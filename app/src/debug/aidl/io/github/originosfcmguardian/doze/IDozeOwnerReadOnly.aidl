package io.github.originosfcmguardian.doze;

import android.os.Bundle;

/** Debug-only fixed settings read; the Binder is a client-death token, not a request selector. */
interface IDozeOwnerReadOnly {
    Bundle read(IBinder clientLifetime) = 1;
    void cancel() = 2;
    Bundle readEffectiveOwners(IBinder clientLifetime) = 3;
    void destroy() = 16777114;
}
