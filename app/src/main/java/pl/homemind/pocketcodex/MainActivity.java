package pl.homemind.pocketcodex;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_PHOTOS = 1001;
    private static final int REQ_CAMERA = 1002;

    private TextView chat;
    private TextView status;
    private EditText apiKeyField;
    private EditText modelField;
    private EditText promptField;
    private Button sendButton;
    private ScrollView chatScroll;
    private ToolRegistry tools;
    private AgentClient agent;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pocket_codex", MODE_PRIVATE);
        tools = new ToolRegistry(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("PocketCodex");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, 1);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Agent Androida: model planuje, telefon wykonuje lokalnie po Twojej zgodzie.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(2), 0, dp(10));
        root.addView(subtitle, matchWrap());

        LinearLayout configRow = new LinearLayout(this);
        configRow.setOrientation(LinearLayout.HORIZONTAL);

        modelField = new EditText(this);
        modelField.setSingleLine(true);
        modelField.setText(prefs.getString("model", "gpt-5.3-codex"));
        modelField.setHint("model");
        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        configRow.addView(modelField, modelParams);

        Button permissions = new Button(this);
        permissions.setText("Uprawnienia");
        permissions.setOnClickListener(v -> showPermissionsMenu());
        configRow.addView(permissions, new LinearLayout.LayoutParams(dp(130), dp(48)));
        root.addView(configRow, matchWrap());

        apiKeyField = new EditText(this);
        apiKeyField.setHint("OpenAI API key — zapisywany tylko lokalnie na tym urządzeniu");
        apiKeyField.setSingleLine(true);
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setText(prefs.getString("api_key", ""));
        root.addView(apiKeyField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button save = new Button(this);
        save.setText("Zapisz");
        save.setOnClickListener(v -> saveConfig());
        controls.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button reset = new Button(this);
        reset.setText("Nowa rozmowa");
        reset.setOnClickListener(v -> {
            if (agent != null) agent.resetConversation();
            chat.setText("");
            append("System", "Rozpoczęto nową rozmowę.");
        });
        controls.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1f));
        root.addView(controls, matchWrap());

        status = new TextView(this);
        status.setText("Gotowe");
        status.setTextColor(Color.GRAY);
        status.setPadding(0, dp(8), 0, dp(6));
        root.addView(status, matchWrap());

        chatScroll = new ScrollView(this);
        chat = new TextView(this);
        chat.setTextSize(16);
        chat.setTextColor(Color.BLACK);
        chat.setTextIsSelectable(true);
        chat.setPadding(dp(12), dp(12), dp(12), dp(12));
        chatScroll.addView(chat, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams chatParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(chatScroll, chatParams);

        promptField = new EditText(this);
        promptField.setHint("Np. „Ustaw jasność na 25% i włącz latarkę” albo „zrób czarno-białą kopię ostatniego zdjęcia”");
        promptField.setMinLines(2);
        promptField.setMaxLines(5);
        promptField.setGravity(Gravity.TOP);
        root.addView(promptField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        sendButton = new Button(this);
        sendButton.setText("Wykonaj");
        sendButton.setOnClickListener(v -> sendPrompt());
        root.addView(sendButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        setContentView(root);
        rebuildAgent();
        append("System", "MVP 0.1. Narzędzia zmieniające telefon wymagają każdorazowego potwierdzenia.");
    }

    private void rebuildAgent() {
        String key = apiKeyField == null ? prefs.getString("api_key", "") : apiKeyField.getText().toString();
        String model = modelField == null ? prefs.getString("model", "gpt-5.3-codex") : modelField.getText().toString().trim();
        if (model.isEmpty()) model = "gpt-5.3-codex";
        agent = new AgentClient(tools, key, model);
    }

    private void saveConfig() {
        String model = modelField.getText().toString().trim();
        if (model.isEmpty()) model = "gpt-5.3-codex";
        prefs.edit()
                .putString("api_key", apiKeyField.getText().toString().trim())
                .putString("model", model)
                .apply();
        rebuildAgent();
        Toast.makeText(this, "Konfiguracja zapisana lokalnie", Toast.LENGTH_SHORT).show();
    }

    private void sendPrompt() {
        String prompt = promptField.getText().toString().trim();
        if (prompt.isEmpty()) return;
        String key = apiKeyField.getText().toString().trim();
        if (key.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Brak klucza API")
                    .setMessage("Ten MVP używa OpenAI Responses API. Wpisz klucz API. Klucz nie jest wysyłany nigdzie poza api.openai.com.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String model = modelField.getText().toString().trim();
        if (model.isEmpty()) model = "gpt-5.3-codex";
        agent.setApiKey(key);
        agent.setModel(model);

        append("Ty", prompt);
        promptField.setText("");
        sendButton.setEnabled(false);

        agent.send(prompt, new AgentClient.Callback() {
            @Override
            public void onStatus(String value) {
                runOnUiThread(() -> status.setText(value));
            }

            @Override
            public void onText(String text) {
                runOnUiThread(() -> append("Codex", text));
            }

            @Override
            public void onApprovalRequired(String toolName, String arguments, AgentClient.ApprovalDecision decision) {
                runOnUiThread(() -> showApproval(toolName, arguments, decision));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> append("Błąd", error));
            }

            @Override
            public void onFinished() {
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    status.setText("Gotowe");
                });
            }
        });
    }

    private void showApproval(String toolName, String arguments, AgentClient.ApprovalDecision decision) {
        String prettyArgs = arguments;
        try { prettyArgs = new JSONObject(arguments).toString(2); } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Zezwolić na lokalną akcję?")
                .setMessage("Narzędzie: " + toolName + "\n\n" + prettyArgs)
                .setPositiveButton("Wykonaj", (d, w) -> decision.resolve(true))
                .setNegativeButton("Odrzuć", (d, w) -> decision.resolve(false))
                .setCancelable(false)
                .show();
    }

    private void showPermissionsMenu() {
        String[] items = new String[]{
                "Zdjęcia",
                "Aparat / latarka",
                "Zmiana ustawień systemowych",
                "Accessibility Service",
                "Otwórz główne ustawienia"
        };
        new AlertDialog.Builder(this)
                .setTitle("Uprawnienia PocketCodex")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: requestPhotoPermission(); break;
                        case 1: requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA); break;
                        case 2: tools.settingsTools().openWriteSettingsPermission(); break;
                        case 3: startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                        default: startActivity(new Intent(Settings.ACTION_SETTINGS)); break;
                    }
                })
                .show();
    }

    private void requestPhotoPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (Build.VERSION.SDK_INT >= 34) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQ_PHOTOS);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_PHOTOS);
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PHOTOS);
        }
    }

    private void append(String who, String text) {
        if (chat.length() > 0) chat.append("\n\n");
        chat.append(who + ":\n" + text);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
