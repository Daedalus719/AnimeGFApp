package com.daedalus.animegf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything the app does on top of the page's touches:
 *
 *  - Long-press menu: the page script (assets/page_interaction.js) reports what is under the
 *    finger; only a real image or a real link opens {@link ContextMenuDialog}. Anything else
 *    (buttons, modal triggers, "#" links, plain text) falls through to normal behaviour.
 *  - Text selection: the system toolbar is hidden by {@link SelectionWebView}; this class
 *    shows {@link SelectionToolbar} instead, driven by the selection state the page reports.
 */
final class PageInteraction implements SelectionToolbar.Listener {

    private static final String TAG = "PageInteraction";

    private static final String BRIDGE_NAME  = "AgfPage";
    private static final String SCRIPT_ASSET = "page_interaction.js";

    /** Delay between lifting the finger and asking the page for the final selection. */
    private static final long REPORT_DELAY_MS = 120L;
    /** A selection change this soon after a touch counts as the user's doing (not the site's). */
    private static final long USER_SELECTION_WINDOW_MS = 1200L;
    private static final int  MAX_LOOKUP_CHARS = 1500;

    private static final String JS_REPORT     = "window.__agfPage&&window.__agfPage.report()";
    private static final String JS_TEXT       = "window.__agfPage?window.__agfPage.text():''";
    private static final String JS_CUT        = "window.__agfPage?window.__agfPage.cut():''";
    private static final String JS_SELECT_ALL = "window.__agfPage&&window.__agfPage.selectAll()";
    private static final String JS_COLLAPSE   = "window.__agfPage&&window.__agfPage.collapse()";
    private static final String JS_CLOSE_SITE_MENU = "window.__agfPage&&window.__agfPage.closeSiteMenu()";

    private final Activity activity;
    private final SelectionWebView webView;
    private final FrameLayout host;
    private final Set<String> scriptOrigins;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SelectionToolbar toolbar;
    private final Runnable requestReport;

    private String script;
    private ScriptHandler scriptHandler;

    /** What was under the finger on the latest touch (written by the JS bridge thread). */
    private volatile TouchTarget touchTarget = TouchTarget.NONE;

    private Selection selection;
    private boolean touching;
    private boolean userSelection;     // current selection was made by the user, not by the site
    private boolean caretRequested;    // long-press in an editable field: show Paste
    private long lastTouchEnd;

    /**
     * @param toolbarIndex child position in {@code host} for the toolbar (below the splash)
     * @param scriptOrigins origins the page script is registered for at document start
     */
    PageInteraction(Activity activity, SelectionWebView webView, FrameLayout host,
                    int toolbarIndex, Set<String> scriptOrigins) {
        this.activity = activity;
        this.webView = webView;
        this.host = host;
        this.scriptOrigins = scriptOrigins;
        this.toolbar = new SelectionToolbar(activity, host, toolbarIndex, this);
        this.requestReport = () -> webView.evaluateJavascript(JS_REPORT, null);
    }

    // ─── SETUP / LIFECYCLE ────────────────────────────────────────────────────

    /** Connects the bridge and listeners and registers the page script. Call before the first load. */
    void install() {
        webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
        webView.setOnLongClickListener(v -> onLongPress());
        webView.setTouchStateListener(this::onTouchStateChanged);

        try {
            script = readScript();
        } catch (IOException e) {
            Log.e(TAG, "Page script unavailable", e);
            Toast.makeText(activity, "Link menu and selection toolbar unavailable: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, scriptOrigins);
            } catch (Exception e) {
                Log.w(TAG, "Document-start script rejected, using fallback", e);
            }
        }
    }

    void onPageStarted(WebView view, String url) {
        resetState();
        injectFallback(view, url);
    }

    /** Fallback for WebViews without document-start scripts. The script is idempotent. */
    void injectFallback(WebView view, String url) {
        if (script == null || scriptHandler != null || url == null) return;
        if (!url.contains("anime.gf")) return;
        view.evaluateJavascript(script, null);
    }

    void hideToolbar() {
        toolbar.hide();
    }

    /**
     * Back button while the selection toolbar is up: dismiss it and the selection, like the
     * system toolbar did. Returns true when the key press was used.
     */
    boolean dismissSelection() {
        if (!toolbar.isShowing()) return false;
        toolbar.hide();
        webView.evaluateJavascript(JS_COLLAPSE, null);
        return true;
    }

    void destroy() {
        ui.removeCallbacksAndMessages(null);
        if (scriptHandler != null) {
            try { scriptHandler.remove(); } catch (Exception ignored) { }
            scriptHandler = null;
        }
    }

    private void resetState() {
        touchTarget = TouchTarget.NONE;
        selection = null;
        userSelection = false;
        caretRequested = false;
        toolbar.hide();
    }

    private String readScript() throws IOException {
        try (InputStream in = activity.getAssets().open(SCRIPT_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ─── TOUCH ────────────────────────────────────────────────────────────────

    private void onTouchStateChanged(boolean down) {
        touching = down;
        ui.removeCallbacks(requestReport);
        if (down) {
            touchTarget = TouchTarget.NONE;
            caretRequested = false;
            toolbar.hide();
        } else {
            lastTouchEnd = SystemClock.uptimeMillis();
            ui.postDelayed(requestReport, REPORT_DELAY_MS);
        }
    }

    /** Long-press: our menu for real images / links, otherwise let the WebView select text as usual. */
    private boolean onLongPress() {
        TouchTarget t = touchTarget;
        if (t.editable) {
            caretRequested = true;
            return false;
        }
        if (!t.hasMenu()) return false;
        toolbar.hide();
        showContextMenu(t);
        if (t.siteMenu) closeSiteMenuSoon();
        return true;
    }

    // ─── SELECTION STATE ──────────────────────────────────────────────────────

    private void onSelectionBusy() {
        if (touching || SystemClock.uptimeMillis() - lastTouchEnd <= USER_SELECTION_WINDOW_MS) {
            userSelection = true;
        }
        toolbar.hide();
    }

    private void onSelection(Selection s) {
        selection = s;
        if (s == null || !s.hasText) userSelection = false;
        refreshToolbar();
    }

    private void refreshToolbar() {
        Selection s = selection;
        if (touching || s == null) {
            toolbar.hide();
            return;
        }
        boolean forSelection = s.hasText && userSelection;
        boolean forCaret = !s.hasText && s.editable && caretRequested;
        if (!forSelection && !forCaret) {
            toolbar.hide();
            return;
        }

        int actions = 0;
        if (s.hasText && !s.secret) {
            actions |= SelectionToolbar.COPY | SelectionToolbar.SEARCH | SelectionToolbar.TRANSLATE;
            if (s.editable) actions |= SelectionToolbar.CUT;
        }
        if (s.editable && clipboardHasText()) actions |= SelectionToolbar.PASTE;
        if (!(s.editable && s.empty)) actions |= SelectionToolbar.SELECT_ALL;

        int[] webViewPos = new int[2];
        int[] hostPos = new int[2];
        webView.getLocationInWindow(webViewPos);
        host.getLocationInWindow(hostPos);
        float dx = webViewPos[0] - hostPos[0];
        float dy = webViewPos[1] - hostPos[1];

        RectF anchor = new RectF(s.rect.left + dx, s.rect.top + dy, s.rect.right + dx, s.rect.bottom + dy);
        RectF bounds = new RectF(dx, dy, dx + webView.getWidth(), dy + webView.getHeight());
        toolbar.show(anchor, bounds, actions);
    }

    // ─── TOOLBAR ACTIONS ──────────────────────────────────────────────────────

    @Override
    public void onAction(int action) {
        toolbar.hide();
        switch (action) {
            case SelectionToolbar.COPY:
                evaluateText(JS_TEXT, text -> {
                    copyText(text);
                    webView.evaluateJavascript(JS_COLLAPSE, null);
                });
                break;
            case SelectionToolbar.CUT:
                evaluateText(JS_CUT, this::copyText);
                break;
            case SelectionToolbar.PASTE:
                pasteFromClipboard();
                break;
            case SelectionToolbar.SELECT_ALL:
                userSelection = true;
                webView.evaluateJavascript(JS_SELECT_ALL, null);
                break;
            case SelectionToolbar.SEARCH:
                evaluateText(JS_TEXT, text -> lookUp(text, query ->
                    "https://www.google.com/search?q=" + Uri.encode(query)));
                break;
            case SelectionToolbar.TRANSLATE:
                evaluateText(JS_TEXT, text -> lookUp(text, query ->
                    "https://translate.google.com/?sl=auto&tl=" + Uri.encode(deviceLanguage())
                        + "&text=" + Uri.encode(query) + "&op=translate"));
                break;
            default:
                break;
        }
    }

    /** Runs {@code js} in the page and hands its string result to {@code done} (UI thread). */
    private void evaluateText(String js, Consumer<String> done) {
        webView.evaluateJavascript(js, raw -> done.accept(decodeJsString(raw)));
    }

    /** evaluateJavascript returns JSON: "\"text\"" for strings, "null" for nothing. */
    private static String decodeJsString(String raw) {
        if (raw == null || raw.equals("null")) return "";
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value instanceof String ? (String) value : "";
        } catch (JSONException e) {
            return "";
        }
    }

    private void lookUp(String text, Function<String, String> urlFor) {
        String query = text.replaceAll("\\s+", " ").trim();
        if (query.isEmpty()) return;
        if (query.length() > MAX_LOOKUP_CHARS) query = query.substring(0, MAX_LOOKUP_CHARS);
        openExternal(urlFor.apply(query));
        webView.evaluateJavascript(JS_COLLAPSE, null);
    }

    private static String deviceLanguage() {
        String language = Locale.getDefault().getLanguage();
        return language.isEmpty() ? "en" : language;
    }

    // ─── CLIPBOARD ────────────────────────────────────────────────────────────

    private ClipboardManager clipboard() {
        return (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private boolean clipboardHasText() {
        ClipboardManager cm = clipboard();
        if (cm == null || !cm.hasPrimaryClip()) return false;
        ClipDescription description = cm.getPrimaryClipDescription();
        return description != null
            && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML));
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = clipboard();
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text == null || text.length() == 0) return;
        webView.evaluateJavascript(
            "window.__agfPage&&window.__agfPage.paste(" + JSONObject.quote(text.toString()) + ")", null);
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = clipboard();
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Text", text));
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── CONTEXT MENU ─────────────────────────────────────────────────────────

    private void showContextMenu(TouchTarget t) {
        String image = t.imageUrl;
        String link = t.linkUrl;
        String primary = image != null ? image : link;

        ContextMenuDialog menu = new ContextMenuDialog(activity, hostOf(primary), primary,
            R.drawable.ic_link, image != null ? imageRequest(image) : null);

        if (image != null) {
            menu.addAction(R.drawable.ic_download, "Save image", Ui.GREEN,
                    () -> ImageTools.save(activity, imageRequest(image)))
                .addAction(R.drawable.ic_link, "Copy image URL", Ui.BLUE,
                    () -> copyText(image))
                .addAction(R.drawable.ic_image_search, "Search with Google Lens", Ui.CYAN,
                    () -> webView.loadUrl("https://lens.google.com/uploadbyurl?url=" + Uri.encode(image)));
        }
        if (link != null) {
            if (image != null) menu.addDivider();
            menu.addAction(R.drawable.ic_open_external, openLinkLabel(link), Ui.BLUE,
                    () -> openExternal(link))
                .addAction(R.drawable.ic_copy, "Copy link address", Ui.GREEN,
                    () -> copyText(link));
        }
        menu.show();
    }

    /** The site opens its own menu on the same long-press, a moment before or after ours; close it. */
    private void closeSiteMenuSoon() {
        for (long delay : new long[]{150L, 600L}) {
            ui.postDelayed(() -> webView.evaluateJavascript(JS_CLOSE_SITE_MENU, null), delay);
        }
    }

    private ImageTools.Request imageRequest(String url) {
        return new ImageTools.Request(url, webView.getSettings().getUserAgentString(),
            CookieManager.getInstance().getCookie(url), "https://anime.gf/");
    }

    private static String openLinkLabel(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("mailto:")) return "Send email";
        if (lower.startsWith("tel:")) return "Call number";
        return "Open link in browser";
    }

    private static String hostOf(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host != null && !host.isEmpty()) return host;
        String part = uri.getSchemeSpecificPart();
        return part != null && !part.isEmpty() ? part : url;
    }

    private void openExternal(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app can open this", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── JS BRIDGE ────────────────────────────────────────────────────────────

    /** Called from the page; these run on a WebView background thread. */
    private final class Bridge {

        @JavascriptInterface
        public void onTouchTarget(String json) {
            touchTarget = TouchTarget.parse(json);
        }

        @JavascriptInterface
        public void onSelectionBusy() {
            ui.post(PageInteraction.this::onSelectionBusy);
        }

        @JavascriptInterface
        public void onSelection(String json) {
            final Selection parsed = Selection.parse(json);
            ui.post(() -> PageInteraction.this.onSelection(parsed));
        }
    }

    // ─── DATA ─────────────────────────────────────────────────────────────────

    /** What a touch landed on, as classified by the page script. */
    private static final class TouchTarget {
        static final TouchTarget NONE = new TouchTarget(null, null, false, false);

        final String imageUrl;
        final String linkUrl;
        final boolean editable;
        final boolean siteMenu;   // inside an element the site gives its own long-press menu

        private TouchTarget(String imageUrl, String linkUrl, boolean editable, boolean siteMenu) {
            this.imageUrl = imageUrl;
            this.linkUrl = linkUrl;
            this.editable = editable;
            this.siteMenu = siteMenu;
        }

        boolean hasMenu() {
            return imageUrl != null || linkUrl != null;
        }

        static TouchTarget parse(String json) {
            if (json == null) return NONE;
            try {
                JSONObject o = new JSONObject(json);
                String image = o.isNull("i") ? null : o.getString("i");
                String link = o.isNull("l") ? null : o.getString("l");
                // The page is not trusted: re-check the schemes before they reach any menu action.
                if (image != null && !isWebUrl(image)) image = null;
                if (link != null && !isWebUrl(link) && !isMailOrPhone(link)) link = null;
                boolean editable = o.optInt("e") == 1;
                if (image == null && link == null && !editable) return NONE;
                return new TouchTarget(image, link, editable, o.optInt("s") == 1);
            } catch (JSONException e) {
                return NONE;
            }
        }

        private static boolean isWebUrl(String url) {
            String lower = url.toLowerCase(Locale.US);
            return lower.startsWith("https://") || lower.startsWith("http://");
        }

        private static boolean isMailOrPhone(String url) {
            String lower = url.toLowerCase(Locale.US);
            return lower.startsWith("mailto:") || lower.startsWith("tel:");
        }
    }

    /** The page's current text selection (or caret in an editable field). */
    private static final class Selection {
        final boolean hasText;
        final boolean editable;
        final boolean secret;   // password field: never offer copy / cut / lookup
        final boolean empty;    // editable field without content
        final RectF rect;       // WebView pixels

        private Selection(boolean hasText, boolean editable, boolean secret, boolean empty, RectF rect) {
            this.hasText = hasText;
            this.editable = editable;
            this.secret = secret;
            this.empty = empty;
            this.rect = rect;
        }

        /** Null for the empty string the page sends when nothing is selected. */
        static Selection parse(String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                JSONObject o = new JSONObject(json);
                RectF rect = new RectF(
                    (float) o.getDouble("l"), (float) o.getDouble("t"),
                    (float) o.getDouble("r"), (float) o.getDouble("b"));
                if (Float.isNaN(rect.left) || Float.isNaN(rect.top)
                        || Float.isNaN(rect.right) || Float.isNaN(rect.bottom)) {
                    return null;
                }
                return new Selection(o.optInt("s") == 1, o.optInt("e") == 1,
                    o.optInt("p") == 1, o.optInt("z") == 1, rect);
            } catch (JSONException e) {
                return null;
            }
        }
    }
}
