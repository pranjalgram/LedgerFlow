import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local development receiver. Supply WEBHOOK_SECRET in the environment; never pass secrets in command arguments. */
class DemoWebhookReceiver {
    public static void main(String[] args) throws Exception {
        String secret = System.getenv("WEBHOOK_SECRET");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("WEBHOOK_SECRET is required");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 9099), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            int status = 401;
            try {
                String timestamp = exchange.getRequestHeaders().getFirst("X-LedgerFlow-Timestamp");
                String signature = exchange.getRequestHeaders().getFirst("X-LedgerFlow-Signature");
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
                boolean fresh = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) <= 300;
                if (body.length <= 65536 && fresh && signature != null && signature.startsWith("v1=")
                        && MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(3)))) status = 204;
            } catch (IllegalArgumentException | java.security.GeneralSecurityException invalid) { status = 401; }
            exchange.sendResponseHeaders(status, -1); exchange.close();
            if (status == 204) System.out.println("Accepted signed event; traceparent=" + exchange.getRequestHeaders().getFirst("traceparent"));
        });
        server.start();
        System.out.println("Local signature-verifying receiver listening on 127.0.0.1:9099/hook");
    }
}
