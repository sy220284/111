import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Read the verified signing identity, independent of apksigner CLI display labels. */
class VerifyApkSigning {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: VerifyApkSigning APK");
        }
        var result = new ApkVerifier.Builder(new File(args[0]))
                .setMinCheckedPlatformVersion(29).build().verify();
        if (!result.isVerified()) {
            throw new IllegalStateException("APK signature verification failed: " + result.getAllErrors());
        }
        var signers = result.getSignerCertificates();
        if (signers.size() != 1) {
            throw new IllegalStateException("Expected exactly one verified APK signing identity, got " + signers.size());
        }
        var lineage = result.getSigningCertificateLineage();
        if (lineage != null && lineage.size() > 1) {
            throw new IllegalStateException("Unexpected signing key rotation; release requires a continuous single identity");
        }
        var digest = MessageDigest.getInstance("SHA-256").digest(signers.get(0).getEncoded());
        System.out.println(HexFormat.of().formatHex(digest));
    }
}
