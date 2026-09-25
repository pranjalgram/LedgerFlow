import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

/** Creates a local encryption key without printing it or overwriting an existing key. */
class GenerateWebhookKey {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(".local-secrets");
        Files.createDirectories(directory);
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        Files.writeString(directory.resolve("webhook-key"), Base64.getEncoder().encodeToString(key), StandardOpenOption.CREATE_NEW);
        System.out.println("Created .local-secrets/webhook-key; StartBackend.ps1 loads it. Back it up securely.");
    }
}
