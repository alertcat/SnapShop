package com.example.snapshop;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * In-app WebView browser for shopping platforms.
 *
 * Replaces Chrome Custom Tabs to keep users inside the app.
 * Supports platform-specific configuration:
 * - Amazon: custom Chrome UA to avoid 503 bot detection
 * - Bitrefill: loads normal website (embed widget requires approved partner,
 *              apply at bitrefill.com/partner)
 */
public class ShopWebViewActivity extends AppCompatActivity {

    private static final String TAG = "ShopWebView";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PLATFORM = "platform"; // "amazon", "ebay", "aliexpress", "bitrefill"

    // Real Chrome Mobile User-Agent to avoid Amazon 503 bot detection
    private static final String CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36";

    // Desktop User-Agent for sites that force app deep links on mobile
    // (e.g. AliExpress redirects mobile to aliexpress:// scheme)
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvUrl;
    private ImageButton btnWebBack, btnWebForward, btnWebRefresh, btnWebClose;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop_webview);

        // Bind views
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        tvUrl = findViewById(R.id.tvUrl);
        btnWebBack = findViewById(R.id.btnWebBack);
        btnWebForward = findViewById(R.id.btnWebForward);
        btnWebRefresh = findViewById(R.id.btnWebRefresh);
        btnWebClose = findViewById(R.id.btnWebClose);

        String platform = getIntent().getStringExtra(EXTRA_PLATFORM);

        // Configure WebView settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Platform-specific User-Agent
        if ("amazon".equals(platform)) {
            // Amazon requires a real Chrome UA to avoid 503 bot detection
            settings.setUserAgentString(CHROME_MOBILE_UA);
        } else if ("aliexpress".equals(platform)) {
            // AliExpress mobile site redirects to aliexpress:// deep link scheme
            // which WebView cannot handle (ERR_UNKNOWN_URL_SCHEME).
            // Using desktop UA prevents this redirect and loads the full web version.
            settings.setUserAgentString(DESKTOP_UA);
        }

        // Enable cookies (important for shopping sites)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // WebViewClient - handles page navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                // Allow normal HTTP/HTTPS navigation inside the WebView
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }

                // Handle custom URL schemes (aliexpress://, intent://, market://, etc.)
                // These are app deep links that WebView cannot load directly.
                // Try to open them in the corresponding native app.
                Log.d(TAG, "Intercepted non-HTTP scheme: " + uri.toString());
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, "No app found for scheme: " + scheme);
                    // App not installed — ignore the redirect, stay on current page
                }
                return true; // Block WebView from trying to load the custom scheme
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                tvUrl.setText(url);
                updateNavigationButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateNavigationButtons();
            }
        });

        // WebChromeClient - handles progress bar
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        // Toolbar button listeners
        btnWebBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnWebForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        btnWebRefresh.setOnClickListener(v -> webView.reload());
        btnWebClose.setOnClickListener(v -> finish());

        // Handle system back button: WebView history first, then close
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        // Load the URL
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        } else {
            finish();
        }
    }

    private void updateNavigationButtons() {
        btnWebBack.setAlpha(webView.canGoBack() ? 1.0f : 0.3f);
        btnWebForward.setAlpha(webView.canGoForward() ? 1.0f : 0.3f);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
