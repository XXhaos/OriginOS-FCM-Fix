package io.github.originosfcmguardian.doze.ofd;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Debug qualification only: fixed private scratch inode, no command/path selector. */
interface IDozeOfdReadOnly {
    Bundle setup(in ParcelFileDescriptor independentB, in ParcelFileDescriptor duplicateA,
        int expectedGuardianAppUID, long expectedDev, long expectedIno, IBinder clientLifetime) = 1;
    Bundle appHeldProbe() = 2;
    Bundle exportedBClosedProbe() = 3;
    Bundle acquireB() = 4;
    Bundle releaseAndCloseB() = 5;
    void destroy() = 16777114;
}
