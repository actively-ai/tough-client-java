package ai.actively.toughclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {

    static final String DEFAULT_SERVER_URL = "https://actively-ai--tough-server-fastapi-app.modal.run/completion";

    static final int LIMIT = 100; // characters
    static final int INTERVAL = 10; // seconds

    static final int PORT = 8000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/completion", Main::handleCompletion);
        // Handle each request on its own thread so concurrent clients don't block each other
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Server listening on http://localhost:" + port + "/completion");
    }

    static void handleCompletion(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<?, ?> requestJson;
            try {
                requestJson = MAPPER.readValue(body, Map.class);
            } catch (IOException e) {
                send(exchange, 400, "Invalid JSON body: " + e.getMessage(), "text/plain");
                return;
            }

            Object prompt = requestJson.get("prompt"); // noqa

            getOpenAICompletion(exchange, body);
        }
    }

    static void getOpenAICompletion(HttpExchange exchange, byte[] requestBody) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(DEFAULT_SERVER_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<byte[]> resp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            String contentType = resp.headers().firstValue("content-type").orElse(null);
            send(exchange, resp.statusCode(), resp.body(), contentType);
        } catch (IOException e) {
            send(exchange, 503, "Upstream request failed: " + e, "text/plain");
        } catch (Exception e) {
            send(exchange, 500, "Unexpected error: " + e, "text/plain");
        }
    }

    static void send(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        send(exchange, statusCode, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    static void send(HttpExchange exchange, int statusCode, byte[] body, String contentType) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(statusCode, body.length == 0 ? -1 : body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
