package pl.homemind.pocketcodex;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PhotoTools {
    private static final int MAX_DECODE_DIMENSION = 4096;
    private final Activity activity;

    public PhotoTools(Activity activity) {
        this.activity = activity;
    }

    public String listRecent(int limit) throws Exception {
        if (!canReadPhotos()) return photoPermissionError();
        limit = Math.max(1, Math.min(20, limit));

        JSONArray photos = new JSONArray();
        ContentResolver resolver = activity.getContentResolver();
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
        };

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                int widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
                int heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);
                int count = 0;
                while (cursor.moveToNext() && count < limit) {
                    long id = cursor.getLong(idCol);
                    Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    photos.put(new JSONObject()
                            .put("name", cursor.getString(nameCol))
                            .put("uri", uri.toString())
                            .put("date_added_unix", cursor.getLong(dateCol))
                            .put("width", cursor.getInt(widthCol))
                            .put("height", cursor.getInt(heightCol)));
                    count++;
                }
            }
        }
        return new JSONObject().put("ok", true).put("photos", photos).toString();
    }

    public String rotateLatest(int degrees) throws Exception {
        if (!canReadPhotos()) return photoPermissionError();
        Uri source = latestPhotoUri();
        if (source == null) return new JSONObject().put("ok", false).put("error", "Brak dostępnych zdjęć.").toString();

        Bitmap bitmap = decodeSampled(source);
        if (bitmap == null) return new JSONObject().put("ok", false).put("error", "Nie udało się odczytać zdjęcia.").toString();

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        Uri output = saveJpeg(rotated, "rotated");

        if (rotated != bitmap) rotated.recycle();
        bitmap.recycle();

        return new JSONObject()
                .put("ok", output != null)
                .put("output_uri", output == null ? JSONObject.NULL : output.toString())
                .put("note", "Utworzono nową kopię; oryginał nie został zmieniony.")
                .toString();
    }

    public String grayscaleLatest() throws Exception {
        if (!canReadPhotos()) return photoPermissionError();
        Uri source = latestPhotoUri();
        if (source == null) return new JSONObject().put("ok", false).put("error", "Brak dostępnych zdjęć.").toString();

        Bitmap bitmap = decodeSampled(source);
        if (bitmap == null) return new JSONObject().put("ok", false).put("error", "Nie udało się odczytać zdjęcia.").toString();

        Bitmap gray = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(gray);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(bitmap, 0, 0, paint);

        Uri output = saveJpeg(gray, "grayscale");
        gray.recycle();
        bitmap.recycle();

        return new JSONObject()
                .put("ok", output != null)
                .put("output_uri", output == null ? JSONObject.NULL : output.toString())
                .put("note", "Utworzono nową kopię; oryginał nie został zmieniony.")
                .toString();
    }

    private boolean canReadPhotos() {
        if (Build.VERSION.SDK_INT >= 33) {
            return activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 34 && activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED);
        }
        return activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private String photoPermissionError() throws Exception {
        return new JSONObject()
                .put("ok", false)
                .put("permission_required", "PHOTOS")
                .put("message", "W aplikacji wybierz Uprawnienia → Zdjęcia i nadaj dostęp do zdjęć.")
                .toString();
    }

    private Uri latestPhotoUri() {
        ContentResolver resolver = activity.getContentResolver();
        String[] projection = new String[]{MediaStore.Images.Media._ID};
        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            }
        }
        return null;
    }

    private Bitmap decodeSampled(Uri uri) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }

        int sample = 1;
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        while (w / sample > MAX_DECODE_DIMENSION || h / sample > MAX_DECODE_DIMENSION) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private Uri saveJpeg(Bitmap bitmap, String suffix) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "PocketCodex_" + suffix + "_" + stamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketCodex");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;

        boolean ok = false;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            ok = out != null && bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        }
        if (!ok) {
            resolver.delete(uri, null, null);
            return null;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }
}
