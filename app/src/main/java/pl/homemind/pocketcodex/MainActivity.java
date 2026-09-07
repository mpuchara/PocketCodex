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
    private static final String DEFAULT_MODEL = "openrouter/free";
    private static final String PREF_OPENROUTER_KEY = "openrouter_api_key";

    private TextView chat;
    private TextView status;
    private EditText promptField;
    private Button sendButton;
    private ScrollView chatScroll;
    private ToolRegistry tools;
    private AgentClient agent;
    private OpenRouterAuth openRouterAuth;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pocket_codex", MODE_PRIVATE);
        tools = new ToolRegistry(this);
        openRouterAuth = new OpenRouterAuth(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("PocketCodex");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, 1);
        heading.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Powiedz, co telefon ma zrobić.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        heading.addView(subtitle, matchWrap());

        topBar.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button settingsButton = new Button(this);
        settingsButton.setText("Ustawienia");
        settingsButton.setAllCaps(false);
        settingsButton.setOnClickListener(v -> showAppSettings());
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(dp(115), dp(46)));
        root.addView(topBar, matchWrap());

        status = new TextView(this);
        status.setText(hasAiConnection() ? "Gotowe" : "Połącz darmowe AI");
        status.setTextColor(Color.GRAY);
        status.setPadding(0, dp(10), 0, dp(8));
        root.addView(status, matchWrap());

        chatScroll = new ScrollView(this);
        chat = new TextView(this);
        chat.setTextSize(16);
        chat.setTextColor(Color.BLACK);
        chat.setTextIsSelectable(true);
        chat.setPadding(dp(12), dp(12), dp(12), dp(12));
        chatScroll.addView(chat, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        root.addView(chatScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        promptField = new EditText(this);
        promptField.setHint("Np. „zmniejsz jasność o 10%” albo „zrób czarno-białą kopię ostatniego zdjęcia”");
        promptField.setMinLines(2);
        promptField.setMaxLines(5);
        promptField.setGravity(Gravity.TOP);
        root.addView(promptField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        sendButton = new Button(this);
        sendButton.setText("Wykonaj");
        sendButton.setAllCaps(false);
        sendButton.setOnClickListener(v -> sendPrompt());
        root.addView(sendButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));

        setContentView(root);
        rebuildAgent();

        if (hasAiConnection()) {
            append("PocketCodex", "Gotowe. Napisz, co mam zrobić na telefonie.");
        } else {
            root.post(this::showFirstRunSetup);
        }
    }

    private boolean hasAiConnection() {
        return !prefs.getString(PREF_OPENROUTER_KEY, "").trim().isEmpty();
    }

    private void rebuildAgent() {
        agent = new AgentClient(
                tools,
                prefs.getString(PREF_OPENROUTER_KEY, ""),
                DEFAULT_MODEL
        );
    }

    private void showFirstRunSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Połącz darmowe AI")
                .setMessage(
                        "PocketCodex korzysta z darmowych modeli online przez OpenRouter. " +
                        "Nie musisz tworzyć ani kopiować żadnego klucza API.\n\n" +
                        "Po kliknięciu otworzy się przeglądarka. Zaloguj się lub utwórz konto OpenRouter i zaakceptuj połączenie. " +
                        "Po autoryzacji PocketCodex połączy się automatycznie."
                )
                .setPositiveButton("Połącz", (dialog, which) -> startOpenRouterLogin())
                .setNegativeButton("Później", null)
                .show();
    }

    private void startOpenRouterLogin() {
        status.setText("Otwieram OpenRouter…");
        Toast.makeText(this, "Po autoryzacji połączenie dokończy się automatycznie", Toast.LENGTH_LONG).show();

        openRouterAuth.cancel();
        openRouterAuth = new OpenRouterAuth(this);
        openRouterAuth.start(new OpenRouterAuth.Callback() {
            @Override
            public void onStatus(String value) {
                runOnUiThread(() -> status.setText(value));
            }

            @Override
            public void onSuccess(String apiKey) {
                runOnUiThread(() -> {
                    prefs.edit()
                            .putString(PREF_OPENROUTER_KEY, apiKey)
                            .remove("api_key")
                            .remove("model")
                            .apply();
                    rebuildAgent();
                    status.setText("Gotowe");
                    if (chat.length() == 0) {
                        append("PocketCodex", "Połączono z darmowym AI. Napisz, co mam zrobić na telefonie.");
                    } else {
                        append("PocketCodex", "Połączono z darmowym AI.");
                    }
                    Toast.makeText(MainActivity.this, "Darmowe AI połączone", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    status.setText(hasAiConnection() ? "Gotowe" : "Nie połączono");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Nie udało się połączyć")
                            .setMessage(error)
                            .setPositiveButton("Spróbuj ponownie", (d, w) -> startOpenRouterLogin())
                            .setNegativeButton("Później", null)
                            .show();
                });
            }
        });
    }

    private void showAppSettings() {
        String connectionLabel = hasAiConnection() ? "Zmień konto darmowego AI" : "Połącz darmowe AI";
        String[] items = new String[]{
                connectionLabel,
                "Uprawnienia telefonu",
                "Nowa rozmowa",
                "Rozłącz darmowe AI",
                "Informacje"
        };
        new AlertDialog.Builder(this)
                .setTitle("PocketCodex")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startOpenRouterLogin();
                            break;
                        case 1:
                            showPermissionsMenu();
                            break;
                        case 2:
                            if (agent != null) agent.resetConversation();
                            chat.setText("");
                            append("PocketCodex", "Nowa rozmowa. Co mam zrobić?");
                            break;
                        case 3:
                            disconnectOpenRouter();
                            break;
                        default:
                            showInfo();
                            break;
                    }
                })
                .show();
    }

    private void disconnectOpenRouter() {
        if (!hasAiConnection()) {
            Toast.makeText(this, "Darmowe AI nie jest połączone", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Rozłączyć darmowe AI?")
                .setMessage("PocketCodex usunie lokalny klucz dostępu OpenRouter z telefonu.")
                .setPositiveButton("Rozłącz", (d, w) -> {
                    prefs.edit().remove(PREF_OPENROUTER_KEY).apply();
                    if (agent != null) agent.resetConversation();
                    rebuildAgent();
                    status.setText("Połącz darmowe AI");
                    append("PocketCodex", "Darmowe AI zostało rozłączone.");
                })
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
                .setTitle("PocketCodex 0.3")
                .setMessage(
                        "AI: OpenRouter Free (dynamiczny wybór darmowego modelu)\n\n" +
                        "Model planuje online, ale akcje na telefonie wykonuje lokalnie PocketCodex. " +
                        "Działania zmieniające urządzenie wymagają Twojego potwierdzenia.\n\n" +
                        "Skład darmowych modeli może zmieniać się automatycznie."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void sendPrompt() {
        String prompt = promptField.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (!hasAiConnection()) {
            showFirstRunSetup();
            return;
        }

        rebuildAgentIfNeeded();
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
                runOnUiThread(() -> append("PocketCodex", text));
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

    private void rebuildAgentIfNeeded() {
        if (agent == null) rebuildAgent();
        agent.setApiKey(prefs.getString(PREF_OPENROUTER_KEY, ""));
        agent.setModel(DEFAULT_MODEL);
    }

    private void showApproval(String toolName, String arguments, AgentClient.ApprovalDecision decision) {
        String description = friendlyAction(toolName, arguments);
        new AlertDialog.Builder(this)
                .setTitle("Pozwolić PocketCodex?")
                .setMessage(description)
                .setPositiveButton("Wykonaj", (d, w) -> decision.resolve(true))
                .setNegativeButton("Nie", (d, w) -> decision.resolve(false))
                .setCancelable(false)
                .show();
    }

    private String friendlyAction(String toolName, String arguments) {
        try {
            JSONObject args = new JSONObject(arguments == null ? "{}" : arguments);
            switch (toolName) {
                case "set_brightness":
                    return "Ustawić jasność ekranu na " + args.optInt("percent") + "%?";
                case "set_media_volume":
                    return "Ustawić głośność multimediów na " + args.optInt("percent") + "%?";
                case "set_flashlight":
                    return args.optBoolean("enabled") ? "Włączyć latarkę?" : "Wyłączyć latarkę?";
                case "open_settings":
                    return "Otworzyć odpowiedni ekran ustawień telefonu?";
                case "rotate_latest_photo":
                    return "Utworzyć obróconą kopię ostatniego zdjęcia (" + args.optInt("degrees") + "°)?";
                case "grayscale_latest_photo":
                    return "Utworzyć czarno-białą kopię ostatniego zdjęcia?";
                case "global_action":
                    return "Wykonać akcję systemową: " + friendlyGlobalAction(args.optString("action")) + "?";
                case "click_visible_text":
                    return "Kliknąć na ekranie element „" + args.optString("text") + "”?";
                default:
                    return "Wykonać tę akcję na telefonie?";
            }
        } catch (Exception ignored) {
            return "Wykonać tę akcję na telefonie?";
        }
    }

    private String friendlyGlobalAction(String action) {
        switch (action) {
            case "back": return "Wstecz";
            case "home": return "Ekran główny";
            case "recents": return "Ostatnie aplikacje";
            case "notifications": return "Powiadomienia";
            case "quick_settings": return "Szybkie ustawienia";
            case "screenshot": return "Zrzut ekranu";
            default: return action;
        }
    }

    private void showPermissionsMenu() {
        String[] items = new String[]{
                "Zdjęcia",
                "Aparat / latarka",
                "Zmiana ustawień systemowych",
                "Sterowanie ekranem (Accessibility)",
                "Główne ustawienia telefonu"
        };
        new AlertDialog.Builder(this)
                .setTitle("Uprawnienia telefonu")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            requestPhotoPermission();
                            break;
                        case 1:
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                            break;
                        case 2:
                            tools.settingsTools().openWriteSettingsPermission();
                            break;
                        case 3:
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            break;
                        default:
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                            break;
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
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
