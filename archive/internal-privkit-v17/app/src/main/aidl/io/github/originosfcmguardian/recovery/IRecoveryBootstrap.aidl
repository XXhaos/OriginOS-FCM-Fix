package io.github.originosfcmguardian.recovery;
import android.os.IBinder;
/** Dedicated Priv Kit bootstrap only; no recovery call may go through its shell relay. */
interface IRecoveryBootstrap {
    void destroy() = 16777114;
    IBinder getRecoveryEndpoint(long generation, IBinder owner) = 1;
}
