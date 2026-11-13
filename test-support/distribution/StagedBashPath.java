import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;

/** Persistent PATH integration for a checked Bash startup file. */
public final class StagedBashPath {
    private static final String BEGIN = "# >>> Tuprel staging PATH >>>\n";
    private static final String END = "# <<< Tuprel staging PATH <<<\n";

    private StagedBashPath() {}

    public static void main(String[] args) {
        try {
            edit(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Tuprel Bash PATH change refused. Check the startup file and launcher directory.");
            System.exit(1);
        }
    }

    private static void edit(String[] args) throws IOException {
        if (!System.getProperty("os.name").equals("Linux") || args.length != 5
                || !(args[0].equals("add") || args[0].equals("remove"))
                || !(args[1].equals("interactive") || args[1].equals("login"))) {
            throw new IOException("Expected add|remove interactive|login <home> <launcher-directory> <native-library>");
        }
        Path bin = Path.of(args[3]);
        if (bin.toString().chars().anyMatch(c -> c == ':' || c < 32 || c == 127)) {
            throw new IOException("Invalid PATH entry");
        }
        StagingRoot.load(Path.of(args[4]));
        try (StagingRoot home = new StagingRoot(Path.of(args[2]));
                StagingRoot launcher = new StagingRoot(bin);
                FileChannel channel = SignedCliBundle.openLock(home.anchor());
                FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("PATH lock is invalid");
            launcher.verifyFile(launcher.anchor().resolve("tuprel"));
            Path profile = profile(home, args[1]);
            String prior = read(home, profile);
            String directory = "'" + bin.toString().replace("'", "'\\''") + "'";
            String block = BEGIN + "if ! command -v tuprel >/dev/null 2>&1; then\n"
                    + "  case :${PATH-}: in\n"
                    + "    *:" + directory + ":*) ;;\n"
                    + "    *) export PATH=${PATH:+$PATH:}" + directory + " ;;\n"
                    + "  esac\nfi\n" + END;
            boolean add = args[0].equals("add");
            String next = editBlock(prior, block, add);
            if (prior.equals(next)) return;
            if (add) verifySupportedProfile(prior);
            Path pending = home.anchor().resolve(".tuprel-bash-path-" + UUID.randomUUID());
            home.write(pending, next.getBytes(StandardCharsets.UTF_8), false);
            if (!profile.equals(profile(home, args[1])) || !prior.equals(read(home, profile))) {
                throw new IOException("Bash startup file changed during the operation");
            }
            home.replaceFile(pending, profile);
            if (!next.equals(read(home, profile))) throw new IOException("Bash PATH result is uncertain");
        }
    }

    private static void verifySupportedProfile(String contents) throws IOException {
        // This profile permits comments and blank lines. Arbitrary Bash can return early,
        // redefine command lookup, or interpret an appended block as data.
        if (contents.lines().anyMatch(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
                || contents.lines().anyMatch(line -> line.endsWith("\\"))) {
            throw new IOException("Existing Bash startup code requires manual integration");
        }
    }

    private static Path profile(StagingRoot home, String kind) throws IOException {
        if (kind.equals("interactive")) return home.anchor().resolve(".bashrc");
        for (String name : new String[]{".bash_profile", ".bash_login", ".profile"}) {
            Path candidate = home.anchor().resolve(name);
            if (SignedCliBundle.present(candidate)) return candidate;
        }
        throw new IOException("No existing login startup file");
    }

    private static String read(StagingRoot home, Path profile) throws IOException {
        home.verifyFile(profile);
        if (!Files.getPosixFilePermissions(profile).equals(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("Expected a private Bash startup file with mode 0600");
        }
        byte[] bytes = VerifyCliBundle.read(profile, 1024 * 1024);
        String contents = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (!contents.endsWith("\n") || contents.indexOf('\r') >= 0 || contents.indexOf('\0') >= 0) {
            throw new IOException("Unsupported Bash startup file encoding");
        }
        return contents;
    }

    private static String editBlock(String prior, String block, boolean add) throws IOException {
        int start = prior.indexOf(BEGIN.strip());
        int end = prior.indexOf(END.strip());
        if (start < 0 && end < 0) return add ? prior + block : prior;
        if (start < 0 || (start > 0 && prior.charAt(start - 1) != '\n') || !prior.startsWith(block, start)
                || end != start + block.indexOf(END) || prior.indexOf(BEGIN.strip(), start + 1) >= 0
                || prior.indexOf(END.strip(), end + 1) >= 0) {
            throw new IOException("Bash PATH markers are ambiguous or have been modified");
        }
        return add ? prior : prior.substring(0, start) + prior.substring(start + block.length());
    }
}
