package com.daedalus.animegf;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
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
import android.widget.Button;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    private WebView      webView;
    private ProgressBar  progressBar;
    private TextView     offlineBanner;

    // ─── FILE CHOOSER ─────────────────────────────────────────────────────────
    private ValueCallback<Uri[]>           fileChooserCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    // ─── FLOATING REFRESH BUTTON ──────────────────────────────────────────────
    private Button   refreshFab;
    private int      fabSizePx;
    private int      dragThresholdPx;

    // Drag tracking
    private float   touchDownRawX, touchDownRawY;
    private float   fabDownX, fabDownY;
    private boolean isDragging = false;

    // Double-tap state
    private boolean waitingForSecondTap = false;
    private long    firstTapTime        = 0L;
    private static final long DOUBLE_TAP_MAX_GAP = 1000L; // ms

    // Reset timer for "primed" visual after first tap
    private final Handler  fabResetHandler  = new Handler(Looper.getMainLooper());
    private       Runnable fabResetRunnable = null;

    // Shared preferences keys
    private static final String PREFS_NAME = "animegf_prefs";
    private static final String PREF_FAB_X = "fab_x_v2";
    private static final String PREF_FAB_Y = "fab_y_v2";

    // Button colors
    private static final int COLOR_FAB_NORMAL  = 0xCC4A4A8A; // muted indigo, semi-transparent
    private static final int COLOR_FAB_PRIMED  = 0xEEE64A19; // vivid orange-red
    private static final int COLOR_FAB_TEXT    = 0xFFFFFFFF;
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
            return WindowInsetsCompat.CONSUMED;
        });

        setupFileChooserLauncher();
        setupWebView();
        setupRefreshFab();
        requestStoragePermissionIfNeeded();

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            if (isOnline()) {
                webView.loadUrl(intent.getData().toString());
            } else {
                showOfflineBanner(true);
            }
        } else {
            if (isOnline()) {
                webView.loadUrl(TARGET_URL);
            } else {
                showOfflineBanner(true);
            }
        }
    }

    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onPause()  { super.onPause();  webView.onPause();  }

    @Override
    protected void onStop() {
        super.onStop();
        saveFabPosition();
    }

    @Override
    protected void onDestroy() {
        fabResetHandler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectBlobDownloadBridge(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    showOfflineBanner(!isOnline());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
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

    // ─── FLOATING REFRESH BUTTON ──────────────────────────────────────────────

    /**
     * Creates a small floating circular button that the user can drag anywhere
     * on screen. Requires a double-tap (within 1 second) to actually refresh
     * to avoid accidental reloads while repositioning.
     *
     *  • Single tap  → button turns orange with a slight scale-up ("primed")
     *  • Second tap within 1 s → refreshes the current URL, button resets
     *  • Timeout (1 s after first tap) → button resets to normal automatically
     *  • Position is persisted in SharedPreferences across app restarts.
     */
    private void setupRefreshFab() {
        float density = getResources().getDisplayMetrics().density;
        fabSizePx       = Math.round(52 * density);
        dragThresholdPx = Math.round(8  * density);

        refreshFab = new Button(this);
        refreshFab.setText("↻");
        refreshFab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        refreshFab.setTextColor(COLOR_FAB_TEXT);
        refreshFab.setIncludeFontPadding(false);
        refreshFab.setGravity(Gravity.CENTER);
        refreshFab.setPadding(0, 0, 0, 0);
        refreshFab.setAllCaps(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            refreshFab.setElevation(6 * density);
        }

        applyFabBackground(false /* not primed */);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fabSizePx, fabSizePx);
        rootLayout.addView(refreshFab, lp);

        // Restore saved position once the layout has measured itself.
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    restoreFabPosition();
                }
            }
        );

        refreshFab.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    touchDownRawX = event.getRawX();
                    touchDownRawY = event.getRawY();
                    fabDownX      = refreshFab.getX();
                    fabDownY      = refreshFab.getY();
                    isDragging    = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dxMove = event.getRawX() - touchDownRawX;
                    float dyMove = event.getRawY() - touchDownRawY;
                    if (!isDragging &&
                        (Math.abs(dxMove) > dragThresholdPx ||
                         Math.abs(dyMove) > dragThresholdPx)) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        refreshFab.setX(clampFabX(fabDownX + dxMove));
                        refreshFab.setY(clampFabY(fabDownY + dyMove));
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isDragging) {
                        handleFabTap();
                    }
                    break;
            }
            return true; // consume — don't let touches pass through to the WebView
        });
    }

    /** Handle a confirmed tap (not a drag) on the refresh FAB. */
    private void handleFabTap() {
        long now = SystemClock.elapsedRealtime();

        if (waitingForSecondTap && (now - firstTapTime) <= DOUBLE_TAP_MAX_GAP) {
            // ── Second tap: perform the reload ────────────────────────────────
            waitingForSecondTap = false;
            if (fabResetRunnable != null) {
                fabResetHandler.removeCallbacks(fabResetRunnable);
                fabResetRunnable = null;
            }
            // Brief "flash" scale to give tactile feedback, then reset
            refreshFab.animate().cancel();
            refreshFab.animate()
                .scaleX(1.25f).scaleY(1.25f)
                .setDuration(80)
                .withEndAction(() ->
                    refreshFab.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(150)
                        .start()
                ).start();
            applyFabBackground(false);

            // Reload the currently displayed URL (not the hard-coded home URL)
            String currentUrl = webView.getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                webView.loadUrl(currentUrl);
            } else {
                webView.reload();
            }

        } else {
            // ── First tap: prime the button ───────────────────────────────────
            firstTapTime        = now;
            waitingForSecondTap = true;
            applyFabBackground(true);
            refreshFab.animate().cancel();
            refreshFab.animate()
                .scaleX(1.18f).scaleY(1.18f)
                .setDuration(120)
                .start();

            // Auto-reset if user doesn't tap again within the window
            if (fabResetRunnable != null) fabResetHandler.removeCallbacks(fabResetRunnable);
            fabResetRunnable = () -> {
                waitingForSecondTap = false;
                applyFabBackground(false);
                refreshFab.animate().cancel();
                refreshFab.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start();
            };
            fabResetHandler.postDelayed(fabResetRunnable, DOUBLE_TAP_MAX_GAP);
        }
    }

    /** Draw the circular FAB background. Orange when primed, muted indigo otherwise. */
    private void applyFabBackground(boolean primed) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (primed) {
            bg.setColor(COLOR_FAB_PRIMED);
            bg.setStroke(Math.round(2 * density), 0xFFFF6D3A);
        } else {
            bg.setColor(COLOR_FAB_NORMAL);
            bg.setStroke(0, Color.TRANSPARENT);
        }
        refreshFab.setBackground(bg);
    }

    /** Clamp an X coordinate so the button stays fully on screen. */
    private float clampFabX(float x) {
        return Math.max(0, Math.min(x, rootLayout.getWidth() - fabSizePx));
    }

    /** Clamp a Y coordinate so the button stays fully on screen. */
    private float clampFabY(float y) {
        return Math.max(0, Math.min(y, rootLayout.getHeight() - fabSizePx));
    }

    /** Position the FAB from SharedPreferences, defaulting to bottom-right corner. */
    private void restoreFabPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float savedX = prefs.getFloat(PREF_FAB_X, -1f);
        float savedY = prefs.getFloat(PREF_FAB_Y, -1f);

        if (savedX < 0 || savedY < 0) {
            // Default: bottom-right with a small margin
            float margin = 20 * getResources().getDisplayMetrics().density;
            savedX = rootLayout.getWidth()  - fabSizePx - margin;
            savedY = rootLayout.getHeight() - fabSizePx - margin;
        }

        refreshFab.setX(clampFabX(savedX));
        refreshFab.setY(clampFabY(savedY));
    }

    /** Persist the current FAB position so it survives app restarts. */
    private void saveFabPosition() {
        if (refreshFab == null) return;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREF_FAB_X, refreshFab.getX())
            .putFloat(PREF_FAB_Y, refreshFab.getY())
            .apply();
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
