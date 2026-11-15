package dev.tuprel.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class NativeRenameProcessTest {
    private static final String IDENTITY = "ext4 1 2 3 4";
    private static final String PROTOCOL_ERROR = "Unverified native publisher response";

    @Test
    void drainsProtocolWhileNoisyStderrIsDiscarded() throws Exception {
        assertResult("noisy", NativeRenameProcess.Completion.MOVED);
    }

    @Test
    void requiresMatchingFrameExitStatusAndEof() throws Exception {
        assertResult("collision", NativeRenameProcess.Completion.COLLISION);
        assertResult("wrongExit", NativeRenameProcess.Completion.UNKNOWN);
        assertResult("trailing", NativeRenameProcess.Completion.UNKNOWN);
    }

    @Test
    void verifiesObservationWithoutSendingGo() throws Exception {
        Process child = launch("observe");
        try {
            try (NativeRenameProcess reader = NativeRenameProcess.start(child)) {
                assertEquals(IDENTITY, reader.identity("PRESENT"));
                assertTrue(reader.complete(13, "OBSERVED"));
            }
            assertStopped(child);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void rejectsOverlongFrameAndStopsNoisyChild() throws Exception {
        rejectInitialFrame("flood");
    }

    @Test
    void rejectsEofInsideFrameWithoutExposingItsContents() throws Exception {
        rejectInitialFrame("partial");
    }

    @Test
    void stopsChildAtDeadlineWhenInitialReadIsBlocked() throws Exception {
        long start = System.nanoTime();
        rejectInitialFrame("silent");
        assertDeadline(start);
    }

    @Test
    void movedWithoutEofIsUnverifiedAndStopsChildOnClose() throws Exception {
        Process child = launch("noEof");
        try {
            long start;
            try (NativeRenameProcess reader = NativeRenameProcess.start(child)) {
                assertEquals(IDENTITY, reader.identity("READY"));
                reader.go();
                start = System.nanoTime();
                assertEquals(PROTOCOL_ERROR, assertThrows(IOException.class, reader::renameResult).getMessage());
                assertDeadline(start);
            }
            assertStopped(child);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void interruptingBlockedRenameStopsChildAndLeavesTheResultUnconfirmed() throws Exception {
        Process child = launch("noEof");
        CountDownLatch sentGo = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try (NativeRenameProcess reader = NativeRenameProcess.start(child)) {
                reader.go();
                sentGo.countDown();
                outcome.set(new AssertionError("The protocol completed without EOF: " + reader.renameResult()));
            } catch (IOException | InterruptedException failure) {
                outcome.set(failure);
            }
        }, "publication-test-caller");
        caller.setDaemon(true);
        try {
            caller.start();
            assertTrue(sentGo.await(5, TimeUnit.SECONDS), "GO was not sent");
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < limit) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertEquals(Thread.State.TIMED_WAITING, caller.getState());
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive(), "Interrupted caller did not return");
            assertInstanceOf(InterruptedException.class, outcome.get());
            assertStopped(child);
        } finally {
            caller.interrupt();
            child.destroyForcibly();
        }
    }

    private static void assertResult(String mode, NativeRenameProcess.Completion expected) throws Exception {
        Process child = launch(mode);
        try {
            try (NativeRenameProcess reader = NativeRenameProcess.start(child)) {
                assertEquals(IDENTITY, reader.identity("READY"));
                reader.go();
                assertEquals(expected, reader.renameResult());
            }
            assertStopped(child);
        } finally {
            child.destroyForcibly();
        }
    }

    private static void rejectInitialFrame(String mode) throws Exception {
        Process child = launch(mode);
        try {
            IOException error = assertThrows(IOException.class, () -> {
                try (NativeRenameProcess reader = NativeRenameProcess.start(child)) {
                    reader.identity("READY");
                }
            });
            assertEquals(PROTOCOL_ERROR, error.getMessage());
            assertStopped(child);
        } finally {
            child.destroyForcibly();
        }
    }

    private static void assertStopped(Process child) throws InterruptedException {
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Publisher fixture process was left alive");
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (readerAlive() && System.nanoTime() < limit) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertFalse(readerAlive(), "Publisher response reader was left alive");
    }

    private static boolean readerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("tuprel-native-publisher-response"));
    }

    private static void assertDeadline(long start) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(9)) >= 0, elapsed::toString);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(20)) < 0, elapsed::toString);
    }

    private static Process launch(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path classpath = Path.of(PublisherFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", classpath.toString(),
                PublisherFixture.class.getName(), mode).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        return builder.start();
    }

    public static final class PublisherFixture {
        private PublisherFixture() {}

        public static void main(String[] arguments) throws Exception {
            String mode = arguments[0];
            if (mode.equals("silent")) {
                new CountDownLatch(1).await();
                return;
            }
            if (mode.equals("flood")) {
                System.out.print("x".repeat(512 * 1024));
                return;
            }
            if (mode.equals("partial")) {
                System.out.print("READY private-incomplete-frame");
                return;
            }
            if (mode.equals("observe")) {
                System.out.print("PRESENT " + IDENTITY + "\nOBSERVED\n");
                System.exit(13);
            }
            Thread stderr = new Thread(() -> {
                if (mode.equals("noisy")) { System.err.print("x".repeat(512 * 1024)); }
            });
            stderr.start();
            System.out.print("READY " + IDENTITY + "\n");
            System.out.flush();
            if (!Arrays.equals(System.in.readAllBytes(), "GO\n".getBytes(StandardCharsets.US_ASCII))) {
                System.exit(2);
            }
            if (mode.equals("collision")) {
                System.out.print("COLLISION\n");
                System.exit(11);
            }
            System.out.print("MOVED\n");
            System.out.flush();
            if (mode.equals("noEof")) { new CountDownLatch(1).await(); }
            if (mode.equals("trailing")) { System.out.print("unexpected\n"); }
            stderr.join();
            if (mode.equals("wrongExit")) { System.exit(13); }
        }
    }
}
