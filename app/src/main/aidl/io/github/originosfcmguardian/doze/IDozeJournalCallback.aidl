package io.github.originosfcmguardian.doze;

/** App-owned durable journal. A null expected value means the slot must be empty. */
interface IDozeJournalCallback {
    boolean isSessionActive();
    byte[] load();
    boolean compareAndSet(in byte[] expected, in byte[] replacement);
}
