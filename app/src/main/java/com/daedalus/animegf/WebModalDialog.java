package com.daedalus.animegf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small in-app browser shown as a glass modal on top of the app (used for web search and
 * translate). The strip of app above it stays visible; tapping that strip, the close button
 * or Back (when there is no page history left) dismisses it. A button in the header hands the
 * current page over to the external browser.
 */
final class WebModalDialog extends GlassDialog {

    private static final float HEIGHT_FRACTION = 0.84f;

    private final WebView web;
    private final View progress;
    private final TextView titleView;
    private final TextView hostView;
    private final String initialUrl;

    /**
     * @param title      shown until the page reports its own title
     * @param chipIcon   header icon
     * @param url        page to load (http / https)
     * @param userAgent  user agent to present, normally the main WebView's
     */
    WebModalDialog(Activity activity, String title, int chipIcon, String url, String userAgent) {
        super(activity, 640, heightDp(activity), true);
        this.initialUrl = url;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        panel.setLayoutParams(lp);

        titleView = title(title);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        hostView = subtitle(hostOf(url));
        hostView.setSingleLine(true);
        hostView.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        panel.addView(buildHeaderRow(chipIcon));

        progress = new View(ctx);
        progress.setBackground(Ui.accentGradient(0));
        progress.setPivotX(0f);
        progress.setScaleX(0f);
        progress.setVisibility(View.INVISIBLE);
        panel.addView(progress, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(2, Ui.dp(ctx, 2))));

        web = buildWebView(userAgent);
        panel.addView(web, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setOnDismissListener(d -> destroyWeb());
    }

    @Override
    void show() {
        super.show();
        web.loadUrl(initialUrl);
    }

    @Override
    protected void onBackRequested() {
        if (web.canGoBack()) web.goBack(); else dismissAnimated();
    }

    // ─── BUILD ────────────────────────────────────────────────────────────────

    private static int heightDp(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(activity.getResources().getDisplayMetrics().heightPixels * HEIGHT_FRACTION / density);
    }

    private LinearLayout buildHeaderRow(int chipIcon) {
        ImageView external = Ui.iconButton(ctx, R.drawable.ic_open_external, Ui.BLUE, 38, "Open in browser");
        external.setOnClickListener(v -> openInBrowser());
        ImageView close = Ui.iconButton(ctx, R.drawable.ic_close, Ui.TEXT, 38, "Close");
        close.setOnClickListener(v -> dismissAnimated());

        LinearLayout row = buildHeader(chipIcon, titleView, hostView, external);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(Ui.dp(ctx, 38), Ui.dp(ctx, 38));
        closeLp.leftMargin = Ui.dp(ctx, 6);
        row.addView(close, closeLp);
        return row;
    }

    private WebView buildWebView(String userAgent) {
        WebView view = new WebView(activity);
        view.setBackgroundColor(Ui.NAVY_DEEP);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (userAgent != null) s.setUserAgentString(userAgent);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isWebUrl(url)) return false;
                openExternal(url);   // intent:, market:, mailto: ...
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                hostView.setText(hostOf(url));
                setProgress(5);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                setProgress(100);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView v, String pageTitle) {
                if (pageTitle != null && !pageTitle.isEmpty()) titleView.setText(pageTitle);
            }
        });
        return view;
    }

    // ─── BEHAVIOUR ────────────────────────────────────────────────────────────

    private void setProgress(int percent) {
        if (percent >= 100) {
            progress.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                progress.setVisibility(View.INVISIBLE);
                progress.setAlpha(1f);
                progress.setScaleX(0f);
            }).start();
            return;
        }
        progress.animate().cancel();
        progress.setAlpha(1f);
        progress.setVisibility(View.VISIBLE);
        progress.setScaleX(percent / 100f);
    }

    private void openInBrowser() {
        String url = web.getUrl() != null ? web.getUrl() : initialUrl;
        dismissAnimated();
        openExternal(url);
    }

    private void openExternal(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app can open this", Toast.LENGTH_SHORT).show();
        }
    }

    private void destroyWeb() {
        web.stopLoading();
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(null);
        panel.removeView(web);
        web.destroy();
    }

    private static boolean isWebUrl(String url) {
        return url.startsWith("https://") || url.startsWith("http://");
    }

    private static String hostOf(String url) {
        String host = Uri.parse(url).getHost();
        return host != null ? host : url;
    }
}
