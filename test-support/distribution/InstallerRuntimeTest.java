import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pure eligibility checks; the OS fixture separately executes real signed CLI packages. */
public final class InstallerRuntimeTest {
    private InstallerRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        SignedCliBundle.checkLibraryRuntime(jar(61, 0, false, 0, 0), 17);
        for (int feature : new int[]{17, 21, 25}) {
            SignedCliBundle.checkLibraryRuntime(jar(61, 0, true, 21, 65), feature);
            SignedCliBundle.checkLibraryRuntime(jar(61, 0, false, 17, 65), feature);
            reject(jar(61, 65535, false, 0, 0), feature);
            reject(new byte[]{1, 2, 3, 4}, feature);
            reject(new byte[]{'P', 'K', 3, 4}, feature);
        }
        reject(jar(65, 0, false, 0, 0), 17);
        reject(jar(69, 0, false, 0, 0), 21);
        SignedCliBundle.checkLibraryRuntime(jar(65, 0, false, 0, 0), 21);
        SignedCliBundle.checkLibraryRuntime(jar(69, 0, false, 0, 0), 25);
        reject(jar(61, 0, true, 17, 65), 17);
        reject(jar(61, 0, true, 21, 69), 21);
        reject(jar(61, 1, false, 0, 0), 17);
        System.out.println("Installer runtime eligibility passed on Java " + Runtime.version().feature());
    }

    private static void reject(byte[] jar, int feature) throws Exception {
        try {
            SignedCliBundle.checkLibraryRuntime(jar, feature);
            throw new AssertionError("Accepted incompatible library bytes");
        } catch (IOException expected) {
            // No class loading or filesystem mutation is involved.
        }
    }

    private static byte[] jar(int major, int minor, boolean multiRelease, int variant, int variantMajor) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(("Manifest-Version: 1.0\r\nMulti-Release: " + multiRelease + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fixture/Model.class"));
            zip.write(header(major, minor));
            zip.closeEntry();
            if (variant != 0) {
                zip.putNextEntry(new ZipEntry("META-INF/versions/" + variant + "/fixture/Model.class"));
                zip.write(header(variantMajor, 0));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] header(int major, int minor) {
        return new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                (byte) (minor >>> 8), (byte) minor, (byte) (major >>> 8), (byte) major};
    }
}
