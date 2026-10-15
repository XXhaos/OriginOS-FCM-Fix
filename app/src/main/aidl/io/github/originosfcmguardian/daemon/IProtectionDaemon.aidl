package io.github.originosfcmguardian.daemon;

import android.os.Bundle;

/** Separate stable resident owner; no arbitrary shell or caller-supplied configuration patch. */
interface IProtectionDaemon {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    Bundle transact(int operation, in Bundle request) = 2;
}
