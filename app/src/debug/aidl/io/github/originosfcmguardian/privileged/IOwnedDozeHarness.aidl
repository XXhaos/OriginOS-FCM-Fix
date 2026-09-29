package io.github.originosfcmguardian.privileged;

import android.os.Bundle;

/** Debug-only, single-owned-package DeviceIdle experiment. */
interface IOwnedDozeHarness {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    Bundle probe(int expectedUid, String expectedFingerprint) = 2;
    Bundle add(int expectedUid, String expectedFingerprint) = 3;
    Bundle remove(int expectedUid, String expectedFingerprint) = 4;
}
