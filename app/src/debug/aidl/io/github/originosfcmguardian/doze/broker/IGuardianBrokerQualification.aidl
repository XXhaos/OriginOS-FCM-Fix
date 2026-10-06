package io.github.originosfcmguardian.doze.broker;

import android.os.Bundle;
import android.os.IBinder;

/** Debug-only fixed sleep qualification; no command, package, UID or operation input. */
interface IGuardianBrokerQualification {
    void destroy() = 16777114;
    Bundle hello(IBinder clientLifetime) = 1;
    Bundle activate(in byte[] pin) = 2;
    Bundle start(in byte[] prepared) = 3;
    Bundle awaitCleanup(in byte[] preparation) = 4;
    Bundle revoke(in byte[] off) = 5;
    Bundle acknowledgeResult(in byte[] result) = 6;
}
