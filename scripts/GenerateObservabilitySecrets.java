import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

class GenerateObservabilitySecrets {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(".local-secrets"); Files.createDirectories(directory);
        for (String name : new String[]{"metrics-password", "grafana-password"}) {
            Path path = directory.resolve(name);
            if (!Files.exists(path)) {
                byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
                Files.writeString(path, Base64.getUrlEncoder().withoutPadding().encodeToString(secret), StandardOpenOption.CREATE_NEW);
            }
        }
        System.out.println("Local observability secrets are available in .local-secrets; existing files were preserved.");
    }
}
