package dev.tuprel.migrate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded protocol for an already authenticated executable. */
final class NativeRenameProcess implements AutoCloseable {
    enum Completion { MOVED, COLLISION, UNKNOWN }
    private static final Pattern ID = Pattern.compile(
            "(ext4|xfs|ntfs) [a-f0-9]{1,16}( [a-f0-9]{1,32}){3}");
    private static final int TIMEOUT_SECONDS = 10;
    private final Process process;
    private final String initial;

    private NativeRenameProcess(Process process) throws IOException, InterruptedException {
        this.process = process;
        this.initial = within(() -> line(process.getInputStream()));
    }

    static NativeRenameProcess start(Path executable, Path parent, String stage, String destination)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "2", parent.toString(), stage, destination)
                .directory(executable.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        Process process = builder.start();
        try { return new NativeRenameProcess(process); }
        catch (IOException | InterruptedException failure) { process.destroyForcibly(); throw failure; }
    }

    String identity(String frame) throws IOException {
        String prefix = frame + " ";
        if (!initial.startsWith(prefix)) { throw protocol(); }
        String identity = initial.substring(prefix.length());
        Matcher parsed = ID.matcher(identity);
        if (!parsed.matches()) { throw protocol(); }
        int fileIdLength = parsed.group(1).equals("ntfs") ? 32 : 16;
        String[] parts = identity.split(" ", -1);
        for (int i = 2; i < parts.length; i++) {
            if (fileIdLength == 32 ? parts[i].length() != 32 : parts[i].length() > fileIdLength) { throw protocol(); }
        }
        return identity;
    }

    void go() throws IOException {
        try (java.io.OutputStream output = process.getOutputStream()) {
            output.write(new byte[]{'G', 'O', '\n'});
        }
    }

    boolean complete(int status, String expected) throws IOException, InterruptedException {
        return within(() -> expected.equals(line(process.getInputStream())) && process.getInputStream().read() == -1
                && process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == status);
    }

    Completion renameResult() throws IOException, InterruptedException {
        return within(() -> {
            String result = line(process.getInputStream());
            if (process.getInputStream().read() != -1 || !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Completion.UNKNOWN;
            }
            if (process.exitValue() == 0 && result.equals("MOVED")) { return Completion.MOVED; }
            if (process.exitValue() == 11 && result.equals("COLLISION")) { return Completion.COLLISION; }
            return Completion.UNKNOWN;
        });
    }

    static boolean validIdentity(String value) { return ID.matcher(value).matches(); }

    @Override public void close() {
        try { process.getOutputStream().close(); } catch (IOException ignored) { /* Final result is determined separately. */ }
        if (process.isAlive()) { process.destroyForcibly(); }
        try { process.getInputStream().close(); } catch (IOException ignored) { /* No raw process errors escape. */ }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        for (int i = 0; i < 160; i++) {
            int next = input.read();
            if (next == '\n') { return buffer.toString(StandardCharsets.US_ASCII); }
            if (next < 32 || next > 126) { throw protocol(); }
            buffer.write(next);
        }
        throw protocol();
    }

    private static <T> T within(Callable<T> operation) throws IOException, InterruptedException {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread.startVirtualThread(task);
        try { return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (ExecutionException | TimeoutException failure) { throw protocol(); }
        finally { task.cancel(true); }
    }

    private static IOException protocol() { return new IOException("Unverified native publisher response"); }
}
