package com.daedalus.animegf;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // ─── CONFIG ───────────────────────────────────────────────────────────────
    private static final String TARGET_URL = "https://anime.gf";
    private static final String APP_NAME   = "AnimeGF";

    // Clean Chrome Mobile UA — no "wv" marker, indistinguishable from Chrome.
    private static final String CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    // ──────────────────────────────────────────────────────────────────────────

    private static final String TAG              = "AnimeGFApp";
    private static final int    REQ_STORAGE_PERM = 1001;

    // ─── VIEWS ────────────────────────────────────────────────────────────────
    private FrameLayout  rootLayout;     // full-screen host (changed from LinearLayout)
    private LinearLayout contentLayout;  // inner stack for progress/banner/webview
    private SelectionWebView webView;
    private ProgressBar  progressBar;
    private TextView     offlineBanner;

    // ─── FILE CHOOSER ─────────────────────────────────────────────────────────
    private ValueCallback<Uri[]>           fileChooserCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    // ─── FLOATING MENU / SPLASH / SNIPPETS / DESKTOP MODE ─────────────────────
    private FloatingMenu floatingMenu;
    private SplashView   splashView;
    private SnippetStore snippetStore;
    private DesktopMode  desktopMode;
    private PageInteraction pageInteraction;

    private static final long SPLASH_TIMEOUT_MS = 15000L;
    private final Handler splashHandler = new Handler(Looper.getMainLooper());

    // User agent the WebView would use for the normal mobile site.
    private String mobileUserAgent;
    // Desktop-mode script + how it is delivered (document-start when supported).
    private String        desktopScript;
    private ScriptHandler desktopScriptHandler;
    private static final Set<String> SITE_SCRIPT_ORIGINS = new HashSet<>(
        Arrays.asList("https://anime.gf", "https://www.anime.gf", "https://*.anime.gf"));
    // ──────────────────────────────────────────────────────────────────────────

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        WindowInsetsControllerCompat ctrl =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctrl.setAppearanceLightStatusBars(false);
        ctrl.setAppearanceLightNavigationBars(false);

        setContentView(R.layout.activity_main);

        rootLayout    = findViewById(R.id.rootLayout);
        contentLayout = findViewById(R.id.contentLayout);
        progressBar   = findViewById(R.id.progressBar);
        offlineBanner = findViewById(R.id.offlineBanner);
        webView       = findViewById(R.id.webView);
        splashView    = findViewById(R.id.splashView);
        snippetStore  = new SnippetStore(this);
        desktopMode   = DesktopMode.load(this);

        // Apply window insets to the *inner* content layout so the FAB
        // (which is also in rootLayout) can float freely edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() |
                WindowInsetsCompat.Type.displayCutout()
            );
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            int bottomPadding = Math.max(bars.bottom, ime.bottom);
            contentLayout.setPadding(bars.left, bars.top, bars.right, bottomPadding);
            if (floatingMenu != null) {
                floatingMenu.setSafeInsets(bars.left, bars.top, bars.right, bars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        setupFileChooserLauncher();
        setupWebView();
        setupFloatingMenu();
        setupPageInteraction();
        applyDesktopMode(false);   // before the first load so the site sees the right identity
        requestStoragePermissionIfNeeded();
        splashHandler.postDelayed(this::dismissSplash, SPLASH_TIMEOUT_MS);

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            if (isOnline()) {
                webView.loadUrl(intent.getData().toString());
            } else {
                showOfflineBanner(true);
                dismissSplash();
            }
        } else {
            if (isOnline()) {
                webView.loadUrl(TARGET_URL);
            } else {
                showOfflineBanner(true);
                dismissSplash();
            }
        }
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        pageInteraction.hideToolbar();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (floatingMenu != null) floatingMenu.savePosition();
    }

    @Override
    protected void onDestroy() {
        splashHandler.removeCallbacksAndMessages(null);
        if (pageInteraction != null) pageInteraction.destroy();
        if (desktopScriptHandler != null) {
            try { desktopScriptHandler.remove(); } catch (Exception ignored) { }
        }
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && floatingMenu != null && floatingMenu.isOpen()) {
            floatingMenu.collapse(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && pageInteraction.dismissSelection()) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ─── PERMISSIONS ──────────────────────────────────────────────────────────

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(req, p, g);
    }

    // ─── FILE CHOOSER ─────────────────────────────────────────────────────────

    private void setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (fileChooserCallback == null) return;
                Uri[] uris = null;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        uris = new Uri[count];
                        for (int i = 0; i < count; i++)
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileChooserCallback.onReceiveValue(uris);
                fileChooserCallback = null;
            }
        );
    }

    // ─── WEBVIEW ──────────────────────────────────────────────────────────────

    private void setupWebView() {
        applyWebViewSettings(webView, false /* keep site UA */);
        mobileUserAgent = webView.getSettings().getUserAgentString();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
 
                if (isGoogleAuthUrl(url)) {
                    openOAuthOverlay(url);
                    return true;
                }

                if (isDiscordAuthUrl(url)) {
                    openOAuthOverlay(url);
                    return true;
                }

                if (isAllowedInApp(url)) {
                    return false; 
                }

                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Log.w(TAG, "Unhandled URL: " + url); }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                showOfflineBanner(false);
                if (splashView != null) splashView.setStep("Fetching configuration...");
                pageInteraction.onPageStarted(view, url);
                injectDesktopScriptFallback(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                pageInteraction.injectFallback(view, url);
                injectDesktopScriptFallback(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectBlobDownloadBridge(view);
                pageInteraction.injectFallback(view, url);
                injectDesktopScriptFallback(view, url);
                dismissSplash();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    showOfflineBanner(!isOnline());
                    dismissSplash();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                if (splashView != null) splashView.setProgress(newProgress / 100f);
                if (splashView != null) {
                    splashView.setStep(newProgress < 35
                        ? "Loading services..."
                        : newProgress < 85 ? "Initializing modules..." : "Finishing setup...");
                }
            }

            @Override
            public boolean onShowFileChooser(WebView wv,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                }
                fileChooserCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file chooser",
                        Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("blob:")) return;
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimetype);
                req.addRequestHeader("User-Agent", userAgent);
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                req.setTitle(fileName);
                req.setDescription("Downloading " + fileName);
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                req.allowScanningByMediaScanner();
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "Downloading " + fileName + " …",
                        Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        });

        webView.addJavascriptInterface(new BlobDownloadInterface(), "AndroidBridge");
    }

    // ─── SPLASH ───────────────────────────────────────────────────────────────

    /** Complete the splash ring, fade it out and remove it. Safe to call repeatedly. */
    private void dismissSplash() {
        final SplashView splash = splashView;
        if (splash == null) return;
        splash.finish(() -> {
            rootLayout.removeView(splash);
            if (splashView == splash) splashView = null;
        });
    }

    // ─── FLOATING MENU ────────────────────────────────────────────────────────

    /**
     * Round, draggable menu button that expands into Reload / Snippets / Desktop mode.
     * Its views are inserted just below the splash so the splash covers them at launch.
     */
    private void setupFloatingMenu() {
        int insertIndex = splashView != null
            ? rootLayout.indexOfChild(splashView)
            : rootLayout.getChildCount();
        floatingMenu = new FloatingMenu(this, rootLayout, insertIndex, new FloatingMenu.Listener() {
            @Override public void onReload()   { reloadPage(); }
            @Override public void onSnippets() { new SnippetsDialog(MainActivity.this, snippetStore).show(); }
            @Override public void onDesktop()  { showDesktopDialog(); }
        });
    }

    /**
     * Long-press menu for images / links and the app's own text-selection toolbar. Its toolbar
     * view goes just below the splash, like the floating menu.
     */
    private void setupPageInteraction() {
        int insertIndex = splashView != null
            ? rootLayout.indexOfChild(splashView)
            : rootLayout.getChildCount();
        pageInteraction = new PageInteraction(this, webView, rootLayout, insertIndex, SITE_SCRIPT_ORIGINS);
        pageInteraction.install();
    }

    /** Reload whatever page is showing (or the home page if nothing loaded yet). */
    private void reloadPage() {
        String currentUrl = webView.getUrl();
        if (currentUrl != null && !currentUrl.isEmpty() && !currentUrl.startsWith("about:")) {
            webView.loadUrl(currentUrl);
        } else if (isOnline()) {
            showOfflineBanner(false);
            webView.loadUrl(TARGET_URL);
        } else {
            showOfflineBanner(true);
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── DESKTOP MODE ─────────────────────────────────────────────────────────

    private void showDesktopDialog() {
        new DesktopDialog(this, desktopMode, (mode, width) -> {
            desktopMode.set(this, mode, width);
            applyDesktopMode(true);
        }).show();
    }

    /**
     * Applies the saved desktop-mode setting to the WebView: user agent, zoom behaviour and
     * the page script (registered to run before any site script when the WebView supports it).
     * A reload is needed for the site to see the change.
     */
    private void applyDesktopMode(boolean reload) {
        WebSettings s = webView.getSettings();
        boolean active = desktopMode.isActive();

        s.setUserAgentString(active
            ? DesktopMode.desktopUserAgent(mobileUserAgent)
            : mobileUserAgent);

        // Free pinch-zoom only in the real wide-viewport mode.
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(desktopMode.getMode() == DesktopMode.VIEWPORT);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        if (desktopScriptHandler != null) {
            try { desktopScriptHandler.remove(); } catch (Exception ignored) { }
            desktopScriptHandler = null;
        }
        desktopScript = null;

        if (active) {
            try {
                desktopScript = desktopMode.buildScript(this, mobileUserAgent);
            } catch (IOException e) {
                Log.e(TAG, "Desktop mode script unavailable", e);
                Toast.makeText(this, "Desktop mode failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
            if (desktopScript != null
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    desktopScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                        webView, desktopScript, SITE_SCRIPT_ORIGINS);
                } catch (Exception e) {
                    Log.w(TAG, "Document-start script rejected, using fallback", e);
                }
            }
        }

        if (floatingMenu != null) floatingMenu.setDesktopState(active, desktopMode.label());
        if (reload) reloadPage();
    }

    /**
     * Fallback for WebViews without document-start scripts: inject as early as the
     * WebViewClient callbacks allow. The script is idempotent, so repeated calls are fine.
     */
    private void injectDesktopScriptFallback(WebView view, String url) {
        if (desktopScript == null || desktopScriptHandler != null || url == null) return;
        if (!url.contains("anime.gf")) return;
        view.evaluateJavascript(desktopScript, null);
    }

    // ─── OAUTH OVERLAY ────────────────────────────────────────────────────────

    /**
     * Opens a full-screen dialog containing a WebView configured with a clean
     * Chrome Mobile user agent (no "wv" marker). Google's OAuth happily
     * accepts this UA and allows the login to proceed.
     */
    private void openOAuthOverlay(String url) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            );
        }

        WebView oauthWebView = new WebView(this);
        applyWebViewSettings(oauthWebView, true /* use clean Chrome UA */);

        oauthWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String redirectUrl = request.getUrl().toString();

                if (isGoogleAuthUrl(redirectUrl) || isDiscordAuthUrl(redirectUrl)) return false;

                dialog.dismiss();
                oauthWebView.destroy();
                webView.loadUrl(redirectUrl);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Nothing extra needed
            }
        });

        oauthWebView.loadUrl(url);
        dialog.setContentView(oauthWebView);
        dialog.setOnDismissListener(d -> {
            try { oauthWebView.destroy(); } catch (Exception ignored) {}
        });
        dialog.show();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private void applyWebViewSettings(WebView wv, boolean useCleanUA) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);

        if (useCleanUA) {
            s.setUserAgentString(CHROME_MOBILE_UA);
        } else {
            String ua = s.getUserAgentString()
                .replaceAll("\\s*\\(wv\\)\\s*", " ")
                .replaceAll("\\s+wv\\s+", " ")
                .replaceAll("Version/[\\d.]+\\s*", "")
                .trim();
            s.setUserAgentString(ua);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
    }

    private boolean isGoogleAuthUrl(String url) {
        return url.contains("accounts.google.com")
            || url.contains("google.com/o/oauth2")
            || url.contains("oauth2.googleapis.com")
            || url.contains("google.com/accounts")
            || (url.contains("supabase.co") && url.contains("provider=google"));
    }

    private boolean isDiscordAuthUrl(String url) {
        return url.contains("discord.com/oauth2")
            || url.contains("discord.com/api/oauth2")
            || (url.contains("supabase.co") && url.contains("provider=discord"));
    }

    private boolean isAllowedInApp(String url) {
        return url.contains("anime.gf")
            || url.contains("supabase.co")
            || url.contains("accounts.google.com")
            || url.contains("google.com/o/oauth2")
            || url.contains("oauth2.googleapis.com")
            || url.contains("discord.com/oauth2")
            || url.contains("discord.com/api/oauth2");
    }

    private boolean isOnline() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void showOfflineBanner(boolean show) {
        offlineBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void injectBlobDownloadBridge(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "  if(window.__blobPatched)return;" +
            "  window.__blobPatched=true;" +
            "  document.addEventListener('click',function(e){" +
            "    var el=e.target;" +
            "    for(var i=0;i<6&&el;i++,el=el.parentElement){" +
            "      if(el.tagName==='A'&&el.href&&el.href.startsWith('blob:')){" +
            "        e.preventDefault();" +
            "        var fname=el.download||'download';" +
            "        var xhr=new XMLHttpRequest();" +
            "        xhr.open('GET',el.href,true);" +
            "        xhr.responseType='blob';" +
            "        xhr.onload=function(){" +
            "          var reader=new FileReader();" +
            "          reader.onloadend=function(){" +
            "            var b64=reader.result.split(',')[1];" +
            "            AndroidBridge.receiveBlobDownload(b64,fname,xhr.response.type);" +
            "          };" +
            "          reader.readAsDataURL(xhr.response);" +
            "        };" +
            "        xhr.send();return;" +
            "      }" +
            "    }" +
            "  },true);" +
            "})();",
            null
        );
    }

    // ─── BLOB DOWNLOAD BRIDGE ─────────────────────────────────────────────────

    private class BlobDownloadInterface {

        @JavascriptInterface
        public void receiveBlobDownload(final String base64Data,
                                        final String fileName,
                                        final String mimeType) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    String safeName = sanitizeFileName(fileName, mimeType);
                    File dir = Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(bytes);
                    }
                    MediaScannerConnection.scanFile(MainActivity.this,
                        new String[]{out.getAbsolutePath()}, new String[]{mimeType}, null);
                    Toast.makeText(MainActivity.this,
                        "Saved: " + safeName, Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Toast.makeText(MainActivity.this,
                        "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        private String sanitizeFileName(String name, String mimeType) {
            if (name == null || name.isEmpty()) {
                name = APP_NAME + "_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            }
            name = name.replaceAll("[/\\\\:*?\"<>|]", "_");
            if (!name.contains(".")) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (ext != null && !ext.isEmpty()) name += "." + ext;
            }
            return name;
        }
    }
}
