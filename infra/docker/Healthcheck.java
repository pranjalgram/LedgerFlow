import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

class Healthcheck {
    public static void main(String[] args) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:8080/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
        }
    }
}
