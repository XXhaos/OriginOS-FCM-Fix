package io.github.originosfcmguardian.doze;

import android.os.Bundle;
import io.github.originosfcmguardian.doze.IDozeJournalCallback;

/** Fixed GMS operation surface: callers cannot choose a package, UID, command or executable. */
interface IGmsDozeTransaction {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    Bundle inspect(IDozeJournalCallback journal) = 2;
    Bundle apply(IDozeJournalCallback journal) = 3;
    Bundle restore(IDozeJournalCallback journal) = 4;
}
