package pl.homemind.pocketcodex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentClient {
    public interface ApprovalDecision {
        void resolve(boolean approved);
    }

    public interface Callback {
        void onStatus(String status);
        void onText(String text);
        void onApprovalRequired(String toolName, String arguments, ApprovalDecision decision);
        void onError(String error);
        void onFinished();
    }

    private static final String ENDPOINT = "https://openrouter.ai/api/v1/responses";
    private static final int MAX_TOOL_STEPS = 10;
    private static final int MAX_HTTP_ATTEMPTS = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ToolRegistry tools;
    private String apiKey;
    private String model;
    private String previousResponseId;

    public AgentClient(ToolRegistry tools, String apiKey, String model) {
        this.tools = tools;
        this.apiKey = apiKey;
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void resetConversation() {
        previousResponseId = null;
    }

    public void send(String userText, Callback callback) {
        executor.execute(() -> {
            try {
                callback.onStatus("Pytam darmowe AI…");
                JSONObject body = baseBody();
                body.put("input", userText);
                if (previousResponseId != null) {
                    body.put("previous_response_id", previousResponseId);
                }
                JSONObject response = post(body);
                handleResponse(response, 0, callback);
            } catch (Exception e) {
                callback.onError(readableError(e));
                callback.onFinished();
            }
        });
    }

    private JSONObject baseBody() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("parallel_tool_calls", false);
        body.put("instructions",
                "Jesteś lokalnym agentem Androida o nazwie PocketCodex. Wykonuj operacje na urządzeniu wyłącznie przez dostępne narzędzia. " +
                "Nie twierdź, że coś zostało wykonane, dopóki narzędzie nie zwróci powodzenia. " +
                "Preferuj działania odwracalne. Przy zdjęciach edytory tworzą nową kopię zamiast nadpisywać oryginał. " +
                "Jeśli brakuje uprawnień, wyjaśnij użytkownikowi krótko, jakiego uprawnienia potrzeba. " +
                "Jeśli użytkownik prosi o zmianę względną, np. zmniejsz jasność o 10%, najpierw odczytaj stan urządzenia. " +
                "Odpowiadaj po polsku, krótko i konkretnie.");
        body.put("tools", tools.apiDefinitions());
        return body;
    }

    private void handleResponse(JSONObject response, int toolStep, Callback callback) throws Exception {
        String responseId = response.optString("id", null);
        if (responseId == null) {
            throw new IllegalStateException("Darmowy model nie zwrócił identyfikatora odpowiedzi.");
        }

        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            String directText = response.optString("output_text", "");
            if (!directText.isEmpty()) {
                callback.onText(directText);
                previousResponseId = responseId;
                callback.onStatus("Gotowe");
                callback.onFinished();
                return;
            }
            throw new IllegalStateException("Darmowy model zwrócił nieoczekiwany format odpowiedzi.");
        }

        JSONObject functionCall = null;
        StringBuilder visibleText = new StringBuilder();

        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            if ("function_call".equals(type) && functionCall == null) {
                functionCall = item;
            } else if ("message".equals(type)) {
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null && "output_text".equals(part.optString("type"))) {
                        if (visibleText.length() > 0) visibleText.append('\n');
                        visibleText.append(part.optString("text", ""));
                    }
                }
            }
        }

        if (visibleText.length() > 0) {
            callback.onText(visibleText.toString());
        }

        if (functionCall == null) {
            previousResponseId = responseId;
            callback.onStatus("Gotowe");
            callback.onFinished();
            return;
        }

        if (toolStep >= MAX_TOOL_STEPS) {
            previousResponseId = responseId;
            callback.onError("Przerwałem po " + MAX_TOOL_STEPS + " krokach, żeby uniknąć pętli.");
            callback.onFinished();
            return;
        }

        String callId = functionCall.optString("call_id", null);
        String name = functionCall.optString("name", null);
        String arguments = functionCall.optString("arguments", "{}");
        if (callId == null || name == null) {
            throw new IllegalStateException("Model zwrócił niepełną akcję.");
        }

        callback.onStatus("Przygotowuję akcję…");

        if (tools.requiresApproval(name)) {
            String currentResponseId = responseId;
            callback.onApprovalRequired(name, arguments, approved -> executor.execute(() -> {
                try {
                    String toolResult;
                    if (approved) {
                        callback.onStatus("Wykonuję na telefonie…");
                        toolResult = tools.execute(name, arguments);
                    } else {
                        toolResult = new JSONObject()
                                .put("ok", false)
                                .put("error", "Użytkownik odrzucił wykonanie tej operacji.")
                                .toString();
                    }
                    continueAfterTool(currentResponseId, callId, toolResult, toolStep + 1, callback);
                } catch (Exception e) {
                    callback.onError(readableError(e));
                    callback.onFinished();
                }
            }));
        } else {
            String toolResult = tools.execute(name, arguments);
            continueAfterTool(responseId, callId, toolResult, toolStep + 1, callback);
        }
    }

    private void continueAfterTool(String parentResponseId, String callId, String toolResult,
                                   int toolStep, Callback callback) throws Exception {
        JSONObject body = baseBody();
        body.put("previous_response_id", parentResponseId);

        JSONArray input = new JSONArray();
        input.put(new JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", toolResult));
        body.put("input", input);

        JSONObject response = post(body);
        handleResponse(response, toolStep, callback);
    }

    private JSONObject post(JSONObject body) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("PocketCodex nie jest połączony z OpenRouter.");
        }

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(120_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("HTTP-Referer", "https://github.com/mpuchara/PocketCodex");
                conn.setRequestProperty("X-Title", "PocketCodex");

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String text = readAll(stream);

                if (code >= 200 && code < 300) {
                    return new JSONObject(text);
                }

                String message = extractError(text);
                boolean temporary = code == 408 || code == 429 || code == 502 || code == 503 || code == 524 || code == 529;
                if (temporary && attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(700L * attempt);
                    continue;
                }

                if (code == 429 || code == 529) {
                    throw new IllegalStateException("Darmowe modele są teraz przeciążone. Spróbuj ponownie za chwilę.");
                }
                if (code == 401 || code == 403) {
                    throw new IllegalStateException("Połączenie z OpenRouter wygasło. Wejdź w Ustawienia i połącz konto ponownie.");
                }
                if (code == 402) {
                    throw new IllegalStateException("OpenRouter odrzucił żądanie rozliczeniowe. PocketCodex używa modelu openrouter/free — spróbuj ponownie lub połącz konto ponownie.");
                }
                throw new IllegalStateException("OpenRouter HTTP " + code + ": " + message);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_HTTP_ATTEMPTS) {
                    Thread.sleep(500L * attempt);
                    continue;
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        if (last != null) throw last;
        throw new IllegalStateException("Nie udało się połączyć z darmowym AI.");
    }

    private static String extractError(String text) {
        String message = text;
        try {
            JSONObject errorJson = new JSONObject(text);
            JSONObject error = errorJson.optJSONObject("error");
            if (error != null) message = error.optString("message", text);
            else message = errorJson.optString("message", text);
        } catch (Exception ignored) {}
        return message == null || message.isEmpty() ? "Nieznany błąd" : message;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String readableError(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }
}
