package pl.homemind.pocketcodex;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolRegistry {
    private final SettingsTools settingsTools;
    private final PhotoTools photoTools;

    public ToolRegistry(Activity activity) {
        this.settingsTools = new SettingsTools(activity);
        this.photoTools = new PhotoTools(activity);
    }

    public SettingsTools settingsTools() {
        return settingsTools;
    }

    public JSONArray apiDefinitions() throws Exception {
        JSONArray tools = new JSONArray();

        tools.put(function("get_device_state",
                "Odczytaj lokalnie podstawowy stan telefonu: jasność, głośność multimediów, baterię i dostęp do ustawień.",
                obj(new String[]{}, new JSONObject())));

        tools.put(function("set_brightness",
                "Ustaw jasność ekranu w procentach. Wymaga uprawnienia do modyfikacji ustawień systemowych.",
                obj(new String[]{"percent"}, new JSONObject()
                        .put("percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)))));

        tools.put(function("set_media_volume",
                "Ustaw głośność multimediów w procentach.",
                obj(new String[]{"percent"}, new JSONObject()
                        .put("percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)))));

        tools.put(function("set_flashlight",
                "Włącz lub wyłącz latarkę telefonu.",
                obj(new String[]{"enabled"}, new JSONObject()
                        .put("enabled", new JSONObject().put("type", "boolean")))));

        tools.put(function("open_settings",
                "Otwórz właściwy panel ustawień Androida. Używaj, gdy bezpośrednia zmiana nie jest dostępna.",
                obj(new String[]{"panel"}, new JSONObject()
                        .put("panel", new JSONObject().put("type", "string")
                                .put("enum", stringArray("general", "wifi", "bluetooth", "display", "sound", "apps", "accessibility", "notifications"))))));

        tools.put(function("list_recent_photos",
                "Zwróć metadane ostatnich zdjęć dostępnych dla aplikacji. Nie modyfikuje zdjęć.",
                obj(new String[]{"limit"}, new JSONObject()
                        .put("limit", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20)))));

        tools.put(function("rotate_latest_photo",
                "Obróć najnowsze dostępne zdjęcie i zapisz wynik jako nową kopię w Pictures/PocketCodex.",
                obj(new String[]{"degrees"}, new JSONObject()
                        .put("degrees", new JSONObject().put("type", "integer").put("enum", intArray(90, 180, 270))))));

        tools.put(function("grayscale_latest_photo",
                "Utwórz czarno-białą kopię najnowszego dostępnego zdjęcia w Pictures/PocketCodex.",
                obj(new String[]{}, new JSONObject())));

        tools.put(function("global_action",
                "Wykonaj zatwierdzoną globalną akcję Androida przez Accessibility Service.",
                obj(new String[]{"action"}, new JSONObject()
                        .put("action", new JSONObject().put("type", "string")
                                .put("enum", stringArray("back", "home", "recents", "notifications", "quick_settings", "screenshot"))))));

        tools.put(function("click_visible_text",
                "Kliknij widoczny na ekranie element zawierający podany tekst. Wymaga Accessibility Service.",
                obj(new String[]{"text"}, new JSONObject()
                        .put("text", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 100)))));

        return tools;
    }

    /** OpenAI-compatible tool schema used by OpenRouter Chat Completions. */
    public JSONArray chatApiDefinitions() throws Exception {
        JSONArray raw = apiDefinitions();
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject src = raw.getJSONObject(i);
            JSONObject function = new JSONObject()
                    .put("name", src.getString("name"))
                    .put("description", src.optString("description", ""))
                    .put("parameters", src.getJSONObject("parameters"));
            out.put(new JSONObject()
                    .put("type", "function")
                    .put("function", function));
        }
        return out;
    }

    public boolean requiresApproval(String name) {
        switch (name) {
            case "get_device_state":
            case "list_recent_photos":
                return false;
            default:
                return true;
        }
    }

    public String execute(String name, String arguments) throws Exception {
        JSONObject args = arguments == null || arguments.isEmpty() ? new JSONObject() : new JSONObject(arguments);
        switch (name) {
            case "get_device_state":
                return settingsTools.getDeviceState();
            case "set_brightness":
                return settingsTools.setBrightness(args.getInt("percent"));
            case "set_media_volume":
                return settingsTools.setMediaVolume(args.getInt("percent"));
            case "set_flashlight":
                return settingsTools.setFlashlight(args.getBoolean("enabled"));
            case "open_settings":
                return settingsTools.openSettings(args.getString("panel"));
            case "list_recent_photos":
                return photoTools.listRecent(args.optInt("limit", 5));
            case "rotate_latest_photo":
                return photoTools.rotateLatest(args.getInt("degrees"));
            case "grayscale_latest_photo":
                return photoTools.grayscaleLatest();
            case "global_action":
                return PocketAccessibilityService.performNamedGlobalAction(args.getString("action"));
            case "click_visible_text":
                return PocketAccessibilityService.clickVisibleText(args.getString("text"));
            default:
                return new JSONObject().put("ok", false).put("error", "Nieznane narzędzie: " + name).toString();
        }
    }

    private static JSONArray stringArray(String... values) {
        JSONArray out = new JSONArray();
        for (String value : values) out.put(value);
        return out;
    }

    private static JSONArray intArray(int... values) {
        JSONArray out = new JSONArray();
        for (int value : values) out.put(value);
        return out;
    }

    private static JSONObject function(String name, String description, JSONObject parameters) throws Exception {
        return new JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters)
                .put("strict", true);
    }

    private static JSONObject obj(String[] required, JSONObject properties) throws Exception {
        JSONArray requiredArray = new JSONArray();
        for (String item : required) requiredArray.put(item);
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", requiredArray)
                .put("additionalProperties", false);
    }
}
