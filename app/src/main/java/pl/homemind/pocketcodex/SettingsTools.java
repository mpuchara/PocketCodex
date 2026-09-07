package pl.homemind.pocketcodex;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.Settings;

import org.json.JSONObject;

public final class SettingsTools {
    private final Activity activity;

    public SettingsTools(Activity activity) {
        this.activity = activity;
    }

    public String getDeviceState() throws Exception {
        JSONObject out = new JSONObject();

        int brightness = Settings.System.getInt(
                activity.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                -1
        );
        out.put("brightness_0_255", brightness);
        if (brightness >= 0) out.put("brightness_percent", Math.round(brightness * 100f / 255f));
        out.put("can_write_system_settings", Settings.System.canWrite(activity));

        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        out.put("media_volume_percent", max == 0 ? 0 : Math.round(current * 100f / max));

        BatteryManager bm = (BatteryManager) activity.getSystemService(Context.BATTERY_SERVICE);
        out.put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        out.put("charging", bm.isCharging());
        out.put("ok", true);
        return out.toString();
    }

    public String setBrightness(int percent) throws Exception {
        percent = Math.max(0, Math.min(100, percent));
        if (!Settings.System.canWrite(activity)) {
            return new JSONObject()
                    .put("ok", false)
                    .put("permission_required", "WRITE_SETTINGS")
                    .put("message", "W aplikacji wybierz Uprawnienia → Zmiana ustawień systemowych i zezwól PocketCodex.")
                    .toString();
        }
        int value = Math.round(percent * 255f / 100f);
        boolean ok = Settings.System.putInt(
                activity.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                value
        );
        return new JSONObject().put("ok", ok).put("brightness_percent", percent).toString();
    }

    public String setMediaVolume(int percent) throws Exception {
        percent = Math.max(0, Math.min(100, percent));
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = Math.round(percent * max / 100f);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI);
        return new JSONObject().put("ok", true).put("media_volume_percent", percent).toString();
    }

    public String setFlashlight(boolean enabled) throws Exception {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return new JSONObject()
                    .put("ok", false)
                    .put("permission_required", "CAMERA")
                    .put("message", "W aplikacji wybierz Uprawnienia → Aparat / latarka.")
                    .toString();
        }

        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        String selected = null;
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                selected = cameraId;
                break;
            }
        }
        if (selected == null) {
            return new JSONObject().put("ok", false).put("error", "Nie znaleziono lampy błyskowej.").toString();
        }
        manager.setTorchMode(selected, enabled);
        return new JSONObject().put("ok", true).put("flashlight", enabled).toString();
    }

    public String openSettings(String panel) throws Exception {
        String action;
        switch (panel) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "accessibility": action = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "notifications": action = Settings.ACTION_NOTIFICATION_SETTINGS; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        activity.runOnUiThread(() -> activity.startActivity(new Intent(action)));
        return new JSONObject().put("ok", true).put("opened", panel).toString();
    }

    public void openWriteSettingsPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }
}
