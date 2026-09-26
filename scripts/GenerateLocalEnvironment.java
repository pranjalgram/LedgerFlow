import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/** Writes local-only random database credentials; existing configuration is never replaced. */
class GenerateLocalEnvironment {
    public static void main(String[] args) throws Exception {
        String owner = secret(); String runtime = secret();
        String config = Files.readString(Path.of(".env.example"))
                .replace("replace-with-a-local-password", owner)
                .replace("replace-with-a-different-runtime-password", runtime)
                .replace("replace-with-the-runtime-password", runtime)
                .replace("replace-with-the-migration-password", owner);
        Files.writeString(Path.of(".env"), config, StandardOpenOption.CREATE_NEW);
        System.out.println("Created ignored .env with random local database credentials; restrict access to your user.");
    }
    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
