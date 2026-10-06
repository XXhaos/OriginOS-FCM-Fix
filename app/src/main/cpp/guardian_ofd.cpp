#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
// Stable wire codes shared with GuardianOfdNative.Status. No path/open/close/dup JNI API.
constexpr jlong OK = 0;
constexpr jlong BUSY = 1;
constexpr jlong UNSUPPORTED = 2;
constexpr jlong IDENTITY_MISMATCH = 3;
constexpr jlong INVALID = 4;
constexpr jlong ERROR = 5;

struct Check { jlong status; int error; struct stat info; };

Check inspect(jint fd, jint owner) {
    Check result{OK, 0, {}};
    if (fd < 0 || owner < 10000 || owner > 99999) {
        result.status = INVALID;
        return result;
    }
    if (fstat(fd, &result.info) != 0) {
        result.error = errno;
        result.status = ERROR;
        return result;
    }
    if (!S_ISREG(result.info.st_mode) || result.info.st_nlink != 1 ||
        (result.info.st_mode & 0077) != 0) {
        result.status = INVALID;
        return result;
    }
    if (result.info.st_uid != static_cast<uid_t>(owner)) {
        result.status = IDENTITY_MISMATCH;
        return result;
    }
    const int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        result.error = errno;
        result.status = ERROR;
    } else if ((flags & O_ACCMODE) != O_RDWR || (flags & O_PATH) != 0) {
        result.status = INVALID;
    }
    return result;
}

jlongArray reply(JNIEnv* env, const jlong* values, jsize count) {
    jlongArray array = env->NewLongArray(count);
    if (array != nullptr) env->SetLongArrayRegion(array, 0, count, values);
    return array;
}

jlongArray lock(JNIEnv* env, jint fd, jint owner, jlong device, jlong inode,
                jint mode, bool acquire) {
    Check checked = inspect(fd, owner);
    if (checked.status == OK &&
        (static_cast<jlong>(checked.info.st_dev) != device ||
         static_cast<jlong>(checked.info.st_ino) != inode ||
         static_cast<jint>(checked.info.st_mode) != mode)) {
        checked.status = IDENTITY_MISMATCH;
    }
    if (checked.status == OK) {
#if defined(F_OFD_SETLK)
        struct flock range{};
        range.l_type = acquire ? F_WRLCK : F_UNLCK;
        range.l_whence = SEEK_SET;
        range.l_start = 0;
        range.l_len = 0;  // Whole stable inode, including future bytes.
        range.l_pid = 0;  // Mandatory OFD owner semantics, never process locks.
        if (fcntl(fd, F_OFD_SETLK, &range) == -1) {
            checked.error = errno;  // Capture immediately, never retry/block/fallback.
            // EACCES can be SELinux/file-lock permission denial, not contention evidence.
            // Keep it ERROR; qualify mutual exclusion only with actual EAGAIN plus free-lock OK.
            if (acquire && checked.error == EAGAIN) {
                checked.status = BUSY;
            } else if (checked.error == EINVAL || checked.error == ENOSYS ||
                       checked.error == EOPNOTSUPP) {
                checked.status = UNSUPPORTED;
            } else {
                checked.status = ERROR;
            }
        }
#else
        (void) acquire;
        checked.status = UNSUPPORTED;
#endif
    }
    const jlong values[]{checked.status, checked.error};
    return reply(env, values, 2);
}
}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_originosfcmguardian_doze_ofd_GuardianOfdNative_nativeIdentity(
        JNIEnv* env, jclass, jint fd, jint owner) {
    const Check checked = inspect(fd, owner);
    const jlong values[]{checked.status, checked.error,
        checked.status == OK ? static_cast<jlong>(checked.info.st_dev) : 0,
        checked.status == OK ? static_cast<jlong>(checked.info.st_ino) : 0,
        checked.status == OK ? static_cast<jlong>(checked.info.st_uid) : 0,
        checked.status == OK ? static_cast<jlong>(checked.info.st_mode) : 0};
    return reply(env, values, 6);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_originosfcmguardian_doze_ofd_GuardianOfdNative_nativeTryLock(
        JNIEnv* env, jclass, jint fd, jint owner, jlong device, jlong inode, jint mode) {
    return lock(env, fd, owner, device, inode, mode, true);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_originosfcmguardian_doze_ofd_GuardianOfdNative_nativeUnlock(
        JNIEnv* env, jclass, jint fd, jint owner, jlong device, jlong inode, jint mode) {
    return lock(env, fd, owner, device, inode, mode, false);
}
