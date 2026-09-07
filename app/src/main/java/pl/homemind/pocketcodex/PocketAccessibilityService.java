package pl.homemind.pocketcodex;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.List;

public class PocketAccessibilityService extends AccessibilityService {
    private static volatile PocketAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    public static String performNamedGlobalAction(String action) throws Exception {
        PocketAccessibilityService service = instance;
        if (service == null) {
            return new JSONObject()
                    .put("ok", false)
                    .put("permission_required", "ACCESSIBILITY_SERVICE")
                    .put("message", "Włącz usługę PocketCodex w Ustawienia → Ułatwienia dostępu.")
                    .toString();
        }

        int id;
        switch (action) {
            case "back": id = GLOBAL_ACTION_BACK; break;
            case "home": id = GLOBAL_ACTION_HOME; break;
            case "recents": id = GLOBAL_ACTION_RECENTS; break;
            case "notifications": id = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": id = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "screenshot": id = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default:
                return new JSONObject().put("ok", false).put("error", "Nieznana akcja globalna: " + action).toString();
        }

        boolean ok = service.performGlobalAction(id);
        return new JSONObject().put("ok", ok).put("action", action).toString();
    }

    public static String clickVisibleText(String text) throws Exception {
        PocketAccessibilityService service = instance;
        if (service == null) {
            return new JSONObject()
                    .put("ok", false)
                    .put("permission_required", "ACCESSIBILITY_SERVICE")
                    .put("message", "Włącz usługę PocketCodex w Ustawienia → Ułatwienia dostępu.")
                    .toString();
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return new JSONObject().put("ok", false).put("error", "Brak aktywnego okna.").toString();

        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        if (matches == null || matches.isEmpty()) {
            return new JSONObject().put("ok", false).put("error", "Nie znaleziono widocznego elementu z tekstem: " + text).toString();
        }

        for (AccessibilityNodeInfo node : matches) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return new JSONObject().put("ok", true).put("clicked_text", text).toString();
            }
        }
        return new JSONObject().put("ok", false).put("error", "Element znaleziony, ale nie udało się go kliknąć.").toString();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // MVP intentionally does not collect or store accessibility event contents.
    }

    @Override
    public void onInterrupt() {
    }
}
