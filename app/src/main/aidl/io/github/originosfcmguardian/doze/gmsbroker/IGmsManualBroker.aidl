package io.github.originosfcmguardian.doze.gmsbroker;

import android.os.Bundle;
import android.os.IBinder;

/** One fixed operation per concrete service; no command, package, UID or operation input. */
interface IGmsManualBroker {
    void destroy() = 16777114;
    Bundle hello(IBinder clientLifetime) = 1;
    Bundle probe() = 2;
    Bundle activate(in byte[] pin) = 3;
    Bundle start(in byte[] prepared) = 4;
    Bundle awaitCleanup(in byte[] preparation) = 5;
    Bundle revoke(in byte[] off) = 6;
    Bundle acknowledgeResult(in byte[] result) = 7;
}
