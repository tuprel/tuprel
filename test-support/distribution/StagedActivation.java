import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staging entry point for activation and version-independent launching. */
public final class StagedActivation {
    private static final String POINTER = ".tuprel-active";
    private static final String HEADER = "TUPREL-STAGING-ACTIVE 1";
    private static final String POLICY = "TUPREL-STAGING-AUTHORIZATION 1";

    private StagedActivation() {}

    public static void main(String[] args) {
        try {
            System.exit(execute(args));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            System.err.println("Tuprel staging activation or launch refused. Check the active version and signed authorization.");
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    private static int execute(String[] args) throws IOException, GeneralSecurityException, InterruptedException {
        if (args.length < 5 || !digest(args[3])) throw new IOException("Missing trusted staging configuration");
        String operation = args[4];
        boolean launch = operation.equals("launch");
        boolean rollback = operation.equals("rollback");
        if (!launch && (!(rollback || operation.equals("activate")) || args.length != 6)) {
            throw new IOException("Expected launch, activate or rollback");
        }
        StagingRoot.load(Path.of(args[1]));
        Path catalogue = Path.of(args[2]);
        try (StagingRoot root = new StagingRoot(Path.of(args[0]));
                StagingRoot metadata = new StagingRoot(catalogue)) {
            root.verify();
            metadata.verify();
            try (FileChannel channel = SignedCliBundle.openLock(root.anchor()); FileLock lock = channel.lock()) {
                if (!lock.isValid()) throw new IOException("Staging lock is invalid");
                root.verify();
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(VerifyCliBundle.read(metadata.anchor().resolve("public-key.bin"), 128)));
                if (!SignedCliBundle.sha256(key.getEncoded()).equals(args[3])) throw new IOException("Untrusted key");
                Map<String, Authorization> policy = readPolicy(metadata.anchor(), key);
                State previous = readState(root.anchor());
                if (launch && previous == null) throw new IOException("No active version");
                String version = launch ? previous.version() : args[5];
                Authorization allowed = policy.get(version);
                if (allowed == null || allowed.mode().equals("revoked")) throw new IOException("Version is unauthorized");
                if (!launch) validateTransition(previous, version, allowed, rollback);
                SignedCliBundle bundle = SignedCliBundle.verify(metadata.anchor().resolve(version + ".zip"),
                        VerifyCliBundle.read(metadata.anchor().resolve(version + ".manifest"), 512),
                        VerifyCliBundle.read(metadata.anchor().resolve(version + ".ed25519"), 64),
                        key, args[3], version);
                if (!bundle.digest().equals(allowed.digest()) || (launch && !bundle.digest().equals(previous.digest()))) {
                    throw new IOException("Version identity differs from the authorization");
                }
                Path installed = root.anchor().resolve(version);
                Object identity = root.identity(installed);
                List<String> libraries = bundle.checkedLibraries(root, installed);
                if (!identity.equals(root.identity(installed))) throw new IOException("Installed version identity changed");
                metadata.verify();
                if (launch) return launch(Path.of(args[0]).resolve(version), libraries, args);
                String highWater = previous == null || compare(version, previous.highWater()) > 0
                        ? version : previous.highWater();
                State next = new State(version, bundle.digest(), highWater);
                if (!next.equals(previous)) {
                    Path candidate = root.anchor().resolve(".tuprel-activation-" + UUID.randomUUID());
                    root.write(candidate, next.bytes(), false);
                    root.replaceFile(candidate, root.anchor().resolve(POINTER));
                    if (!next.equals(readState(root.anchor()))) throw new IOException("Activation result is uncertain");
                }
                return 0;
            }
        }
    }

    private static int launch(Path installed, List<String> libraries, String[] args)
            throws IOException, InterruptedException {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", javaName);
        if (installed.toString().contains(java.io.File.pathSeparator)) {
            throw new IOException("Installation path contains the classpath separator");
        }
        String classpath = String.join(java.io.File.pathSeparator,
                libraries.stream().map(installed::resolve).map(Path::toString).toList());
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-cp", classpath, "dev.tuprel.cli.TuprelCli"));
        command.addAll(Arrays.asList(args).subList(5, args.length));
        ProcessBuilder process = new ProcessBuilder(command).inheritIO();
        process.environment().remove("JAVA_TOOL_OPTIONS");
        process.environment().remove("JDK_JAVA_OPTIONS");
        process.environment().remove("_JAVA_OPTIONS");
        return process.start().waitFor();
    }

    private static void validateTransition(State previous, String version, Authorization allowed, boolean rollback)
            throws IOException {
        if (rollback) {
            if (previous == null || compare(version, previous.version()) >= 0 || !allowed.mode().equals("rollback")) {
                throw new IOException("Rollback is not authorized");
            }
        } else if (previous != null && !version.equals(previous.version()) && compare(version, previous.highWater()) < 0) {
            throw new IOException("Activation would downgrade the selected release");
        }
    }

    private static int compare(String left, String right) {
        String[] a = left.substring(0, left.length() - 8).split("\\.");
        String[] b = right.substring(0, right.length() - 8).split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static Map<String, Authorization> readPolicy(Path catalogue, PublicKey key)
            throws IOException, GeneralSecurityException {
        byte[] policy = VerifyCliBundle.read(catalogue.resolve("authorization"), 8192);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(policy);
        if (!verifier.verify(VerifyCliBundle.read(catalogue.resolve("authorization.ed25519"), 64))) {
            throw new IOException("Invalid staging authorization signature");
        }
        String[] lines = new String(policy, StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length < 3 || !lines[0].equals(POLICY) || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("Invalid staging authorization");
        }
        Map<String, Authorization> result = new HashMap<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String[] fields = lines[i].split(" ", -1);
            if (fields.length != 3 || !version(fields[0]) || !digest(fields[1])
                    || !List.of("allow", "rollback", "revoked").contains(fields[2])
                    || result.putIfAbsent(fields[0], new Authorization(fields[1], fields[2])) != null) {
                throw new IOException("Invalid staging authorization entry");
            }
        }
        return result;
    }

    static State readState(Path root) throws IOException {
        Path pointer = root.resolve(POINTER);
        if (!SignedCliBundle.present(pointer)) return null;
        String[] fields = new String(VerifyCliBundle.read(pointer, 256), StandardCharsets.US_ASCII).split("\n", -1);
        if (fields.length != 5 || !fields[0].equals(HEADER) || !version(fields[1]) || !digest(fields[2])
                || !version(fields[3]) || compare(fields[3], fields[1]) < 0 || !fields[4].isEmpty()) {
            throw new IOException("Invalid active version pointer");
        }
        return new State(fields[1], fields[2], fields[3]);
    }

    private static boolean version(String text) {
        return SignedCliBundle.VERSION.matcher(text).matches();
    }

    private static boolean digest(String text) {
        return text.matches("[0-9a-f]{64}");
    }

    record State(String version, String digest, String highWater) {
        byte[] bytes() {
            return (HEADER + "\n" + version + "\n" + digest + "\n" + highWater + "\n").getBytes(StandardCharsets.US_ASCII);
        }
    }

    private record Authorization(String digest, String mode) {}
}
