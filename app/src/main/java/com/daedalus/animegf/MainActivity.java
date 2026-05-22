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
    // This is what the OAuth overlay WebView identifies itself as, so Google
    // does NOT block the sign-in as "disallowed_useragent".
    private static final String CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    // ──────────────────────────────────────────────────────────────────────────

    private static final String TAG              = "AnimeGFApp";
    private static final int    REQ_STORAGE_PERM = 1001;

    private WebView      webView;
    private ProgressBar  progressBar;
    private TextView     offlineBanner;
    private LinearLayout rootLayout;

    private ValueCallback<Uri[]>           fileChooserCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

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
        progressBar   = findViewById(R.id.progressBar);
        offlineBanner = findViewById(R.id.offlineBanner);
        webView       = findViewById(R.id.webView);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() |
                WindowInsetsCompat.Type.displayCutout()
            );
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            // When keyboard is closed: ime.bottom = 0, so bars.bottom wins (nav bar height)
            // When keyboard is open:   ime.bottom = full keyboard height, always > nav bar
            // This makes the chat input lift above the keyboard automatically.
            int bottomPadding = Math.max(bars.bottom, ime.bottom);

            view.setPadding(bars.left, bars.top, bars.right, bottomPadding);
            return WindowInsetsCompat.CONSUMED;
        });

        setupFileChooserLauncher();
        setupWebView();
        requestStoragePermissionIfNeeded();

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            // Opened via a deep link — load that URL directly
            if (isOnline()) {
                webView.loadUrl(intent.getData().toString());
            } else {
                showOfflineBanner(true);
            }
        } else {
            // Normal launch — load the homepage
            if (isOnline()) {
                webView.loadUrl(TARGET_URL);
            } else {
                showOfflineBanner(true);
            }
        }
    }

    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onPause()   { super.onPause();   webView.onPause();  }

    @Override
    protected void onDestroy() {
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
                                        android.webkit.WebResourceError err) {
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
                // Create a fresh intent ignoring website's file type restrictions
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

    // ─── OAUTH OVERLAY ────────────────────────────────────────────────────────

    /**
     * Opens a full-screen dialog containing a WebView configured with a clean
     * Chrome Mobile user agent (no "wv" marker). Google's OAuth happily
     * accepts this UA and allows the login to proceed.
     *
     * Because Android's CookieManager is process-wide, every WebView in this
     * app shares the same cookie jar. The moment Google sets the session cookie
     * in the overlay WebView, the main WebView already has it — no sync needed.
     *
     * When the OAuth flow finishes, Google redirects back to anime.gf. We detect
     * that redirect, close the overlay, and tell the main WebView to load the
     * callback URL so it can finalise the session handshake.
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

                // Still a Google-owned URL — stay in the overlay
                if (isGoogleAuthUrl(redirectUrl) || isDiscordAuthUrl(redirectUrl)) return false;

                // The OAuth flow has completed and Google is redirecting back
                // to the app's domain (anime.gf or its auth callback).
                // Close the overlay and pass the URL to the main WebView.
                dialog.dismiss();
                oauthWebView.destroy();
                webView.loadUrl(redirectUrl);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Nothing extra needed; the dialog itself shows loading activity
            }
        });

        oauthWebView.loadUrl(url);
        dialog.setContentView(oauthWebView);
        dialog.setOnDismissListener(d -> {
            // If user presses back to dismiss without completing sign-in,
            // ensure the orphaned WebView is cleaned up
            try { oauthWebView.destroy(); } catch (Exception ignored) {}
        });
        dialog.show();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    /** Configures common WebView settings. Pass useCleanUA=true for the OAuth overlay. */
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
            // Fully clean Chrome Mobile UA — Google OAuth will not block this
            s.setUserAgentString(CHROME_MOBILE_UA);
        } else {
            // Strip the "wv" WebView marker from the default UA so that
            // *other* OAuth providers (Discord, etc.) also see a normal browser
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
        || url.contains("supabase.co")          // handles ALL auth redirects
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
