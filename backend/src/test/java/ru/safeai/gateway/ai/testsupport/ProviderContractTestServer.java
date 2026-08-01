package ru.safeai.gateway.ai.testsupport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProviderContractTestServer implements AutoCloseable {

    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor;
    private final Queue<StubResponse> responses =
            new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> requests =
            new CopyOnWriteArrayList<>();

    public ProviderContractTestServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueue(int status, String body) {
        enqueue(status, Map.of(), body);
    }

    public void enqueue(
            int status,
            Map<String, String> headers,
            String body
    ) {
        responses.add(new StubResponse(
                status,
                Map.copyOf(headers),
                body == null ? "" : body
        ));
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public RecordedRequest singleRequest() {
        if (requests.size() != 1) {
            throw new AssertionError(
                    "Expected exactly one request but got "
                            + requests.size()
            );
        }
        return requests.getFirst();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                immutableHeaders(exchange.getRequestHeaders()),
                new String(requestBody, StandardCharsets.UTF_8)
        ));

        StubResponse response = responses.poll();
        if (response == null) {
            response = new StubResponse(
                    500,
                    Map.of(),
                    "{\"error\":{\"type\":\"test_server_empty\"}}"
            );
        }

        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );
        response.headers().forEach(responseHeaders::set);

        byte[] responseBody = response.body()
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(
                response.status(),
                responseBody.length
        );
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    private Map<String, List<String>> immutableHeaders(
            Headers headers
    ) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> result.put(
                name,
                List.copyOf(values)
        ));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record StubResponse(
            int status,
            Map<String, String> headers,
            String body
    ) {
    }

    public record RecordedRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            String body
    ) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> entry
                    : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)
                        && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        public JsonNode jsonBody() {
            try {
                return JSON_MAPPER.readTree(body);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Recorded body is not valid JSON: " + body,
                        exception
                );
            }
        }
    }
}
