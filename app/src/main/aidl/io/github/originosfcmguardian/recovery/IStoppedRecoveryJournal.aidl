package io.github.originosfcmguardian.recovery;
/** One app-owned durable claim; the server cannot supply or overwrite a journal record. */
interface IStoppedRecoveryJournal {
    boolean isSessionActive();
    boolean claim(long requestId);
}
