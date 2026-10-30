package com.daedalus.animegf;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Network side of the image context menu: fetching a small preview and saving the full image
 * to the gallery (Pictures/AnimeGF). Requests carry the WebView's user agent and cookies so
 * images that need a logged-in session still download.
 */
final class ImageTools {

    private ImageTools() {}

    /** Everything needed to fetch one image the way the WebView would. */
    static final class Request {
        final String url;
        final String userAgent;
        final String cookie;
        final String referer;

        Request(String url, String userAgent, String cookie, String referer) {
            this.url = url;
            this.userAgent = userAgent;
            this.cookie = cookie;
            this.referer = referer;
        }
    }

    private static final String ALBUM = "AnimeGF";
    private static final int MAX_THUMBNAIL_BYTES = 12 * 1024 * 1024;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "agf-image-io");
        t.setDaemon(true);
        return t;
    });

    // ─── PREVIEW ──────────────────────────────────────────────────────────────

    /** Loads a preview no larger than about {@code targetPx}; {@code done} gets null on failure. */
    static void loadThumbnail(Activity activity, Request request, int targetPx,
                              Consumer<Bitmap> done) {
        IO.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection conn = null;
            try {
                conn = open(request, 4000, 8000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    byte[] data = readCapped(conn.getInputStream(), MAX_THUMBNAIL_BYTES);
                    if (data != null) bitmap = decodeSampled(data, targetPx);
                }
            } catch (IOException | RuntimeException ignored) {
                // preview is optional; the menu works without it
            } finally {
                if (conn != null) conn.disconnect();
            }
            final Bitmap result = bitmap;
            activity.runOnUiThread(() -> done.accept(result));
        });
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) {
                if (out.size() + n > cap) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Bitmap decodeSampled(byte[] data, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    // ─── SAVE ─────────────────────────────────────────────────────────────────

    /** Downloads the image and adds it to the gallery. Reports the outcome with a toast. */
    static void save(Activity activity, Request request) {
        Toast.makeText(activity, "Saving image…", Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(request, 10000, 20000);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    toast(activity, "Save failed: HTTP " + code);
                    return;
                }

                String mime = baseMimeType(conn.getContentType());
                if (!mime.startsWith("image/") && !mime.equals("application/octet-stream")) {
                    toast(activity, "Save failed: not an image");
                    return;
                }
                if (!mime.startsWith("image/")) mime = "image/jpeg";
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null) {
                    ext = "jpg";
                    mime = "image/jpeg";
                }
                String name = "AnimeGF_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                    + "." + ext;

                try (InputStream in = conn.getInputStream()) {
                    store(activity, in, name, mime);
                }
                toast(activity, "Saved to Pictures/" + ALBUM);
            } catch (IOException | RuntimeException e) {
                toast(activity, "Save failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void store(Activity activity, InputStream in, String name, String mime)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            storeWithMediaStore(activity, in, name, mime);
        } else {
            storeInPicturesFolder(activity, in, name, mime);
        }
    }

    /** Android 10+: no storage permission needed, the file is owned by the app. */
    private static void storeWithMediaStore(Activity activity, InputStream in, String name,
                                            String mime) throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Gallery is not available");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open gallery file");
            copy(in, out);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    /** Android 8 / 9: write into the public Pictures folder (needs WRITE_EXTERNAL_STORAGE). */
    private static void storeInPicturesFolder(Activity activity, InputStream in, String name,
                                              String mime) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES), ALBUM);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir.getName());
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(activity,
            new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
    }

    // ─── SHARED ───────────────────────────────────────────────────────────────

    private static HttpURLConnection open(Request request, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(request.url).openConnection();
        conn.setRequestProperty("User-Agent", request.userAgent);
        if (request.cookie != null) conn.setRequestProperty("Cookie", request.cookie);
        conn.setRequestProperty("Referer", request.referer);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        return conn;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** "image/png; charset=binary" → "image/png" (null → empty string). */
    private static String baseMimeType(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.US);
    }

    private static void toast(Activity activity, String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
