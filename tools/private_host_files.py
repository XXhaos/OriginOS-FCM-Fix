"""Bounded private host files: POSIX permissions or protected local-NTFS handles.

No network operations. Unsupported security/durability primitives fail closed.
"""
from __future__ import annotations

import ctypes
import ntpath
import os
import re
import stat
from pathlib import Path


class PrivateFileError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _fail(code="PRIVATE_FILE_UNSAFE"):
    raise PrivateFileError(code)


def _platform():
    if os.name not in ("posix", "nt"):
        _fail("PRIVATE_PLATFORM_UNSUPPORTED")
    return os.name


def _windows_path(path):
    """Admit ordinary DOS drive paths only, before any mkdir or native open."""
    raw = str(path).replace("/", "\\")
    if raw.startswith("\\\\") or any(ord(c) < 32 for c in raw):
        _fail("PRIVATE_PATH_UNSUPPORTED")
    drive, tail = ntpath.splitdrive(raw)
    if drive and (len(drive) != 2 or not drive[0].isalpha() or drive[1] != ":" or not tail.startswith("\\")):
        _fail("PRIVATE_PATH_UNSUPPORTED")
    if ":" in tail or any(c in tail for c in '<>"|?*'):
        _fail("PRIVATE_PATH_UNSUPPORTED")
    for part in tail.split("\\"):
        if not part:
            continue
        if part in (".", "..") or part.endswith((".", " ")) or len(part) > 255:
            _fail("PRIVATE_PATH_UNSUPPORTED")
        if re.fullmatch(r"CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]", part.split(".", 1)[0], re.IGNORECASE):
            _fail("PRIVATE_PATH_UNSUPPORTED")
    absolute = ntpath.abspath(raw)
    if len(absolute) >= 260 or len(ntpath.splitdrive(absolute)[0]) != 2:
        _fail("PRIVATE_PATH_UNSUPPORTED")
    return absolute


def _windows_ancestors(path):
    for parent in Path(path).parents:
        try:
            info = parent.lstat()
        except FileNotFoundError:
            continue
        if getattr(info, "st_file_attributes", 0) & 0x400 or stat.S_ISLNK(info.st_mode):
            _fail("PRIVATE_PATH_REPARSE")


def _posix_check(fd):
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid():
        _fail()
    if stat.S_IMODE(info.st_mode) != 0o600:
        _fail("TOKEN_FILE_MODE_NOT_0600")
    return info.st_dev, info.st_ino


def _write_all(fd, data):
    offset = 0
    while offset < len(data):
        count = os.write(fd, data[offset:])
        if count <= 0:
            _fail("PRIVATE_FILE_WRITE_FAILED")
        offset += count


def _sync_parent(path):
    fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class _Windows:
    """Native calls use exact handles; no path ACL changes and no fallback to chmod."""
    def __init__(self):
        from ctypes import wintypes as w
        self.w = w
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        self.a = ctypes.WinDLL("advapi32", use_last_error=True)
        P = ctypes.c_void_p
        D = w.DWORD
        H = w.HANDLE
        B = w.BOOL
        def bind(dll, name, args, result):
            fn = getattr(dll, name)
            fn.argtypes, fn.restype = args, result
            return fn
        self.create = bind(self.k, "CreateFileW", [w.LPCWSTR,D,D,P,D,D,H], H)
        self.createdir = bind(self.k, "CreateDirectoryW", [w.LPCWSTR,P], B)
        self.close = bind(self.k, "CloseHandle", [H], B)
        self.read = bind(self.k, "ReadFile", [H,P,D,ctypes.POINTER(D),P], B)
        self.write = bind(self.k, "WriteFile", [H,P,D,ctypes.POINTER(D),P], B)
        self.flush = bind(self.k, "FlushFileBuffers", [H], B)
        self.seek = bind(self.k, "SetFilePointerEx", [H,ctypes.c_longlong,P,D], B)
        self.truncate = bind(self.k, "SetEndOfFile", [H], B)
        self.disposition = bind(self.k, "SetFileInformationByHandle", [H,ctypes.c_int,P,D], B)
        self.filetype = bind(self.k, "GetFileType", [H], D)
        self.fileinfo = bind(self.k, "GetFileInformationByHandle", [H,P], B)
        self.finalpath = bind(self.k, "GetFinalPathNameByHandleW", [H,w.LPWSTR,D,D], D)
        self.volumepath = bind(self.k, "GetVolumePathNameW", [w.LPCWSTR,w.LPWSTR,D], B)
        self.drivetype = bind(self.k, "GetDriveTypeW", [w.LPCWSTR], w.UINT)
        self.volume = bind(self.k, "GetVolumeInformationW", [w.LPCWSTR,w.LPWSTR,D,P,P,P,w.LPWSTR,D], B)
        self.free = bind(self.k, "LocalFree", [P], P)
        self.process = bind(self.k, "GetCurrentProcess", [], H)
        self.tokenopen = bind(self.a, "OpenProcessToken", [H,D,ctypes.POINTER(H)], B)
        self.tokeninfo = bind(self.a, "GetTokenInformation", [H,ctypes.c_int,P,D,ctypes.POINTER(D)], B)
        self.sidtext = bind(self.a, "ConvertSidToStringSidW", [P,ctypes.POINTER(w.LPWSTR)], B)
        self.sddl = bind(self.a, "ConvertStringSecurityDescriptorToSecurityDescriptorW", [w.LPCWSTR,D,ctypes.POINTER(P),P], B)
        self.security = bind(self.a, "GetSecurityInfo", [H,ctypes.c_int,D,ctypes.POINTER(P),P,ctypes.POINTER(P),P,ctypes.POINTER(P)], D)
        self.control = bind(self.a, "GetSecurityDescriptorControl", [P,ctypes.POINTER(w.WORD),ctypes.POINTER(D)], B)
        self.aclinfo = bind(self.a, "GetAclInformation", [P,P,D,ctypes.c_int], B)
        self.ace = bind(self.a, "GetAce", [P,D,ctypes.POINTER(P)], B)
        self.user_sid = self._user_sid()

    def _sid(self, pointer):
        out = self.w.LPWSTR()
        if not self.sidtext(pointer, ctypes.byref(out)):
            _fail("PRIVATE_ACL_UNAVAILABLE")
        try:
            return out.value
        finally:
            self.free(ctypes.cast(out, ctypes.c_void_p))

    def _user_sid(self):
        token = self.w.HANDLE()
        if not self.tokenopen(self.process(), 8, ctypes.byref(token)):
            _fail("PRIVATE_ACL_UNAVAILABLE")
        try:
            length = self.w.DWORD()
            self.tokeninfo(token, 1, None, 0, ctypes.byref(length))
            if length.value not in range(1, 65537):
                _fail("PRIVATE_ACL_UNAVAILABLE")
            buf = ctypes.create_string_buffer(length.value)
            if not self.tokeninfo(token, 1, buf, length.value, ctypes.byref(length)):
                _fail("PRIVATE_ACL_UNAVAILABLE")
            return self._sid(ctypes.c_void_p.from_buffer(buf).value)
        finally:
            self.close(token)

    def local_ntfs(self, path):
        name = str(path.absolute())
        if name.startswith("\\\\"):
            _fail("PRIVATE_FILESYSTEM_UNSUPPORTED")
        root = ctypes.create_unicode_buffer(32768)
        fs = ctypes.create_unicode_buffer(64)
        if not self.volumepath(name, root, len(root)) or self.drivetype(root.value) != 3:
            _fail("PRIVATE_FILESYSTEM_UNSUPPORTED")
        if not self.volume(root.value, None, 0, None, None, None, fs, len(fs)) or fs.value != "NTFS":
            _fail("PRIVATE_FILESYSTEM_UNSUPPORTED")

    def validate(self, handle, expected_path=None):
        class Info(ctypes.Structure):
            _fields_ = [("attributes",self.w.DWORD), ("created",self.w.FILETIME),
                        ("accessed",self.w.FILETIME), ("written",self.w.FILETIME),
                        ("volume",self.w.DWORD), ("sizehigh",self.w.DWORD), ("sizelow",self.w.DWORD),
                        ("links",self.w.DWORD), ("indexhigh",self.w.DWORD), ("indexlow",self.w.DWORD)]
        info = Info()
        if self.filetype(handle) != 1 or not self.fileinfo(handle, ctypes.byref(info)):
            _fail()
        if info.attributes & (0x10 | 0x400) or info.links != 1:
            _fail()
        final = ctypes.create_unicode_buffer(32768)
        length = self.finalpath(handle, final, len(final), 0)
        if not length or length >= len(final):
            _fail("PRIVATE_FILESYSTEM_UNSUPPORTED")
        name = final.value
        if not name.startswith("\\\\?\\") or name.startswith("\\\\?\\UNC\\"):
            _fail("PRIVATE_FILESYSTEM_UNSUPPORTED")
        final = _windows_path(name[4:])
        if expected_path is not None and ntpath.normcase(final) != ntpath.normcase(_windows_path(expected_path)):
            _fail("PRIVATE_FILE_CHANGED")
        self.local_ntfs(Path(final))
        self.validate_acl(handle)
        return info.volume, (info.indexhigh << 32) | info.indexlow

    def validate_acl(self, handle):
        P = ctypes.c_void_p
        owner, acl, descriptor = P(), P(), P()
        if self.security(handle, 1, 1 | 4, ctypes.byref(owner), None, ctypes.byref(acl), None, ctypes.byref(descriptor)) != 0:
            _fail("PRIVATE_ACL_UNAVAILABLE")
        try:
            control, revision = self.w.WORD(), self.w.DWORD()
            if not self.control(descriptor, ctypes.byref(control), ctypes.byref(revision)):
                _fail("PRIVATE_ACL_UNAVAILABLE")
            if not acl.value or not control.value & 4 or not control.value & 0x1000 or self._sid(owner) != self.user_sid:
                _fail("PRIVATE_ACL_UNSAFE")
            class Size(ctypes.Structure):
                _fields_ = [("count",self.w.DWORD),("used",self.w.DWORD),("free",self.w.DWORD)]
            size = Size()
            if not self.aclinfo(acl, ctypes.byref(size), ctypes.sizeof(size), 2) or not 1 <= size.count <= 16:
                _fail("PRIVATE_ACL_UNSAFE")
            user_allowed = False
            for i in range(size.count):
                ace = P()
                if not self.ace(acl, i, ctypes.byref(ace)):
                    _fail("PRIVATE_ACL_UNSAFE")
                header = ctypes.string_at(ace, 4)
                ace_size = int.from_bytes(header[2:4], "little")
                # Only standard explicit allow ACEs are admitted. Unknown/conditional ACEs fail closed.
                if header[0] != 0 or header[1] != 0 or ace_size < 16:
                    _fail("PRIVATE_ACL_UNSAFE")
                sid = self._sid(P(ace.value + 8))
                if sid not in (self.user_sid, "S-1-5-18", "S-1-5-32-544"):
                    _fail("PRIVATE_ACL_UNSAFE")
                user_allowed |= sid == self.user_sid
            if not user_allowed:
                _fail("PRIVATE_ACL_UNSAFE")
        finally:
            self.free(descriptor)

    def _creation_security(self):
        class SecurityAttributes(ctypes.Structure):
            _fields_ = [("length",self.w.DWORD),("descriptor",ctypes.c_void_p),("inherit",self.w.BOOL)]
        descriptor = ctypes.c_void_p()
        text = f"O:{self.user_sid}D:P(A;;FA;;;{self.user_sid})(A;;FA;;;SY)(A;;FA;;;BA)"
        if not self.sddl(text, 1, ctypes.byref(descriptor), None):
            _fail("PRIVATE_ACL_UNAVAILABLE")
        return descriptor, SecurityAttributes(ctypes.sizeof(SecurityAttributes), descriptor, False)

    def directory(self, path, *, create=False):
        path = Path(_windows_path(path))
        _windows_ancestors(path / "boundary")
        self.local_ntfs(path.parent)
        if create and not path.exists():
            if not path.parent.exists():
                self.directory(path.parent, create=True)
            descriptor, sa = self._creation_security()
            try:
                if not self.createdir(str(path), ctypes.byref(sa)) and ctypes.get_last_error() != 183:
                    _fail("PRIVATE_DIRECTORY_CREATE_FAILED")
            finally:
                self.free(descriptor)
        handle = self.create(str(path), 0x20000 | 0x80, 7, None, 3, 0x2000000 | 0x200000, None)
        if handle == ctypes.c_void_p(-1).value:
            _fail("PRIVATE_DIRECTORY_OPEN_FAILED")
        try:
            # BY_HANDLE_FILE_INFORMATION starts with attributes; the remaining fixed structure is 52 bytes.
            info = ctypes.create_string_buffer(52)
            if not self.fileinfo(handle, info):
                _fail("PRIVATE_DIRECTORY_UNSAFE")
            attributes = int.from_bytes(info.raw[:4], "little")
            if not attributes & 0x10 or attributes & 0x400:
                _fail("PRIVATE_DIRECTORY_UNSAFE")
            final = ctypes.create_unicode_buffer(32768)
            length = self.finalpath(handle, final, len(final), 0)
            if not length or length >= len(final) or not final.value.startswith("\\\\?\\"):
                _fail("PRIVATE_DIRECTORY_UNSAFE")
            normalized = _windows_path(final.value[4:])
            if ntpath.normcase(normalized) != ntpath.normcase(str(path)):
                _fail("PRIVATE_DIRECTORY_UNSAFE")
            self.validate_acl(handle)
        finally:
            self.close(handle)

    def open(self, path, *, create=False, writable=False, deletable=False):
        path = Path(_windows_path(path))
        _windows_ancestors(path)
        self.local_ntfs(path.parent)
        self.directory(path.parent)
        descriptor = ctypes.c_void_p()
        sa = None
        if create:
            descriptor, sa = self._creation_security()
        try:
            access = 0x80000000 | 0x20000 | (0x40000000 if writable else 0) | (0x10000 if deletable else 0)
            handle = self.create(str(path.absolute()), access, 1, ctypes.byref(sa) if sa else None,
                                 1 if create else 3, 0x80 | 0x200000 | (0x80000000 if writable else 0), None)
            if handle == ctypes.c_void_p(-1).value:
                if create and ctypes.get_last_error() in (80, 183):
                    raise FileExistsError()
                _fail("PRIVATE_FILE_OPEN_FAILED")
            try:
                identity = self.validate(handle, path)
            except BaseException:
                self.close(handle)
                raise
            return handle, identity
        finally:
            if descriptor.value:
                self.free(descriptor)


_windows_api = None


def _windows():
    global _windows_api
    if _windows_api is None:
        try:
            _windows_api = _Windows()
        except PrivateFileError:
            raise
        except Exception:
            _fail("PRIVATE_PLATFORM_UNSUPPORTED")
    return _windows_api


class PrivateFile:
    def __init__(self, path, handle, identity, backend, writable=False):
        self.path, self.handle, self.identity, self.backend = path, handle, identity, backend
        self.writable = writable
        self.raw = b""

    def close(self):
        if self.handle is not None:
            if self.backend == "nt":
                _windows().close(self.handle)
            else:
                os.close(self.handle)
            self.handle = None

    def _validate(self):
        if self.handle is None:
            _fail("PRIVATE_FILE_CLOSED")
        current = _windows().validate(self.handle, self.path) if self.backend == "nt" else _posix_check(self.handle)
        if current != self.identity:
            _fail("PRIVATE_FILE_CHANGED")
        if self.backend == "posix":
            info = self.path.lstat()
            if (info.st_dev, info.st_ino) != self.identity or stat.S_ISLNK(info.st_mode):
                _fail("PRIVATE_FILE_CHANGED")

    def _write(self, data, append=False):
        self._validate()
        if not self.writable or not isinstance(data, bytes):
            _fail("PRIVATE_FILE_WRITE_FAILED")
        if self.backend == "nt":
            api = _windows()
            if not api.seek(self.handle, 0, None, 2 if append else 0):
                _fail("PRIVATE_FILE_WRITE_FAILED")
            if not append and not api.truncate(self.handle):
                _fail("PRIVATE_FILE_WRITE_FAILED")
            offset = 0
            while offset < len(data):
                written = api.w.DWORD()
                chunk = data[offset:offset + 1024 * 1024]
                if not api.write(self.handle, chunk, len(chunk), ctypes.byref(written), None) or not written.value:
                    _fail("PRIVATE_FILE_WRITE_FAILED")
                offset += written.value
            if not api.flush(self.handle):
                _fail("PRIVATE_FILE_FLUSH_FAILED")
        else:
            os.lseek(self.handle, 0, os.SEEK_END if append else os.SEEK_SET)
            if not append:
                os.ftruncate(self.handle, 0)
            _write_all(self.handle, data)
            os.fsync(self.handle)
            _sync_parent(self.path)

    def replace(self, data):
        self._write(data)

    def append(self, data):
        self._write(data, append=True)

    def consume(self):
        try:
            self._validate()
            if self.backend == "nt":
                api = _windows()
                delete = api.w.BOOL(True)
                if not api.disposition(self.handle, 4, ctypes.byref(delete), ctypes.sizeof(delete)):
                    return "TOKEN_DELETE_FAILED"
            else:
                self.path.unlink()
                _sync_parent(self.path)
            return "CONSUMED"
        except (OSError, PrivateFileError):
            return "TOKEN_PATH_CHANGED"
        finally:
            self.close()


PrivateMarker = PrivateFile


def _open(path, *, create=False, writable=False, deletable=False):
    path = Path(path)
    backend = _platform()
    try:
        if backend == "nt":
            path = Path(_windows_path(path))
            _windows_ancestors(path)
        if create:
            ensure_private_directory(path.parent)
        if backend == "nt":
            handle, identity = _windows().open(path, create=create, writable=writable, deletable=deletable)
        else:
            if not hasattr(os, "O_NOFOLLOW"):
                _fail("PRIVATE_PLATFORM_UNSUPPORTED")
            flags = (os.O_RDWR if writable else os.O_RDONLY) | os.O_NOFOLLOW
            if create:
                flags |= os.O_CREAT | os.O_EXCL
            handle = os.open(path, flags, 0o600)
            try:
                if create:
                    os.fchmod(handle, 0o600)
                identity = _posix_check(handle)
            except BaseException:
                os.close(handle)
                raise
        return PrivateFile(path, handle, identity, backend, writable)
    except (FileExistsError, PrivateFileError):
        raise
    except (OSError, ValueError, TypeError):
        _fail("PRIVATE_FILE_OPEN_FAILED")


def ensure_private_directory(path: Path) -> None:
    """Create a dedicated private directory, or validate it without altering existing ACLs."""
    path = Path(path)
    try:
        if _platform() == "nt":
            _windows().directory(path, create=True)
        else:
            path.mkdir(mode=0o700, parents=True, exist_ok=True)
            info = path.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                _fail("PRIVATE_DIRECTORY_UNSAFE")
    except PrivateFileError:
        raise
    except OSError:
        _fail("PRIVATE_DIRECTORY_UNSAFE")


def create_private_marker(path: Path, data: bytes) -> PrivateFile:
    file = _open(path, create=True, writable=True)
    try:
        file.replace(data)
        return file
    except BaseException:
        file.close()
        raise


def write_exclusive(path: Path, data: bytes) -> None:
    file = create_private_marker(path, data)
    file.close()


def _modify(path, data, append, expected_identity):
    file = _open(path, writable=True)
    try:
        if expected_identity is not None and file.identity != expected_identity:
            _fail("PRIVATE_FILE_CHANGED")
        file._write(data, append)
    except OSError:
        _fail("PRIVATE_FILE_WRITE_FAILED")
    finally:
        file.close()


def append_existing(path: Path, data: bytes, *, expected_identity=None) -> None:
    _modify(path, data, True, expected_identity)


def replace_existing(path: Path, data: bytes, *, expected_identity=None) -> None:
    _modify(path, data, False, expected_identity)


def open_private_token(path: Path, max_bytes: int) -> PrivateFile:
    if not isinstance(max_bytes, int) or not 0 < max_bytes <= 1024 * 1024:
        _fail("PRIVATE_READ_BOUND_INVALID")
    file = _open(path, deletable=True)
    try:
        if file.backend == "nt":
            api = _windows()
            raw = ctypes.create_string_buffer(max_bytes + 1)
            count = api.w.DWORD()
            if not api.read(file.handle, raw, len(raw), ctypes.byref(count), None):
                _fail("PRIVATE_FILE_READ_FAILED")
            file.raw = raw.raw[:count.value]
        else:
            chunks = []
            remaining = max_bytes + 1
            while remaining:
                chunk = os.read(file.handle, remaining)
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            file.raw = b"".join(chunks)
        if len(file.raw) > max_bytes:
            _fail("PRIVATE_FILE_TOO_LARGE")
        file._validate()
        return file
    except BaseException:
        file.close()
        raise
