package com.daedalus.animegf;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent "desktop mode" setting plus the pieces needed to apply it to the WebView:
 * a desktop user-agent string and the document-start script (assets/desktop_mode.js).
 *
 *  OFF       normal mobile site
 *  IDENTITY  mobile layout, but the site is told it is running in a desktop browser
 *  VIEWPORT  real wide desktop layout (virtual width), freely pinch-zoomable
 */
final class DesktopMode {

    static final int OFF      = 0;
    static final int IDENTITY = 1;
    static final int VIEWPORT = 2;

    /** Virtual widths matching common CSS breakpoints (md / lg / xl). */
    static final int[] WIDTHS = {800, 1024, 1280};
    static final int DEFAULT_WIDTH = 1024;

    private static final String PREFS_NAME = "animegf_prefs";
    private static final String KEY_MODE   = "desktop_mode";
    private static final String KEY_WIDTH  = "desktop_width";
    private static final String ASSET      = "desktop_mode.js";

    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)(\\.[\\d.]+)?");

    private static String cachedScript;

    private int mode;
    private int width;

    private DesktopMode(int mode, int width) {
        this.mode = mode;
        this.width = width;
    }

    static DesktopMode load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int mode = p.getInt(KEY_MODE, OFF);
        if (mode < OFF || mode > VIEWPORT) mode = OFF;
        return new DesktopMode(mode, sanitizeWidth(p.getInt(KEY_WIDTH, DEFAULT_WIDTH)));
    }

    void set(Context context, int mode, int width) {
        this.mode = mode;
        this.width = sanitizeWidth(width);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MODE, this.mode)
            .putInt(KEY_WIDTH, this.width)
            .apply();
    }

    int getMode()  { return mode; }
    int getWidth() { return width; }
    boolean isActive() { return mode != OFF; }

    /** Short text for the floating-menu label. */
    String label() {
        switch (mode) {
            case IDENTITY: return "Desktop identity · " + width;
            case VIEWPORT: return "Desktop view · " + width + "px";
            default:       return "Desktop · Off";
        }
    }

    private static int sanitizeWidth(int w) {
        for (int allowed : WIDTHS) if (allowed == w) return w;
        return DEFAULT_WIDTH;
    }

    // ─── WebView plumbing ─────────────────────────────────────────────────────

    /** Desktop Chrome user-agent that keeps the WebView's real Chrome version. */
    static String desktopUserAgent(String mobileUserAgent) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/" + chromeFullVersion(mobileUserAgent) + " Safari/537.36";
    }

    private static String chromeFullVersion(String ua) {
        Matcher m = ua == null ? null : CHROME_VERSION.matcher(ua);
        if (m != null && m.find()) {
            return m.group(1) + (m.group(2) != null ? m.group(2) : ".0.0.0");
        }
        return "124.0.0.0";
    }

    private static String chromeMajor(String ua) {
        Matcher m = ua == null ? null : CHROME_VERSION.matcher(ua);
        return (m != null && m.find()) ? m.group(1) : "124";
    }

    /** Full script (config prefix + asset) for the current setting, or null when OFF / unreadable. */
    String buildScript(Context context, String mobileUserAgent) throws IOException {
        if (!isActive()) return null;
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("mode", mode);
            cfg.put("width", width);
            cfg.put("chromeMajor", chromeMajor(mobileUserAgent));
            cfg.put("chromeFull", chromeFullVersion(mobileUserAgent));
        } catch (JSONException e) {
            throw new IOException("Could not build desktop mode config", e);
        }
        return "window.__AGF_DESKTOP_CFG__=" + cfg + ";\n" + readAsset(context);
    }

    private static synchronized String readAsset(Context context) throws IOException {
        if (cachedScript != null) return cachedScript;
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            cachedScript = out.toString(StandardCharsets.UTF_8.name());
        }
        return cachedScript;
    }
}
