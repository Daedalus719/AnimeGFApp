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
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import android.util.TypedValue;
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
    private WebView      webView;
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

    private static final long SPLASH_TIMEOUT_MS = 15000L;
    private final Handler splashHandler = new Handler(Looper.getMainLooper());

    // User agent the WebView would use for the normal mobile site.
    private String mobileUserAgent;
    // Desktop-mode script + how it is delivered (document-start when supported).
    private String        desktopScript;
    private ScriptHandler desktopScriptHandler;
    private static final Set<String> DESKTOP_SCRIPT_ORIGINS = new HashSet<>(
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
    @Override protected void onPause()  { super.onPause();  webView.onPause();  }

    @Override
    protected void onStop() {
        super.onStop();
        if (floatingMenu != null) floatingMenu.savePosition();
    }

    @Override
    protected void onDestroy() {
        splashHandler.removeCallbacksAndMessages(null);
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
                injectDesktopScriptFallback(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectDesktopScriptFallback(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectBlobDownloadBridge(view);
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
        setupWebViewContextMenu();
    }

        // Context Menu on images 
        private void setupWebViewContextMenu() {
            webView.setOnLongClickListener(v -> {
                WebView.HitTestResult hit = webView.getHitTestResult();
                if (hit == null) return false;

                int type = hit.getType();
                String extra = hit.getExtra(); // URL or src of what was hit

                if (type == WebView.HitTestResult.IMAGE_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

                    showImageContextMenu(extra);
                    return true;
                } else if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    showLinkContextMenu(extra);
                    return true;
                }

                return false;
            });
        }
            private void showImageContextMenu(String url) {
                BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.DarkBottomSheetStyle);

                LinearLayout root = new LinearLayout(this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setBackgroundColor(0xFF1C1C1E);

                // ── Drag handle ──────────────────────────────────────────────────────
                LinearLayout handleWrapper = new LinearLayout(this);
                handleWrapper.setGravity(Gravity.CENTER_HORIZONTAL);
                handleWrapper.setPadding(0, dpToPx(10), 0, 0);
                View handle = new View(this);
                GradientDrawable handleBg = new GradientDrawable();
                handleBg.setColor(0xFF48484A);
                handleBg.setCornerRadius(dpToPx(3));
                handle.setBackground(handleBg);
                handleWrapper.addView(handle, new LinearLayout.LayoutParams(dpToPx(32), dpToPx(4)));
                root.addView(handleWrapper);

                // ── Image preview card ───────────────────────────────────────────────
                LinearLayout previewRow = new LinearLayout(this);
                previewRow.setOrientation(LinearLayout.HORIZONTAL);
                previewRow.setGravity(Gravity.CENTER_VERTICAL);
                previewRow.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));

                // Thumbnail placeholder (grey rounded rect — loads async below)
                android.widget.ImageView thumb = new android.widget.ImageView(this);
                thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                GradientDrawable thumbBg = new GradientDrawable();
                thumbBg.setColor(0xFF2C2C2E);
                thumbBg.setCornerRadius(dpToPx(6));
                thumb.setBackground(thumbBg);
                LinearLayout.LayoutParams thumbLp =
                    new LinearLayout.LayoutParams(dpToPx(52), dpToPx(52));
                thumbLp.rightMargin = dpToPx(12);
                thumb.setLayoutParams(thumbLp);

                String thumbUA = webView.getSettings().getUserAgentString();
                String thumbCookie = CookieManager.getInstance().getCookie(url);
                
                // Load thumbnail async
                new Thread(() -> {
                    try {
                        java.net.URL imgUrl = new java.net.URL(url);
                        java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) imgUrl.openConnection();
                        conn.setRequestProperty("User-Agent", thumbUA);
                        conn.setRequestProperty("Cookie", thumbCookie);
                        conn.setRequestProperty("Referer", "https://anime.gf/");
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(5000);
                        conn.connect();
                        Bitmap bmp = android.graphics.BitmapFactory
                            .decodeStream(conn.getInputStream());
                        if (bmp != null) {
                            runOnUiThread(() -> {
                                thumb.setImageBitmap(bmp);
                                thumb.setBackground(null);
                            });
                        }
                    } catch (Exception ignored) {}
                }).start();

                // URL label
                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView urlLabel = new TextView(this);
                String host = "";
                try { host = new java.net.URL(url).getHost(); } catch (Exception e) { host = url; }
                urlLabel.setText(host);
                urlLabel.setTextColor(0xFFFFFFFF);
                urlLabel.setTextSize(14);
                urlLabel.setMaxLines(1);
                urlLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView urlSub = new TextView(this);
                urlSub.setText(url);
                urlSub.setTextColor(0xFF8E8E93);
                urlSub.setTextSize(11);
                urlSub.setMaxLines(1);
                urlSub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

                textCol.addView(urlLabel);
                textCol.addView(urlSub);

                previewRow.addView(thumb);
                previewRow.addView(textCol);
                root.addView(previewRow);

                // ── Separator ────────────────────────────────────────────────────────
                View sep = new View(this);
                sep.setBackgroundColor(0xFF38383A);
                root.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

                // ── Menu items ───────────────────────────────────────────────────────
                // Using Unicode symbols that render cleanly as icon-like glyphs
                Object[][] items = {
                    {0x21E9, "Save image",             (Runnable) () -> downloadImage(url)},
                    {0x1F517, "Copy image URL",        (Runnable) () -> copyToClipboard(url)},
                    {0x1F50D, "Search with Google Lens",(Runnable) () -> openGoogleLens(url)},
                };

                // Better: use simple vector-drawn icons via canvas
                int[][] iconColors = {
                    {0xFF30D158}, // green  - save
                    {0xFF0A84FF}, // blue   - copy
                    {0xFFFF453A}, // red    - lens
                };
                String[] svgPaths = {"⬇", "🔗", "⌕"};
                // Use text-based icons with a styled circle bg for a modern pill look
                String[] iconSymbols = {"↓", "⎘", "⌕"};
                int[] iconBgColors   = {0xFF1C3A2A, 0xFF1A2A3A, 0xFF2A1A1A};
                int[] iconFgColors   = {0xFF30D158, 0xFF0A84FF, 0xFFFF6B6B};
                String[] labels      = {"Save image", "Copy image URL", "Search with Google Lens"};
                Runnable[] actions   = {
                    () -> downloadImage(url),
                    () -> copyToClipboard(url),
                    () -> openGoogleLens(url)
                };

                for (int i = 0; i < labels.length; i++) {
                    final Runnable action = actions[i];

                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dpToPx(16), dpToPx(13), dpToPx(16), dpToPx(13));
                    TypedValue ripple = new TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                    row.setBackgroundResource(ripple.resourceId);

                    // Circular icon bg
                    TextView iconView = new TextView(this);
                    iconView.setText(iconSymbols[i]);
                    iconView.setTextColor(iconFgColors[i]);
                    iconView.setTextSize(16);
                    iconView.setGravity(Gravity.CENTER);
                    GradientDrawable iconBg = new GradientDrawable();
                    iconBg.setShape(GradientDrawable.OVAL);
                    iconBg.setColor(iconBgColors[i]);
                    iconView.setBackground(iconBg);
                    LinearLayout.LayoutParams iconLp =
                        new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
                    iconLp.rightMargin = dpToPx(14);
                    iconView.setLayoutParams(iconLp);

                    TextView labelView = new TextView(this);
                    labelView.setText(labels[i]);
                    labelView.setTextColor(0xFFFFFFFF);
                    labelView.setTextSize(15);

                    row.addView(iconView);
                    row.addView(labelView);
                    row.setOnClickListener(v -> { sheet.dismiss(); action.run(); });
                    root.addView(row);

                    // thin separator between rows, not after last
                    if (i < labels.length - 1) {
                        View div = new View(this);
                        div.setBackgroundColor(0xFF38383A);
                        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1);
                        divLp.setMargins(dpToPx(66), 0, 0, 0); // indent to align with text, not icon
                        div.setLayoutParams(divLp);
                        root.addView(div);
                    }
                }

                // bottom breathing room
                root.addView(new View(this), new LinearLayout.LayoutParams(0, dpToPx(16)));

                sheet.setContentView(root);
                sheet.show();
            }

            private int dpToPx(int dp) {
                return Math.round(dp * getResources().getDisplayMetrics().density);
            }

            private void showLinkContextMenu(String url) {
                BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.DarkBottomSheetStyle);

                LinearLayout layout = new LinearLayout(this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setBackgroundColor(0xFF1A1A2E);
                int sidePad = dpToPx(8);
                layout.setPadding(sidePad, dpToPx(12), sidePad, dpToPx(24));

                // Handle bar (same as image menu)
                View handle = new View(this);
                GradientDrawable handleBg = new GradientDrawable();
                handleBg.setColor(0xFF444466);
                handleBg.setCornerRadius(dpToPx(4));
                handle.setBackground(handleBg);
                LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dpToPx(36), dpToPx(4));
                handleParams.gravity = Gravity.CENTER_HORIZONTAL;
                LinearLayout handleWrapper = new LinearLayout(this);
                handleWrapper.setGravity(Gravity.CENTER_HORIZONTAL);
                handleWrapper.setPadding(0, 0, 0, dpToPx(8));
                handleWrapper.addView(handle, handleParams);
                layout.addView(handleWrapper);

                String[][] items = {
                    {"🌐", "Open in browser"},
                    {"🔗", "Copy link"}
                };

                for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
                    TypedValue ripple = new TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                    row.setBackgroundResource(ripple.resourceId);

                    TextView emoji = new TextView(this);
                    emoji.setText(items[i][0]);
                    emoji.setTextSize(20);
                    emoji.setPadding(0, 0, dpToPx(16), 0);

                    TextView label = new TextView(this);
                    label.setText(items[i][1]);
                    label.setTextColor(0xFFE0E0F0);
                    label.setTextSize(15);

                    row.addView(emoji);
                    row.addView(label);
                    row.setOnClickListener(v -> {
                        sheet.dismiss();
                        if (index == 0) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        else copyToClipboard(url);
                    });
                    layout.addView(row);

                    if (i < items.length - 1) {
                        View divider = new View(this);
                        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1);
                        dp.setMargins(dpToPx(16), 0, dpToPx(16), 0);
                        divider.setLayoutParams(dp);
                        divider.setBackgroundColor(0x22FFFFFF);
                        layout.addView(divider);
                    }
                }

                sheet.setContentView(layout);
                sheet.show();
            }

        private void downloadImage(String url) {
            if (url == null || url.isEmpty()) return;

                // Grab these on the main thread BEFORE going background
                String userAgent = webView.getSettings().getUserAgentString();
                String cookie = CookieManager.getInstance().getCookie(url);

            Toast.makeText(this, "Saving image…", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
                try {
                    java.net.URL imgUrl = new java.net.URL(url);
                    java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) imgUrl.openConnection();
                    conn.setRequestProperty("User-Agent", userAgent);
                    conn.setRequestProperty("Cookie", cookie);
                    conn.setRequestProperty("Referer", "https://anime.gf/");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.connect();

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        runOnUiThread(() -> Toast.makeText(this,
                            "Download failed: HTTP " + responseCode,
                            Toast.LENGTH_LONG).show());
                        return;
                    }

                    // Guess extension from Content-Type if URL has none
                    String contentType = conn.getContentType();
                    String ext = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentType);
                    if (ext == null) ext = "jpg";

                    String fileName = "AnimeGF_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date()) + "." + ext;

                    File dir = Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, fileName);

                    try (java.io.InputStream in = conn.getInputStream();
                        FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    }

                    MediaScannerConnection.scanFile(this,
                        new String[]{out.getAbsolutePath()},
                        new String[]{contentType}, null);

                    runOnUiThread(() -> Toast.makeText(this,
                        "Saved to Pictures: " + fileName, Toast.LENGTH_LONG).show());

                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                        "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        private void copyToClipboard(String text) {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", text));
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
            }
        }

        private void openGoogleLens(String imageUrl) {
            String lensUrl = "https://lens.google.com/uploadbyurl?url=" +
                Uri.encode(imageUrl);
            // Try to open in the main WebView so cookies are shared
            webView.loadUrl(lensUrl);
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
                        webView, desktopScript, DESKTOP_SCRIPT_ORIGINS);
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
