import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

/** Holds the OS directory identity throughout a staging operation. */
final class StagingRoot implements AutoCloseable {
    private static final boolean LINUX = System.getProperty("os.name").equals("Linux");
    private final Path original;
    private final long handle;

    static void load(Path library) throws IOException {
        if (!library.isAbsolute() || !library.equals(library.toRealPath())
                || !Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected the native library from the trusted staging bootstrap");
        }
        try {
            System.load(library.toString());
        } catch (LinkageError e) {
            throw new IOException("Cannot load the staging root validator", e);
        }
    }

    StagingRoot(Path path) throws IOException {
        original = path;
        handle = open(path.toString());
        if (handle == 0) throw new IOException("Unsupported or unsafe staging directory");
    }

    Path anchor() {
        return LINUX ? Path.of("/proc/self/fd").resolve(Long.toString(handle - 1)) : original;
    }

    void verify() throws IOException {
        if (!matches(handle, original.toString())) {
            throw new IOException("Staging directory identity or permissions changed");
        }
    }

    Object identity(Path directory) throws IOException {
        verify();
        if (!directory.startsWith(anchor()) || directory.equals(anchor())) {
            throw new IOException("Expected a directory beneath the staging root");
        }
        if (!LINUX) {
            try (StagingRoot child = new StagingRoot(directory)) {
                String identity = windowsIdentity(child.handle);
                if (identity == null) throw new IOException("Cannot establish directory identity");
                return identity;
            }
        }
        Path candidate = original.resolve(anchor().relativize(directory));
        try (StagingRoot child = new StagingRoot(candidate)) {
            child.verify();
            if (!Files.isSameFile(child.anchor(), directory)) throw new IOException("Staging directory changed");
        }
        BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.fileKey() == null) throw new IOException("Cannot establish directory identity");
        return attrs.fileKey();
    }

    void write(Path file, byte[] bytes, boolean executable) throws IOException {
        verify();
        Path relative = anchor().relativize(file);
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Path escapes staging");
        try (StagingRoot parent = new StagingRoot(original.resolve(relative).getParent())) {
            parent.verify();
            if (LINUX && !Files.isSameFile(parent.anchor(), file.getParent())) {
                throw new IOException("Staging file parent changed");
            }
            if (!writeNew(parent.handle, file.getFileName().toString(), bytes, executable)) {
                throw new IOException("Cannot create authenticated staging file");
            }
        }
    }

    void verifyFile(Path file) throws IOException {
        verify();
        Path relative = anchor().relativize(file);
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Path escapes staging");
        Path parentPath = file.getParent();
        if (parentPath.equals(anchor())) {
            if (!trustedFile(handle, file.getFileName().toString())) throw new IOException("Unsafe staging file");
            return;
        }
        try (StagingRoot parent = new StagingRoot(original.resolve(relative).getParent())) {
            parent.verify();
            if ((LINUX && !Files.isSameFile(parent.anchor(), parentPath))
                    || !trustedFile(parent.handle, file.getFileName().toString())) {
                throw new IOException("Unsafe staging file");
            }
        }
    }

    void move(Path source, Path destination) throws IOException {
        verify();
        if (!anchor().equals(source.getParent()) || !anchor().equals(destination.getParent())
                || !rename(handle, source.getFileName().toString(), destination.getFileName().toString(), false)) {
            throw new IOException("Atomic staging rename refused or incomplete");
        }
    }

    void replaceFile(Path source, Path destination) throws IOException {
        verify();
        if (!anchor().equals(source.getParent()) || !anchor().equals(destination.getParent())
                || !rename(handle, source.getFileName().toString(), destination.getFileName().toString(), true)) {
            throw new IOException("Atomic activation refused or incomplete");
        }
        verify();
    }

    Path createDirectory(Path parent, String prefix, boolean temporary) throws IOException {
        verify();
        if (!parent.equals(anchor())) identity(parent);
        Path created;
        if (LINUX) {
            var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            created = temporary ? Files.createTempDirectory(parent, prefix, mode)
                    : Files.createDirectory(parent.resolve(prefix), mode);
        } else {
            created = temporary ? Files.createTempDirectory(parent, prefix) : Files.createDirectory(parent.resolve(prefix));
            Files.setOwner(created, Files.getOwner(original, LinkOption.NOFOLLOW_LINKS));
        }
        identity(created);
        return created;
    }

    @Override public void close() {
        release(handle);
    }

    private static native long open(String path);
    private static native boolean matches(long handle, String path);
    private static native void release(long handle);
    private static native String windowsIdentity(long handle);
    private static native boolean writeNew(long handle, String name, byte[] bytes, boolean executable);
    private static native boolean rename(long handle, String source, String destination, boolean replace);
    private static native boolean trustedFile(long handle, String name);
}
