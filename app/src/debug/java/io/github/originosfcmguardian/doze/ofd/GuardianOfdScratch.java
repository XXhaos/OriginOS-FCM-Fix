package io.github.originosfcmguardian.doze.ofd;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import io.github.originosfcmguardian.BuildConfig;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import static io.github.originosfcmguardian.doze.ofd.GuardianOfdNative.*;

/** Debug source only: private fixed scratch opens, never production writer admission. */
public final class GuardianOfdScratch {
    private GuardianOfdScratch() {}

    /** No caller supplied path; never truncates/replaces an existing lock inode. */
    public static ScratchPair openScratchPair(Context context) throws IOException {
        if (!BuildConfig.DEBUG) throw new IOException("Scratch qualification requires debug APK");
        if (!GuardianOfdNative.loadForApp(context).isOk()) throw new IOException("Native runtime unavailable");
        int uid = Os.getuid();
        ParcelFileDescriptor a = null;
        ParcelFileDescriptor b = null;
        try {
            File root = context.getFilesDir().getCanonicalFile();
            // Context filesDir may grant its own app GID access and others search permission.
            // The scratch subdirectory itself remains owner-only 0700.
            StructStat rootStat = Os.lstat(root.getAbsolutePath());
            if (!OsConstants.S_ISDIR(rootStat.st_mode) || rootStat.st_uid != uid ||
                    rootStat.st_gid != uid || (rootStat.st_mode & 0006) != 0)
                throw new IOException("Guardian files directory identity rejected");
            File directory = new File(root, "guardian-ofd-qualification");
            try { Os.mkdir(directory.getAbsolutePath(), 0700); }
            catch (ErrnoException failure) { if (failure.errno != OsConstants.EEXIST) throw failure; }
            requirePrivateDirectory(directory, uid);
            File file = new File(directory, "stable.lock");
            a = openFixed(file);
            IdentityResult first = GuardianOfdNative.identity(a, uid);
            if (!first.result.isOk()) throw new IOException("Scratch identity unavailable");
            // Second open(), never dup(A): a different open-file-description owner.
            b = openFixed(file);
            IdentityResult second = GuardianOfdNative.identity(b, uid);
            if (!second.result.isOk() || !first.identity.sameFile(second.identity))
                throw new IOException("Scratch independently opened inode mismatch");
            ScratchPair pair = new ScratchPair(a, b, first.identity, file);
            if (!pair.verifyPath().isOk()) throw new IOException("Scratch path identity changed");
            return pair;
        } catch (ErrnoException | RuntimeException failure) {
            IOException error = new IOException("Scratch descriptor setup failed", failure);
            closeSuppressed(b, error); closeSuppressed(a, error); throw error;
        } catch (IOException failure) {
            closeSuppressed(b, failure); closeSuppressed(a, failure); throw failure;
        }
    }

    private static ParcelFileDescriptor openFixed(File file) throws ErrnoException, IOException {
        FileDescriptor raw = Os.open(file.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_CREAT |
                OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0600);
        ParcelFileDescriptor result = null;
        try {
            // Dup only this newly opened description; each contender still gets its own open().
            result = ParcelFileDescriptor.dup(raw);
            return result;
        } finally {
            try { Os.close(raw); }
            catch (ErrnoException failure) {
                IOException error = new IOException("Scratch raw descriptor close failed", failure);
                closeSuppressed(result, error);
                throw error;
            }
        }
    }

    private static void requirePrivateDirectory(File directory, int uid) throws ErrnoException, IOException {
        StructStat stat = Os.lstat(directory.getAbsolutePath());
        if (!directory.getCanonicalPath().equals(directory.getAbsolutePath()) ||
                !OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != uid || (stat.st_mode & 0077) != 0)
            throw new IOException("Scratch directory identity rejected");
    }

    public static final class ScratchPair implements AutoCloseable {
        public final ParcelFileDescriptor a;
        public final ParcelFileDescriptor b;
        public final Identity identity;
        public final File file;
        private boolean closed;
        private ScratchPair(ParcelFileDescriptor a, ParcelFileDescriptor b, Identity identity, File file) {
            this.a = a; this.b = b; this.identity = identity; this.file = file;
        }
        /** Check A and the path: old FDs can remain valid after rename; B may already be closed. */
        public synchronized Result verifyPath() {
            if (closed) return GuardianOfdNative.invalid();
            try {
                IdentityResult open = GuardianOfdNative.identity(a, identity.ownerUid);
                if (!open.result.isOk()) return open.result;
                if (!identity.sameFile(open.identity)) return GuardianOfdNative.mismatch();
                requirePrivateDirectory(file.getParentFile(), identity.ownerUid);
                StructStat stat = Os.lstat(file.getAbsolutePath());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1 ||
                        stat.st_uid != identity.ownerUid || stat.st_dev != identity.dev ||
                        stat.st_ino != identity.ino || stat.st_mode != identity.mode)
                    return GuardianOfdNative.mismatch();
                return GuardianOfdNative.ok();
            } catch (IOException | ErrnoException | RuntimeException failure) {
                return GuardianOfdNative.mismatch();
            }
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try { b.close(); } catch (IOException error) { failure = error; }
            try { a.close(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    private static void closeSuppressed(ParcelFileDescriptor fd, IOException failure) {
        if (fd != null) try { fd.close(); } catch (IOException error) { failure.addSuppressed(error); }
    }
}
