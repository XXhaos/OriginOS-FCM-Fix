package io.github.originosfcmguardian.privilegebridge;

import android.os.Bundle;
import android.os.IBinder;

/** Fixed identity read only. No command, target or recovery argument. */
interface IReadOnlyPrivilegeIdentity {
    void destroy() = 16777114;
    Bundle readIdentity(long generation, IBinder owner) = 1;
    oneway void cancel() = 2;
    // Priv Kit relays Binder calls. Return the dedicated endpoint so reads authenticate directly.
    IBinder getReadEndpoint() = 3;
}
