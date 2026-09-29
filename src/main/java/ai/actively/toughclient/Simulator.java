package ai.actively.toughclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Simulator {

    // Default configuration
    static final String DEFAULT_SERVER_URL = "http://localhost:8000/completion";
    static final int DEFAULT_DURATION = 60;

    // ANSI colors for terminal output
    static final String GREEN = "\033[92m";
    static final String YELLOW = "\033[93m";
    static final String RED = "\033[91m";
    static final String BLUE = "\033[94m";
    static final String CYAN = "\033[96m";
    static final String MAGENTA = "\033[95m";
    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final DateTimeFormatter LOG_KEY_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");

    /**
     * A client that sends requests to a specified URL at a specified interval.
     * Logs are maintained in an insertion-ordered map (timestamp → message).
     */
    static class Client {
        final String clientName;
        final int clientId;
        final int nChars;
        final double interval;
        final String userId;
        final String url;

        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();

        // Statistics
        int nSuccess = 0;
        int nFailure = 0;

        // Logs: key is timestamp string, value is colorized message
        final Map<String, String> logs = new LinkedHashMap<>();

        Client(int clientId, int nChars, double interval, String userId, String url) {
            this.clientName = "CLIENT " + clientId;
            this.clientId = clientId;
            this.nChars = nChars;
            this.interval = interval;
            this.userId = userId;
            this.url = url;
        }

        void run(long endTimeMillis) {
            while (System.currentTimeMillis() < endTimeMillis) {
                runOnce();
                try {
                    Thread.sleep((long) (interval * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void runOnce() {
            // Generate the prompt
            String prompt = "A".repeat(nChars);
            Map<String, Object> requestData = Map.of("prompt", prompt, "user_id", userId);

            // First log entry: "Sending ..."
            String logKey = LocalTime.now().format(LOG_KEY_FORMAT);
            upsertLog("» Sending " + nChars + "c...", BLUE, logKey);

            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestData)))
                        .build();

                long startTime = System.nanoTime();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode responseJson = MAPPER.readTree(response.body());
                double latency = (System.nanoTime() - startTime) / 1e9;

                if (response.statusCode() == 200) {
                    JsonNode respChars = responseJson.get("n_chars");
                    if (respChars != null && respChars.isInt() && respChars.asInt() == nChars) {
                        synchronized (this) { nSuccess++; }
                        upsertLog(String.format("✓ [200] Sent %dc (%.2fs)", nChars, latency), GREEN, logKey);
                    } else {
                        synchronized (this) { nFailure++; }
                        upsertLog("x [200] Incorrect response", RED, logKey);
                    }
                } else if (response.statusCode() == 429) {
                    synchronized (this) { nFailure++; }
                    upsertLog("x [429] Rate limited", RED, logKey);
                } else {
                    synchronized (this) { nFailure++; }
                    upsertLog("x [" + response.statusCode() + "] Error", RED, logKey);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                synchronized (this) { nFailure++; }
                upsertLog("x Error: " + e.getMessage(), RED, logKey);
            }
        }

        synchronized void upsertLog(String message, String color, String logKey) {
            logs.put(logKey, color + message + RESET);
        }

        synchronized List<Map.Entry<String, String>> snapshotLogs() {
            return new ArrayList<>(logs.entrySet());
        }

        synchronized double getSuccessRate() {
            int total = nSuccess + nFailure;
            return total > 0 ? (double) nSuccess / total * 100 : 0;
        }

        /** Returns formatted statistics lines for display. */
        synchronized List<String> getStatsOutput() {
            List<String> lines = new ArrayList<>();
            int totalRequests = nSuccess + nFailure;

            // Header
            lines.add(BOLD + header() + RESET);

            // Stats
            lines.add(GREEN + "✓ Successful requests: " + nSuccess + RESET);
            lines.add(RED + "✗ Failed requests: " + nFailure + RESET);

            if (totalRequests > 0) {
                double successRate = getSuccessRate();
                String color;
                if (successRate > 80) {
                    color = GREEN;
                } else if (successRate > 50) {
                    color = YELLOW;
                } else {
                    color = RED;
                }
                lines.add(String.format("%sSuccess rate: %.1f%%%s", color, successRate, RESET));
            }

            return lines;
        }

        String header() {
            return clientName + " (" + nChars + "c/" + interval + "s)";
        }
    }

    /** Responsible for printing the logs of one or more clients side by side. */
    static class Display {
        private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");

        static void clear() {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        }

        static int getTermWidth() {
            String columns = System.getenv("COLUMNS");
            if (columns != null) {
                try {
                    return Integer.parseInt(columns.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                Process p = new ProcessBuilder("sh", "-c", "tput cols 2> /dev/tty")
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                return Integer.parseInt(out);
            } catch (Exception e) {
                return 80;
            }
        }

        /** Left-justify text with ANSI color codes, accounting for the invisible ANSI characters. */
        static String ansiLjust(String text, int width) {
            // Remove ANSI sequences to get the visible length
            String visibleText = ANSI_ESCAPE.matcher(text).replaceAll("");
            int visibleLength = visibleText.codePointCount(0, visibleText.length());

            // Calculate padding needed
            int padding = Math.max(0, width - visibleLength);

            // Add padding to the end of the original text (with ANSI codes)
            return text + " ".repeat(padding);
        }

        static void displayLogs(Client client1, Client client2, int termWidth) {
            int paneWidth = Math.max(20, (termWidth - 3) / 2);
            String separator = "|";

            StringBuilder out = new StringBuilder();

            String header1 = ansiLjust(BOLD + client1.header() + RESET, paneWidth);
            String header2 = ansiLjust(BOLD + client2.header() + RESET, paneWidth);
            out.append(header1).append(' ').append(separator).append(' ').append(header2).append('\n');
            out.append("-".repeat(termWidth)).append('\n');

            List<Map.Entry<String, String>> client1Logs = client1.snapshotLogs();
            List<Map.Entry<String, String>> client2Logs = client2.snapshotLogs();

            // Pair them up so we can display in two columns
            int rows = Math.max(client1Logs.size(), client2Logs.size());
            for (int i = 0; i < rows; i++) {
                String text1 = "";
                if (i < client1Logs.size()) {
                    Map.Entry<String, String> log1 = client1Logs.get(i);
                    text1 = log1.getKey() + " | " + log1.getValue();
                }

                String text2 = "";
                if (i < client2Logs.size()) {
                    Map.Entry<String, String> log2 = client2Logs.get(i);
                    text2 = log2.getKey() + " | " + log2.getValue();
                }

                // Print them side by side
                out.append(ansiLjust(text1, paneWidth)).append(' ').append(separator).append(' ')
                        .append(ansiLjust(text2, paneWidth)).append('\n');
            }

            clear();
            System.out.print(out);
            System.out.flush();
        }

        /** Print final results after the simulation completes. */
        static void displayResults(List<Client> clients, int duration, String userId, String serverUrl) {
            String rule = "=".repeat(80);
            System.out.println("\n\n");
            System.out.println(MAGENTA + rule + RESET);
            System.out.println(BOLD + MAGENTA + "SIMULATION RESULTS" + RESET);
            System.out.println(MAGENTA + rule + RESET);

            System.out.println("\n" + CYAN + "User ID: " + userId + RESET);
            System.out.println(CYAN + "Server URL: " + serverUrl + RESET);
            System.out.println(CYAN + "Duration: " + duration + " seconds" + RESET + "\n");

            for (Client client : clients) {
                System.out.println(String.join("\n", client.getStatsOutput()));
                System.out.println();
            }

            System.out.println(MAGENTA + rule + RESET);
        }

        static void run(Client client1, Client client2, long endTimeMillis) {
            int termWidth = getTermWidth();
            while (System.currentTimeMillis() < endTimeMillis) {
                displayLogs(client1, client2, termWidth);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    final String userId;
    final String url;
    final int duration;
    final Client client1;
    final Client client2;

    Simulator(String userId, String url, int duration) {
        this.userId = userId;
        this.url = url;
        this.duration = duration;

        this.client1 = new Client(1, 16, 1.0, userId, url);
        this.client2 = new Client(2, 32, 8.0, userId, url);
    }

    void start() throws InterruptedException {
        long endTime = System.currentTimeMillis() + duration * 1000L;

        List<Thread> threads = List.of(
                new Thread(() -> client1.run(endTime), "client-1"),
                new Thread(() -> client2.run(endTime), "client-2"),
                new Thread(() -> Display.run(client1, client2, endTime), "display"));

        // Graceful shutdown on SIGINT / SIGTERM
        Thread shutdownHook = new Thread(() -> {
            System.out.println("\nSimulation interrupted.");
            threads.forEach(Thread::interrupt);
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        Runtime.getRuntime().removeShutdownHook(shutdownHook);

        Display.displayResults(List.of(client1, client2), duration, userId, url);
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.err.println("Usage: Simulator USER_ID [URL] [DURATION]");
            System.exit(2);
        }
        String userId = args[0];
        String url = args.length > 1 ? args[1] : DEFAULT_SERVER_URL;
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_DURATION;

        Simulator simulator = new Simulator(userId, url, duration);

        System.out.println("Starting simulation for " + duration + " seconds...");
        System.out.println("User ID: " + userId);
        System.out.println("Server URL: " + url);

        Thread.sleep(1000);

        simulator.start();
    }
}
