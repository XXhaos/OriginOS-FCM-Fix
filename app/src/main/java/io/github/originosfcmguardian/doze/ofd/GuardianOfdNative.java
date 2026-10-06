package io.github.originosfcmguardian.doze.ofd;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.annotation.Keep;
import io.github.originosfcmguardian.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Fixed native OFD primitive, currently only used by the isolated scratch qualification.
 * Caller owns every borrowed PFD and must serialize close/recycle with each call. This class
 * never duplicates or closes borrowed descriptors. Independent contenders MUST independently
 * open the inode; a transported dup of A still owns A's lock and cannot exclude A.
 * Success proves a syscall result, never command completion, durable OFF or runtime qualification.
 */
@Keep
public final class GuardianOfdNative {
    private static final String PACKAGE = "io.github.originosfcmguardian";
    private static final String LIBRARY = "libguardian_ofd.so";
    private static final String SIGNER = "d662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d";
    private static volatile boolean loaded;
    private GuardianOfdNative() {}

    public enum Status { OK, BUSY, UNSUPPORTED, IDENTITY_MISMATCH, INVALID, ERROR }

    public static final class Result {
        public final Status status;
        /** Native errno captured immediately; zero means a validation/load failure or success. */
        public final int errno;
        private Result(Status status, int errno) { this.status = status; this.errno = errno; }
        public boolean isOk() { return status == Status.OK; }
    }

    public static final class Identity {
        public final long dev;
        public final long ino;
        public final int ownerUid;
        public final int mode;
        public Identity(long dev, long ino, int ownerUid, int mode) {
            this.dev = dev; this.ino = ino; this.ownerUid = ownerUid; this.mode = mode;
        }
        public boolean sameFile(Identity other) {
            return other != null && dev == other.dev && ino == other.ino &&
                    ownerUid == other.ownerUid && mode == other.mode;
        }
    }

    public static final class IdentityResult {
        public final Result result;
        /** Null on every failure. */
        public final Identity identity;
        private IdentityResult(Result result, Identity identity) {
            this.result = result; this.identity = identity;
        }
    }

    public static synchronized Result loadForApp(Context context) {
        try {
            if (context == null || !PACKAGE.equals(context.getPackageName()) ||
                    Os.getuid() < 10000 || Os.getuid() > 99999) return invalid();
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            verifyPackage(info, manager.getPackagesForUid(Os.getuid()), Os.getuid());
            return loadVerified(info.applicationInfo);
        } catch (PackageManager.NameNotFoundException | IOException | ErrnoException |
                 NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            return unsupported();
        }
    }

    /** Trusted caller UID is obtained by the shell service's independent Binder caller gate. */
    public static synchronized Result loadForShell(int trustedGuardianUid) {
        try {
            if (Os.getuid() != 2000 || trustedGuardianUid < 10000 || trustedGuardianUid > 99999)
                return invalid();
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getMethod("getService", String.class);
            if (!Modifier.isStatic(getService.getModifiers()) || getService.getReturnType() != IBinder.class)
                return unsupported();
            IBinder binder = (IBinder) getService.invoke(null, "package");
            if (binder == null) return unsupported();
            Class<?> api = Class.forName("android.content.pm.IPackageManager");
            Method asInterface = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class);
            if (!Modifier.isStatic(asInterface.getModifiers()) || asInterface.getReturnType() != api)
                return unsupported();
            Object remote = asInterface.invoke(null, binder);
            if (!api.isInstance(remote)) return unsupported();
            Method getPackage = api.getMethod("getPackageInfo", String.class, long.class, int.class);
            Method getPackages = api.getMethod("getPackagesForUid", int.class);
            if (getPackage.getReturnType() != PackageInfo.class || getPackages.getReturnType() != String[].class)
                return unsupported();
            PackageInfo info = (PackageInfo) getPackage.invoke(remote, PACKAGE,
                    (long) PackageManager.GET_SIGNING_CERTIFICATES, 0);
            String[] packages = (String[]) getPackages.invoke(remote, trustedGuardianUid);
            verifyPackage(info, packages, trustedGuardianUid);
            return loadVerified(info.applicationInfo);
        } catch (ReflectiveOperationException | IOException | ErrnoException |
                 NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            return unsupported();
        }
    }

    private static void verifyPackage(PackageInfo info, String[] packages, int uid)
            throws IOException, NoSuchAlgorithmException {
        if (info == null || !PACKAGE.equals(info.packageName) || info.applicationInfo == null ||
                !PACKAGE.equals(info.applicationInfo.packageName) || info.applicationInfo.uid != uid ||
                info.getLongVersionCode() != BuildConfig.VERSION_CODE ||
                (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0 ||
                packages == null || packages.length != 1 || !PACKAGE.equals(packages[0]) ||
                info.signingInfo == null || info.signingInfo.getApkContentsSigners().length != 1)
            throw new IOException("Guardian installed identity unavailable");
        byte[] signature = info.signingInfo.getApkContentsSigners()[0].toByteArray();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature);
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++)
            expected[i] = (byte) Integer.parseInt(SIGNER.substring(i * 2, i * 2 + 2), 16);
        if (!Arrays.equals(digest, expected)) throw new IOException("Guardian signer mismatch");
    }

    private static Result loadVerified(ApplicationInfo info) throws IOException, ErrnoException {
        String source = info.sourceDir;
        ClassLoader loader = GuardianOfdNative.class.getClassLoader();
        if (source == null || !new File(source).getCanonicalPath().equals(source) ||
                loader == null || !loader.toString().contains("\"" + source + "\""))
            throw new IOException("Loaded APK identity unavailable");
        String directory = info.nativeLibraryDir;
        if (directory == null || !new File(directory).getCanonicalPath().equals(directory))
            throw new IOException("Native directory is not canonical");
        File library = new File(directory, LIBRARY);
        if (!library.getCanonicalPath().equals(library.getAbsolutePath()))
            throw new IOException("Native library alias rejected");
        File ancestor = library.getParentFile();
        File appRoot = new File("/data/app");
        if (!library.getAbsolutePath().startsWith("/data/app/"))
            throw new IOException("Native library is not installed app code");
        while (ancestor != null) {
            StructStat stat = Os.lstat(ancestor.getAbsolutePath());
            // The verified phone also uses 0771/0775 system-group-writable install ancestors.
            // Trust only root/system owner AND group throughout the fresh PM-resolved tree;
            // no app/shell-writable group or world-write is accepted. The .so stays stricter.
            if (!OsConstants.S_ISDIR(stat.st_mode) || (stat.st_uid != 0 && stat.st_uid != 1000) ||
                    (stat.st_gid != 0 && stat.st_gid != 1000) || (stat.st_mode & 0002) != 0)
                throw new IOException("Native parent is writable or aliased");
            if (ancestor.equals(appRoot)) break;
            ancestor = ancestor.getParentFile();
        }
        if (ancestor == null) throw new IOException("Native installed root unavailable");
        StructStat stat = Os.lstat(library.getAbsolutePath());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1 ||
                (stat.st_uid != 0 && stat.st_uid != 1000) || (stat.st_mode & 0022) != 0 ||
                stat.st_size <= 0 || stat.st_size > 16 * 1024 * 1024)
            throw new IOException("Native library identity rejected");
        if (!loaded) {
            // Only this constant basename in fresh verified system package metadata is accepted.
            System.load(library.getAbsolutePath());
            loaded = true;
        }
        return ok();
    }

    public static IdentityResult identity(ParcelFileDescriptor fd, int expectedOwnerUid) {
        if (!loaded) return new IdentityResult(unsupported(), null);
        if (fd == null) return new IdentityResult(invalid(), null);
        try {
            long[] wire = nativeIdentity(fd.getFd(), expectedOwnerUid);
            Result result = decode(wire, 6);
            if (!result.isOk()) return new IdentityResult(result, null);
            return new IdentityResult(result, new Identity(wire[2], wire[3], (int) wire[4], (int) wire[5]));
        } catch (IllegalStateException failure) {
            return new IdentityResult(invalid(), null);
        } catch (RuntimeException | LinkageError failure) {
            return new IdentityResult(unsupported(), null);
        }
    }

    public static Result tryLock(ParcelFileDescriptor fd, Identity identity) {
        return changeLock(fd, identity, true);
    }
    public static Result unlock(ParcelFileDescriptor fd, Identity identity) {
        return changeLock(fd, identity, false);
    }
    private static Result changeLock(ParcelFileDescriptor fd, Identity identity, boolean acquire) {
        if (!loaded) return unsupported();
        if (fd == null || identity == null) return invalid();
        try {
            long[] wire = acquire ? nativeTryLock(fd.getFd(), identity.ownerUid, identity.dev,
                    identity.ino, identity.mode) : nativeUnlock(fd.getFd(), identity.ownerUid,
                    identity.dev, identity.ino, identity.mode);
            return decode(wire, 2);
        } catch (IllegalStateException failure) { return invalid(); }
        catch (RuntimeException | LinkageError failure) { return unsupported(); }
    }

    private static Result decode(long[] wire, int length) {
        if (wire == null || wire.length != length || wire[0] < 0 ||
                wire[0] >= Status.values().length || wire[1] < 0 || wire[1] > Integer.MAX_VALUE)
            return unsupported();
        return new Result(Status.values()[(int) wire[0]], (int) wire[1]);
    }
    static Result ok() { return new Result(Status.OK, 0); }
    static Result invalid() { return new Result(Status.INVALID, 0); }
    static Result mismatch() { return new Result(Status.IDENTITY_MISMATCH, 0); }
    private static Result unsupported() { return new Result(Status.UNSUPPORTED, 0); }
    private static native long[] nativeIdentity(int fd, int ownerUid);
    private static native long[] nativeTryLock(int fd, int ownerUid, long dev, long ino, int mode);
    private static native long[] nativeUnlock(int fd, int ownerUid, long dev, long ino, int mode);
}
