package io.github.originosfcmguardian.recovery;
import io.github.originosfcmguardian.recovery.IStoppedRecoveryJournal;
interface IStoppedRecovery {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    String observe(long requestId, IStoppedRecoveryJournal journal) = 2;
    String recover(long requestId, String expectedIdentity, IStoppedRecoveryJournal journal) = 3;
    void cancel() = 4;
    String observeTarget(long requestId, String expectedIdentity, IStoppedRecoveryJournal journal) = 5;
    String recoverTarget(long requestId, String expectedIdentity, IStoppedRecoveryJournal journal) = 6;
}
