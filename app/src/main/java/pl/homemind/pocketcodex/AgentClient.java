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

    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final int MAX_TOOL_STEPS = 10;

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
                callback.onStatus("Łączę z modelem…");
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
                "Jesteś lokalnym agentem Androida. Wykonuj operacje na urządzeniu wyłącznie przez dostępne narzędzia. " +
                "Nie twierdź, że coś zostało wykonane, dopóki narzędzie nie zwróci powodzenia. " +
                "Preferuj działania odwracalne. Przy zdjęciach edytory tworzą nową kopię zamiast nadpisywać oryginał. " +
                "Jeśli brakuje uprawnień, wyjaśnij użytkownikowi dokładnie, jakiego uprawnienia potrzeba. " +
                "Odpowiadaj po polsku, krótko i konkretnie.");
        body.put("tools", tools.apiDefinitions());
        return body;
    }

    private void handleResponse(JSONObject response, int toolStep, Callback callback) throws Exception {
        String responseId = response.optString("id", null);
        if (responseId == null) {
            throw new IllegalStateException("Brak id odpowiedzi API.");
        }

        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            throw new IllegalStateException("Brak pola output w odpowiedzi API.");
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
            callback.onError("Przerwałem po " + MAX_TOOL_STEPS + " krokach narzędzi, żeby uniknąć pętli.");
            callback.onFinished();
            return;
        }

        String callId = functionCall.optString("call_id", null);
        String name = functionCall.optString("name", null);
        String arguments = functionCall.optString("arguments", "{}");
        if (callId == null || name == null) {
            throw new IllegalStateException("Niepełne wywołanie narzędzia.");
        }

        callback.onStatus("Agent chce użyć: " + name);

        if (tools.requiresApproval(name)) {
            String currentResponseId = responseId;
            callback.onApprovalRequired(name, arguments, approved -> executor.execute(() -> {
                try {
                    String toolResult;
                    if (approved) {
                        callback.onStatus("Wykonuję lokalnie: " + name);
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
            throw new IllegalStateException("Brak klucza OpenAI API.");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(120_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        conn.setRequestProperty("Content-Type", "application/json");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(stream);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            String message = text;
            try {
                JSONObject errorJson = new JSONObject(text);
                JSONObject error = errorJson.optJSONObject("error");
                if (error != null) message = error.optString("message", text);
            } catch (Exception ignored) {}
            throw new IllegalStateException("OpenAI API HTTP " + code + ": " + message);
        }
        return new JSONObject(text);
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
