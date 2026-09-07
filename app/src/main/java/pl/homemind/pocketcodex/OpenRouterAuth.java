package pl.homemind.pocketcodex;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenRouterAuth {
    public interface Callback {
        void onStatus(String status);
        void onSuccess(String apiKey);
        void onError(String error);
    }

    private static final String AUTH_URL = "https://openrouter.ai/auth";
    private static final String EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys";
    private static final int CALLBACK_TIMEOUT_MS = 5 * 60 * 1000;

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile ServerSocket activeServer;

    public OpenRouterAuth(Activity activity) {
        this.activity = activity;
    }

    public void start(Callback callback) {
        executor.execute(() -> {
            ServerSocket server = null;
            try {
                callback.onStatus("Przygotowuję bezpieczne logowanie…");

                String verifier = randomBase64Url(48);
                String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
                String state = randomBase64Url(24);

                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
                server.setSoTimeout(CALLBACK_TIMEOUT_MS);
                activeServer = server;

                int port = server.getLocalPort();
                String callbackUrl = "http://127.0.0.1:" + port + "/callback?state=" + urlEncode(state);
                String authUrl = AUTH_URL
                        + "?callback_url=" + urlEncode(callbackUrl)
                        + "&code_challenge=" + urlEncode(challenge)
                        + "&code_challenge_method=S256"
                        + "&key_label=" + urlEncode("PocketCodex");

                callback.onStatus("Zaloguj się do OpenRouter w przeglądarce…");
                activity.runOnUiThread(() -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                    } catch (Exception e) {
                        callback.onError("Nie udało się otworzyć przeglądarki.");
                    }
                });

                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(15_000);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                    );
                    String requestLine = reader.readLine();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Consume request headers before writing the response.
                    }

                    if (requestLine == null || !requestLine.startsWith("GET ")) {
                        writeBrowserResponse(socket, false, "Nieprawidłowa odpowiedź logowania.");
                        throw new IllegalStateException("Nieprawidłowy callback OpenRouter.");
                    }

                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        writeBrowserResponse(socket, false, "Nieprawidłowa odpowiedź logowania.");
                        throw new IllegalStateException("Nieprawidłowy callback OpenRouter.");
                    }

                    URI callback = URI.create("http://127.0.0.1" + parts[1]);
                    String returnedState = queryParam(callback.getRawQuery(), "state");
                    String code = queryParam(callback.getRawQuery(), "code");
                    String error = queryParam(callback.getRawQuery(), "error");

                    if (!state.equals(returnedState)) {
                        writeBrowserResponse(socket, false, "Logowanie zostało odrzucone ze względów bezpieczeństwa.");
                        throw new IllegalStateException("Nie zgadza się parametr bezpieczeństwa OAuth.");
                    }
                    if (error != null && !error.isEmpty()) {
                        writeBrowserResponse(socket, false, "Logowanie zostało anulowane.");
                        throw new IllegalStateException("OpenRouter: " + error);
                    }
                    if (code == null || code.isEmpty()) {
                        writeBrowserResponse(socket, false, "Nie otrzymano kodu logowania.");
                        throw new IllegalStateException("OpenRouter nie zwrócił kodu autoryzacyjnego.");
                    }

                    writeBrowserResponse(socket, true, "PocketCodex został połączony. Możesz wrócić do aplikacji.");
                    callback.onStatus("Kończę połączenie z darmowym AI…");
                    String apiKey = exchangeCode(code, verifier);
                    callback.onSuccess(apiKey);
                }
            } catch (java.net.SocketTimeoutException e) {
                callback.onError("Logowanie wygasło. Spróbuj połączyć OpenRouter jeszcze raz.");
            } catch (Exception e) {
                String message = e.getMessage();
                callback.onError(message == null || message.isEmpty() ? e.getClass().getSimpleName() : message);
            } finally {
                activeServer = null;
                if (server != null) {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public void cancel() {
        ServerSocket server = activeServer;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    private String exchangeCode(String code, String verifier) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(EXCHANGE_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Title", "PocketCodex");
        conn.setRequestProperty("HTTP-Referer", "https://github.com/mpuchara/PocketCodex");

        JSONObject body = new JSONObject()
                .put("code", code)
                .put("code_verifier", verifier)
                .put("code_challenge_method", "S256");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(stream);
        conn.disconnect();

        if (status < 200 || status >= 300) {
            String message = text;
            try {
                JSONObject parsed = new JSONObject(text);
                JSONObject error = parsed.optJSONObject("error");
                if (error != null) message = error.optString("message", text);
                else message = parsed.optString("message", text);
            } catch (Exception ignored) {}
            throw new IllegalStateException("Nie udało się połączyć OpenRouter (HTTP " + status + "): " + message);
        }

        JSONObject response = new JSONObject(text);
        String key = response.optString("key", "").trim();
        if (key.isEmpty()) {
            JSONObject data = response.optJSONObject("data");
            if (data != null) key = data.optString("key", "").trim();
        }
        if (key.isEmpty()) throw new IllegalStateException("OpenRouter nie zwrócił klucza dostępu.");
        return key;
    }

    private static void writeBrowserResponse(Socket socket, boolean success, String message) {
        try {
            String title = success ? "PocketCodex połączony" : "PocketCodex — błąd";
            String symbol = success ? "✓" : "!";
            String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + title + "</title></head><body style=\"font-family:sans-serif;padding:32px;max-width:560px;margin:auto\">"
                    + "<div style=\"font-size:52px\">" + symbol + "</div><h2>" + title + "</h2><p>" + message + "</p>"
                    + "<p>Możesz zamknąć tę kartę.</p></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            PrintWriter headers = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8);
            headers.print("HTTP/1.1 200 OK\r\n");
            headers.print("Content-Type: text/html; charset=utf-8\r\n");
            headers.print("Content-Length: " + body.length + "\r\n");
            headers.print("Connection: close\r\n\r\n");
            headers.flush();
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    private static String queryParam(String rawQuery, String wanted) throws Exception {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String rawName = split >= 0 ? pair.substring(0, split) : pair;
            String rawValue = split >= 0 ? pair.substring(split + 1) : "";
            String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name());
            if (wanted.equals(name)) {
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name());
            }
        }
        return null;
    }

    private static String randomBase64Url(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return base64Url(random);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static String base64Url(byte[] input) {
        return Base64.encodeToString(input, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString().trim();
    }
}
