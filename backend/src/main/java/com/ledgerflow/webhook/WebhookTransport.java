package com.ledgerflow.webhook;

import com.ledgerflow.shared.DomainException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deliberately small POST transport: pinned DNS, no redirects, bounded headers, no response bodies. */
@Component
public class WebhookTransport implements AutoCloseable {
    private final Set<String> origins;
    private final boolean local;
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("webhook-dns-", 0).factory());
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("webhook-deadline").factory());
    public WebhookTransport(@Value("${ledgerflow.webhooks.allowed-origins:}") String origins,
            @Value("${ledgerflow.webhooks.allow-local:false}") boolean local) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.local = local;
    }
    public URI validate(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { throw rejected(); }
        if (value.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || value.chars().anyMatch(c -> c < 33 || c > 126)
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                || !origins.contains(origin(uri))) throw rejected();
        return uri;
    }
    private static String origin(URI uri) { return uri.getScheme() + "://" + uri.getHost() + ":" + port(uri); }
    private static int port(URI uri) { return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
    public record Result(Integer status, String error, long durationMs) {
        public boolean succeeded() { return status != null && status >= 200 && status < 300; }
    }
    public Result post(String url, UUID event, String payload, String secret) {
        long start = System.nanoTime();
        try {
            URI uri = validate(url);
            var resolution = dns.submit(() -> InetAddress.getAllByName(uri.getHost()));
            InetAddress[] addresses;
            try { addresses = resolution.get(2, TimeUnit.SECONDS); }
            finally { resolution.cancel(true); }
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !permitted(address)))
                return result(null, "destination-blocked", start);
            try (var socket = new Socket()) {
                var timeout = deadlines.schedule(() -> abort(socket), 5, TimeUnit.SECONDS);
                try {
                    socket.connect(new InetSocketAddress(addresses[0], port(uri)), 2000);
                    socket.setSoTimeout(3000);
                    if ("https".equals(uri.getScheme())) {
                        try (var tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, uri.getHost(), port(uri), true)) {
                            var parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                            tls.setSSLParameters(parameters); tls.setSoTimeout(3000); tls.startHandshake();
                            return result(exchange(tls, uri, event, payload, secret), null, start);
                        }
                    }
                    return result(exchange(socket, uri, event, payload, secret), null, start);
                } finally { timeout.cancel(false); }
            }
        } catch (DomainException blocked) { return result(null, "destination-blocked", start); }
        catch (IOException | ExecutionException | TimeoutException | RejectedExecutionException unavailable) { return result(null, "transport-unavailable", start); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return result(null, "interrupted", start); }
    }
    private int exchange(Socket socket, URI uri, UUID event, String payload, String secret) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        if (body.length > 65536) throw new IOException("Payload too large");
        long timestamp = Instant.now().getEpochSecond();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String headers = "POST " + path + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                + "\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " + body.length
                + "\r\nUser-Agent: LedgerFlow-Webhook/1\r\nX-LedgerFlow-Event-Id: " + event
                + "\r\nX-LedgerFlow-Timestamp: " + timestamp + "\r\nX-LedgerFlow-Signature: v1="
                + WebhookSecrets.signature(secret, timestamp, payload) + "\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body); socket.getOutputStream().flush();
        var response = new StringBuilder();
        while (response.length() < 8192) {
            int next = socket.getInputStream().read();
            if (next < 0) throw new IOException("Incomplete response");
            response.append((char) next);
            if (response.toString().endsWith("\r\n\r\n")) {
                String status = response.substring(0, response.indexOf("\r\n"));
                if (!status.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) throw new IOException("Invalid response");
                return Integer.parseInt(status.substring(9, 12));
            }
        }
        throw new IOException("Response headers too large");
    }
    private boolean permitted(InetAddress address) {
        if (local && address.isLoopbackAddress()) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]); int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) return first != 0 && first != 10 && first != 127 && first < 224
                && !(first == 100 && second >= 64 && second <= 127) && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31) && !(first == 192 && (second == 0 || second == 168))
                && !(first == 198 && (second == 18 || second == 19 || second == 51)) && !(first == 203 && second == 0);
        // Only global unicast IPv6; exclude documentation and transition ranges.
        return first >= 0x20 && first <= 0x3f && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && (Byte.toUnsignedInt(bytes[2]) < 2 || Byte.toUnsignedInt(bytes[2]) == 0x0d));
    }
    private static Result result(Integer status, String error, long start) { return new Result(status, error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); }
    private static void abort(Socket socket) {
        try { socket.close(); } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(WebhookTransport.class).debug("Webhook deadline socket close failed");
        }
    }
    private static DomainException rejected() { return new DomainException(400, "webhook-destination-denied", "The webhook destination is not approved by the operator."); }
    @PreDestroy
    public void close() { dns.shutdownNow(); deadlines.shutdownNow(); }
}
