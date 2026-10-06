package io.github.originosfcmguardian.privileged;

import android.os.Bundle;
import android.os.IBinder;

interface IGuardianUserService {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    Bundle execute(long requestId, String operationId, int uidArgument, String packageArgument) = 2;
    void cancel(long requestId) = 3;
    // Dedicated read-only fixed Telegram/user-0 full identity observation. No target arguments.
    Bundle readTelegramStoppedIdentity(long requestId) = 4;
    // Fixed GMS/user-0 read-only socket and manifest receiver metadata. No command arguments.
    Bundle readGmsConnection(long requestId) = 5;
    // Explicit one-shot manual GMS/user-0 heartbeat. No caller-supplied target or action.
    Bundle requestGmsHeartbeat(long requestId) = 6;
    Bundle armAutomaticGmsHeartbeat(long requestId, long generation, String expectedBindingDigest, IBinder lifetime) = 7;
    Bundle requestAutomaticGmsHeartbeat(long requestId, String sessionId, long generation, String bindingDigest, String attemptId) = 8;
    boolean revokeAutomaticGmsHeartbeat(long generation, String sessionId) = 9;
    // Fixed read-only caller identity proof. Current UID/PID are captured from Binder, never supplied.
    Bundle qualifyHeartbeatCaller(long requestId, String previousIdentity) = 10;
}
