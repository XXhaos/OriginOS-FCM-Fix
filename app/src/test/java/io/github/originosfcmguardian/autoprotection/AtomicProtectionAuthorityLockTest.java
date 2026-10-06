package io.github.originosfcmguardian.autoprotection;

import io.github.originosfcmguardian.autostart.AutostartModelsKt;
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity;
import kotlin.jvm.functions.Function0;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual production exclusive() and real domain-store calls; only Android snapshot IO is replaced. */
public class AtomicProtectionAuthorityLockTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private AtomicProtectionAuthorityStorage storage(File lockFile) throws Exception {
        // Constructor creates Android AtomicFile, unavailable to a plain host JVM. exclusive()
        // only needs lockFile; bypass that unrelated constructor but execute the real lock path.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        AtomicProtectionAuthorityStorage storage = (AtomicProtectionAuthorityStorage)
                unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, AtomicProtectionAuthorityStorage.class);
        Field field = AtomicProtectionAuthorityStorage.class.getDeclaredField("lockFile");
        field.setAccessible(true);
        field.set(storage, lockFile);
        return storage;
    }

    private static class SnapshotView implements ProtectionAuthorityStorage {
        final AtomicProtectionAuthorityStorage locking;
        final AtomicReference<ProtectionAuthoritySnapshot> snapshot;
        Runnable beforeRead = () -> {};
        SnapshotView(AtomicProtectionAuthorityStorage locking, AtomicReference<ProtectionAuthoritySnapshot> snapshot) {
            this.locking = locking; this.snapshot = snapshot;
        }
        @Override public <T> T exclusive(Function0<? extends T> action) { return locking.exclusive(action); }
        @Override public ProtectionAuthoritySnapshot read() { beforeRead.run(); return snapshot.get(); }
        @Override public void write(ProtectionAuthoritySnapshot value) { snapshot.set(value); }
    }

    private static void await(CountDownLatch signal) {
        try { assertTrue(signal.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void blockedOnMonitor(Thread contender, Future<?> result) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (contender.getState() != Thread.State.BLOCKED) {
            assertFalse("Contender must wait for the local monitor, not fail its file lock", result.isDone());
            assertTrue("Contender did not reach the monitor", System.nanoTime() < deadline);
            Thread.yield();
        }
    }

    private void concurrentDomainTransaction(boolean suspend) throws Exception {
        File lockFile = temporary.newFile("authority-" + suspend + ".lock");
        AtomicReference<ProtectionAuthoritySnapshot> snapshot = new AtomicReference<>();
        SnapshotView firstView = new SnapshotView(storage(lockFile), snapshot);
        SnapshotView secondView = new SnapshotView(storage(lockFile), snapshot);
        ProtectionConsentStore first = new ProtectionConsentStore(firstView);
        ProtectionConsentStore second = new ProtectionConsentStore(secondView);
        BudgetClock clock = new BudgetClock(14, 600_000, 1_600_000);
        first.grantRecipientExplicit(new SelectedTargetIdentity("example.recipient", 10435, 101,
                Collections.singletonList("b".repeat(64))), AutostartModelsKt.VERIFIED_AUTOSTART_FINGERPRINT, clock);
        assertTrue(first.enableMaster(first.readSnapshot(), clock));
        TargetConsentLease lease = first.desiredLeases().get(0);
        ProtectionAuthoritySnapshot active = first.readSnapshot();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        AtomicReference<Thread> contenderThread = new AtomicReference<>();
        firstView.beforeRead = () -> { entered.countDown(); await(release); };
        secondView.beforeRead = secondRead::countDown;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<ProtectionAuthoritySnapshot> reader = workers.submit(first::readSnapshot);
        try {
            await(entered);
            Future<Boolean> contender = workers.submit(() -> {
                contenderThread.set(Thread.currentThread()); attempting.countDown();
                return suspend ? second.suspendLease(lease, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                        : second.verifyAuthorized(lease);
            });
            await(attempting); blockedOnMonitor(contenderThread.get(), contender);
            assertEquals(1, secondRead.getCount());
            release.countDown();
            assertEquals(active, reader.get(5, TimeUnit.SECONDS));
            assertTrue(contender.get(5, TimeUnit.SECONDS));
            assertEquals(0, secondRead.getCount());
            if (suspend) {
                assertEquals(ProtectionConsentMode.SUSPENDED, snapshot.get().getMode());
                assertEquals(active.getGeneration() + 1, snapshot.get().getGeneration());
                assertEquals(active.getTargetConsents(), snapshot.get().getTargetConsents());
                assertFalse(second.verifyAuthorized(lease));
            } else {
                assertEquals(active, snapshot.get()); assertTrue(second.verifyAuthorized(lease));
            }
        } finally {
            release.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void twoInstancesReadAndVerifyAuthorizedSerializeWithoutFalseRevocation() throws Exception {
        concurrentDomainTransaction(false);
    }

    @Test public void twoInstancesReadAndSuspendSerializeAndPreserveExactConsentChoices() throws Exception {
        concurrentDomainTransaction(true);
    }

    @Test public void exceptionReleasesMonitorFileLockAndHandle() throws Exception {
        File lockFile = temporary.newFile("exception.lock");
        AtomicProtectionAuthorityStorage first = storage(lockFile);
        AtomicProtectionAuthorityStorage second = storage(lockFile);
        IllegalStateException original = new IllegalStateException("action failed");
        assertSame(original, assertThrows(IllegalStateException.class, () -> first.exclusive(() -> { throw original; })));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { assertTrue(worker.submit(() -> second.exclusive(() -> true)).get(5, TimeUnit.SECONDS)); }
        finally { worker.shutdownNow(); }
        try (RandomAccessFile handle = new RandomAccessFile(lockFile, "rw"); FileLock lock = handle.getChannel().tryLock()) {
            assertNotNull(lock);
        }
        // On this Windows host moving the exact temp file also verifies no old handle is retained.
        File moved = new File(temporary.getRoot(), "exception-moved.lock");
        Files.move(lockFile.toPath(), moved.toPath()); Files.move(moved.toPath(), lockFile.toPath());
    }

    /** Separate JVM holds the OS lock, independent of the production process-local monitor. */
    public static class OsLockHolder {
        public static void main(String[] args) throws Exception {
            try (RandomAccessFile handle = new RandomAccessFile(args[0], "rw");
                 FileLock lock = handle.getChannel().lock();
                 BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("LOCKED"); System.out.flush();
                if (!"RELEASE".equals(input.readLine())) throw new IllegalStateException("Unexpected release protocol");
            }
        }
    }

    @Test public void subprocessOsLockStillDeniesAuthorityAndFailureReleasesLocalMonitor() throws Exception {
        File lockFile = temporary.newFile("cross-process.lock");
        AtomicProtectionAuthorityStorage authority = storage(lockFile);
        String executable = new File(System.getProperty("java.home"),
                System.getProperty("os.name").startsWith("Windows") ? "bin/java.exe" : "bin/java").getPath();
        String tests = new File(AtomicProtectionAuthorityLockTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        // The child uses JDK APIs only; Gradle's full Android classpath exceeds Windows command limits.
        Process child = new ProcessBuilder(executable, "-cp", tests,
                OsLockHolder.class.getName(), lockFile.getAbsolutePath()).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream()));
            assertEquals("LOCKED", reader.submit(output::readLine).get(5, TimeUnit.SECONDS));
            IllegalStateException denied = assertThrows(IllegalStateException.class, () -> authority.exclusive(() -> {
                fail("Cross-process contention must not admit the action"); return true;
            }));
            assertEquals("Protection authority owner busy", denied.getMessage());
            PrintWriter input = new PrintWriter(child.getOutputStream()); input.println("RELEASE"); input.flush();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
            assertTrue(authority.exclusive(() -> true));
        } finally {
            child.destroyForcibly(); reader.shutdownNow();
        }
    }
}
