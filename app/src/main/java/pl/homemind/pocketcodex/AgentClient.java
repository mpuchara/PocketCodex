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

    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final int MAX_TOOL_STEPS = 10;
    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final String SYSTEM_PROMPT =
            "Jesteś lokalnym agentem Androida o nazwie PocketCodex. Wykonuj operacje na urządzeniu wyłącznie przez dostępne narzędzia. " +
            "Nie twierdź, że coś zostało wykonane, dopóki narzędzie nie zwróci powodzenia. " +
            "Preferuj działania odwracalne. Przy zdjęciach edytory tworzą nową kopię zamiast nadpisywać oryginał. " +
            "Jeśli brakuje uprawnień, wyjaśnij użytkownikowi krótko, jakiego uprawnienia potrzeba. " +
            "Jeśli użytkownik prosi o zmianę względną, np. zmniejsz jasność o 10%, najpierw użyj get_device_state, oblicz nową wartość i dopiero potem użyj set_brightness. " +
            "Odpowiadaj po polsku, krótko i konkretnie.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ToolRegistry tools;
    private String apiKey;
    private String model;
    private JSONArray messages;

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
        messages = null;
    }

    public void send(String userText, Callback callback) {
        executor.execute(() -> {
            try {
                ensureConversation();
                messages.put(new JSONObject()
                        .put("role", "user")
                        .put("content", userText));
                callback.onStatus("Pytam darmowe AI…");
                runModelLoop(0, callback);
            } catch (Exception e) {
                callback.onError(readableError(e));
                callback.onFinished();
            }
        });
    }

    private void ensureConversation() throws Exception {
        if (messages != null) return;
        messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT));
    }

    private JSONObject baseBody() throws Exception {
        return new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools.chatApiDefinitions())
                .put("tool_choice", "auto")
                .put("parallel_tool_calls", false);
    }

    private void runModelLoop(int toolStep, Callback callback) throws Exception {
        if (toolStep > MAX_TOOL_STEPS) {
            callback.onError("Przerwałem po " + MAX_TOOL_STEPS + " krokach, żeby uniknąć pętli.");
            callback.onFinished();
            return;
        }

        JSONObject response = post(baseBody());
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("Darmowy model nie zwrócił odpowiedzi.");
        }

        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("Darmowy model zwrócił nieoczekiwany format odpowiedzi.");
        }

        String visibleText = extractContent(message.opt("content"));
        JSONArray toolCalls = message.optJSONArray("tool_calls");

        if (toolCalls == null || toolCalls.length() == 0) {
            messages.put(copyAssistantMessage(message, null));
            if (!visibleText.isEmpty()) callback.onText(visibleText);
            callback.onStatus("Gotowe");
            callback.onFinished();
            return;
        }

        if (!visibleText.isEmpty()) callback.onText(visibleText);
        messages.put(copyAssistantMessage(message, toolCalls));
        processToolCalls(toolCalls, 0, toolStep, callback);
    }

    private JSONObject copyAssistantMessage(JSONObject source, JSONArray toolCalls) throws Exception {
        JSONObject out = new JSONObject().put("role", "assistant");
        Object content = source.opt("content");
        if (content == null || content == JSONObject.NULL) out.put("content", JSONObject.NULL);
        else out.put("content", content);
        if (toolCalls != null && toolCalls.length() > 0) out.put("tool_calls", toolCalls);
        return out;
    }

    private void processToolCalls(JSONArray toolCalls, int index, int toolStep, Callback callback) throws Exception {
        if (index >= toolCalls.length()) {
            callback.onStatus("Sprawdzam wynik…");
            runModelLoop(toolStep + 1, callback);
            return;
        }

        JSONObject call = toolCalls.optJSONObject(index);
        if (call == null) {
            processToolCalls(toolCalls, index + 1, toolStep, callback);
            return;
        }

        String callId = call.optString("id", "");
        JSONObject function = call.optJSONObject("function");
        String name = function == null ? "" : function.optString("name", "");
        String arguments = function == null ? "{}" : function.optString("arguments", "{}");
        if (callId.isEmpty() || name.isEmpty()) {
            throw new IllegalStateException("Model zwrócił niepełną akcję.");
        }

        callback.onStatus("Przygotowuję akcję…");

        if (tools.requiresApproval(name)) {
            callback.onApprovalRequired(name, arguments, approved -> executor.execute(() -> {
                try {
                    String result;
                    if (approved) {
                        callback.onStatus("Wykonuję na telefonie…");
                        result = tools.execute(name, arguments);
                    } else {
                        result = new JSONObject()
                                .put("ok", false)
                                .put("error", "Użytkownik odrzucił wykonanie tej operacji.")
                                .toString();
                    }
                    appendToolResult(callId, result);
                    processToolCalls(toolCalls, index + 1, toolStep, callback);
                } catch (Exception e) {
                    callback.onError(readableError(e));
                    callback.onFinished();
                }
            }));
        } else {
            callback.onStatus("Odczytuję stan telefonu…");
            String result = tools.execute(name, arguments);
            appendToolResult(callId, result);
            processToolCalls(toolCalls, index + 1, toolStep, callback);
        }
    }

    private void appendToolResult(String callId, String result) throws Exception {
        messages.put(new JSONObject()
                .put("role", "tool")
                .put("tool_call_id", callId)
                .put("content", result));
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
                if (code == 404 && message.toLowerCase().contains("tool")) {
                    throw new IllegalStateException("Darmowy model chwilowo nie obsługuje akcji telefonu. Spróbuj ponownie — OpenRouter wybierze inny model.");
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

    private static String extractContent(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return ((String) content).trim();
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String text = part.optString("text", "");
                if (!text.isEmpty()) {
                    if (out.length() > 0) out.append('\n');
                    out.append(text);
                }
            }
            return out.toString().trim();
        }
        return String.valueOf(content).trim();
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
