import ctypes
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import private_host_files as private


def set_windows_test_acl(path, sddl):
    """Only callers' synthetic TemporaryDirectory files may be passed here."""
    api = private._windows()
    descriptor = ctypes.c_void_p()
    if not api.sddl(sddl, 1, ctypes.byref(descriptor), None):
        raise AssertionError("Synthetic ACL conversion failed")
    try:
        setter = api.a.SetFileSecurityW
        setter.argtypes = [api.w.LPCWSTR, api.w.DWORD, ctypes.c_void_p]
        setter.restype = api.w.BOOL
        if not setter(str(path), 4 | 0x80000000, descriptor):
            raise AssertionError("Synthetic ACL update failed")
    finally:
        api.free(descriptor)


class PrivateHostFilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "private-run"
        private.ensure_private_directory(self.root)
        self.path = self.root / "synthetic-private"

    def tearDown(self):
        self.temp.cleanup()

    def test_real_secure_creation_bounded_read_and_exact_handle_consumption(self):
        private.write_exclusive(self.path, b"synthetic-only")
        token = private.open_private_token(self.path, 32)
        try:
            self.assertEqual(b"synthetic-only", token.raw)
            self.assertEqual(2, len(token.identity))
            self.assertEqual("CONSUMED", token.consume())
        finally:
            token.close()
        self.assertFalse(self.path.exists())

    def test_held_marker_append_replace_and_exclusive_retry(self):
        marker = private.create_private_marker(self.path, b"intent\n")
        try:
            marker.append(b"outcome\n")
            self.assertEqual(b"intent\noutcome\n", self.path.read_bytes())
            with self.assertRaises(FileExistsError):
                private.write_exclusive(self.path, b"replacement")
            marker.replace(b"final")
            self.assertEqual(b"final", self.path.read_bytes())
        finally:
            marker.close()

    def test_oversized_read_closes_handle_without_deleting_file(self):
        private.write_exclusive(self.path, b"synthetic-too-large")
        with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_FILE_TOO_LARGE"):
            private.open_private_token(self.path, 1)
        self.assertTrue(self.path.exists())
        # Closed rejection handle must not leave a Windows sharing lock behind.
        self.path.rename(self.root / "renamed")

    def test_hardlinked_files_and_directories_are_rejected(self):
        private.write_exclusive(self.path, b"synthetic")
        alias = self.root / "hardlink"
        os.link(self.path, alias)
        with self.assertRaises(private.PrivateFileError):
            private.open_private_token(self.path, 32)
        with self.assertRaises(private.PrivateFileError):
            private.open_private_token(self.root, 32)

    def test_unsupported_platform_and_filesystem_fail_closed(self):
        with mock.patch.object(private, "_platform", side_effect=private.PrivateFileError("PRIVATE_PLATFORM_UNSUPPORTED")):
            with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_PLATFORM_UNSUPPORTED"):
                private.write_exclusive(self.path, b"synthetic")
        self.assertFalse(self.path.exists())
        if os.name == "nt":
            with mock.patch.object(private._windows(), "local_ntfs", side_effect=private.PrivateFileError("PRIVATE_FILESYSTEM_UNSUPPORTED")):
                with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_FILESYSTEM_UNSUPPORTED"):
                    private.write_exclusive(self.path, b"synthetic")
            self.assertFalse(self.path.exists())

    def test_failed_flush_leaves_reserved_path_but_closes_marker(self):
        if os.name == "nt":
            patcher = mock.patch.object(private._windows(), "flush", return_value=False)
        else:
            patcher = mock.patch.object(private.os, "fsync", side_effect=OSError())
        with patcher, self.assertRaises((private.PrivateFileError, OSError)):
            private.create_private_marker(self.path, b"intent")
        self.assertTrue(self.path.exists())
        with self.assertRaises(FileExistsError):
            private.write_exclusive(self.path, b"retry")
        self.path.rename(self.root / "closed-after-failure")

    def test_windows_dacl_broad_null_and_foreign_allow_refused_or_posix_mode_refused(self):
        private.write_exclusive(self.path, b"synthetic")
        if os.name == "nt":
            sid = private._windows().user_sid
            for dacl in (f"D:P(A;;FA;;;{sid})(A;;FR;;;WD)", "D:NO_ACCESS_CONTROL",
                         f"D:P(A;;FA;;;{sid})(A;;FR;;;S-1-5-21-1-2-3-1234)"):
                set_windows_test_acl(self.path, dacl)
                with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_ACL_UNSAFE"):
                    private.open_private_token(self.path, 32)
            set_windows_test_acl(self.path, f"D:P(A;;FA;;;{sid})(A;;FA;;;SY)(A;;FA;;;BA)")
        else:
            self.path.chmod(0o644)
            with self.assertRaisesRegex(private.PrivateFileError, "TOKEN_FILE_MODE_NOT_0600"):
                private.open_private_token(self.path, 32)

    def test_windows_held_handle_denies_replacement_and_writes_or_posix_identity_refuses_swap(self):
        private.write_exclusive(self.path, b"original")
        token = private.open_private_token(self.path, 32)
        try:
            if os.name == "nt":
                with self.assertRaises(OSError):
                    self.path.rename(self.root / "renamed")
                with self.assertRaises(OSError):
                    self.path.write_bytes(b"foreign")
            else:
                self.path.rename(self.root / "original")
                private.write_exclusive(self.path, b"foreign")
                self.assertEqual("TOKEN_PATH_CHANGED", token.consume())
                self.assertEqual(b"foreign", self.path.read_bytes())
        finally:
            token.close()

    def test_symlink_policy_rejects_reparse_without_following_target(self):
        private.write_exclusive(self.path, b"synthetic")
        link = self.root / "link"
        try:
            link.symlink_to(self.path)
        except OSError as error:
            if os.name == "nt" and error.winerror == 1314:
                # Real symlink creation requires a Windows privilege; hardlinks and policy mocks still execute.
                api = private._windows()
                actual_info = api.fileinfo
                def reparse_info(handle, pointer):
                    result = actual_info(handle, pointer)
                    attributes = ctypes.cast(pointer, ctypes.POINTER(api.w.DWORD))
                    if not attributes[0] & 0x10:
                        attributes[0] |= 0x400
                    return result
                with mock.patch.object(api, "fileinfo", side_effect=reparse_info):
                    with self.assertRaises(private.PrivateFileError):
                        private.open_private_token(self.path, 32)
                self.skipTest("Windows symlink creation privilege unavailable; mocked reparse rejection exercised")
            raise
        with self.assertRaises(private.PrivateFileError):
            private.open_private_token(link, 32)
        self.assertEqual(b"synthetic", self.path.read_bytes())

    def test_partial_write_loop_completes_without_loss(self):
        if os.name == "nt":
            api = private._windows()
            real_write = api.write
            def partial(handle, data, count, written, overlap):
                return real_write(handle, data, min(count, 2), written, overlap)
            patcher = mock.patch.object(api, "write", side_effect=partial)
        else:
            real_write = os.write
            patcher = mock.patch.object(private.os, "write", side_effect=lambda fd, data: real_write(fd, data[:2]))
        with patcher:
            private.write_exclusive(self.path, b"partial-synthetic")
        self.assertEqual(b"partial-synthetic", self.path.read_bytes())

    def test_pure_windows_path_gate_rejects_streams_namespaces_and_aliases(self):
        invalid = [r"C:\temp\token:stream", r"C:\temp\token::$DATA", r"\\server\share\token",
                   r"\\?\C:\temp\token", r"\\.\C:\temp\token", r"C:relative", r"C:\temp\CON.txt",
                   "C:\\temp\\trailing.", "C:\\temp\\trailing ", r"C:\temp\NUL", r"C:\temp\LPT1.log",
                   r"C:\temp\bad?name", r"C:\temp\..\token"]
        for path in invalid:
            with self.subTest(path=path), self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_PATH_UNSUPPORTED"):
                private._windows_path(path)
        self.assertEqual(r"C:\temp\ordinary.txt", private._windows_path(r"C:\temp\ordinary.txt"))

    def test_windows_ads_rejected_before_native_open_and_cannot_consume_base(self):
        if os.name != "nt":
            return  # The lexical policy above executes on every platform.
        private.write_exclusive(self.path, b"synthetic-base")
        stream = Path(str(self.path) + ":stream")
        with mock.patch.object(private._windows(), "create") as create:
            with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_PATH_UNSUPPORTED"):
                private.open_private_token(stream, 32)
            with self.assertRaisesRegex(private.PrivateFileError, "PRIVATE_PATH_UNSUPPORTED"):
                private.write_exclusive(stream, b"synthetic")
            create.assert_not_called()
        self.assertEqual(b"synthetic-base", self.path.read_bytes())

    def test_existing_nonprivate_directory_is_rejected_without_acl_changes(self):
        directory = Path(self.temp.name) / "nonprivate"
        directory.mkdir()
        if os.name == "nt":
            sid = private._windows().user_sid
            set_windows_test_acl(directory, f"D:P(A;;FA;;;{sid})(A;;FA;;;WD)")
        else:
            directory.chmod(0o755)
        with self.assertRaises(private.PrivateFileError):
            private.ensure_private_directory(directory)
        with self.assertRaises(private.PrivateFileError):
            private.write_exclusive(directory / "intent", b"synthetic")
        self.assertFalse((directory / "intent").exists())


if __name__ == "__main__":
    unittest.main()
