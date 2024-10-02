package com.taskumi;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Taskumi {

    private static final int PORT = 8080;
    private static final String TARGET_SERVER_URL = "http://example.com/target-api"; // Modify with actual target URL
    private static final int THREAD_POOL_SIZE = 10;
    private static final int QUERY_TIMEOUT_MS = 5000;

    // Thread pool to handle incoming requests concurrently
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(QUERY_TIMEOUT_MS))
            .executor(threadPool)
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        System.out.println("Taskumi proxy server is running on port " + PORT);

        // Handle incoming query requests
        server.createContext("/query", new QueryHandler());

        // Start server with the defined thread pool
        server.setExecutor(threadPool);
        server.start();
    }

    // Query handler for handling incoming HTTP requests
    static class QueryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Asynchronously process the request to avoid blocking the main thread
                CompletableFuture.runAsync(() -> processRequest(exchange), threadPool);
            } else {
                String response = "Only POST requests are supported!";
                exchange.sendResponseHeaders(405, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
        }

        // Method to process each request asynchronously
        private void processRequest(HttpExchange exchange) {
            try {
                // Read incoming request
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("Received query: " + requestBody);

                // Send the query to the target server asynchronously
                CompletableFuture<String> responseFuture = forwardQueryAsync(requestBody);

                // Handle response asynchronously
                responseFuture.thenAccept(response -> {
                    try {
                        // Send response back to client
                        exchange.sendResponseHeaders(200, response.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).exceptionally(ex -> {
                    // Handle any error during processing
                    try {
                        String errorResponse = "Error processing query: " + ex.getMessage();
                        exchange.sendResponseHeaders(500, errorResponse.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return null;
                });

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Method to forward the query to the target server asynchronously
        private CompletableFuture<String> forwardQueryAsync(String query) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TARGET_SERVER_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofMillis(QUERY_TIMEOUT_MS)) // Timeout for each query
                    .build();

            // Send the request asynchronously using the HttpClient and return a CompletableFuture
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .exceptionally(ex -> {
                        // Handle query failure, possibly retrying or logging
                        System.err.println("Error forwarding query to target: " + ex.getMessage());
                        return "Error occurred while forwarding the query: " + ex.getMessage();
                    });
        }
    }
}

