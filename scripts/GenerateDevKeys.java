import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Run from repository root: java scripts/GenerateDevKeys.java. Never replaces existing keys. */
class GenerateDevKeys {
    public static void main(String[] args) throws Exception {
        var directory = Path.of(".local-secrets");
        var privatePath = directory.resolve("jwt-private.pem");
        var publicPath = directory.resolve("jwt-public.pem");
        if (Files.exists(privatePath) || Files.exists(publicPath)) {
            throw new IllegalStateException("Keys already exist; refusing to rotate them implicitly.");
        }
        Files.createDirectories(directory);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        Files.writeString(privatePath, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicPath, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        System.out.println("Created ignored development keys in .local-secrets. Restrict directory access to your user.");
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
