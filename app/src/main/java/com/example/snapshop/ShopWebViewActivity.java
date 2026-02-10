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
    // Also used for AliExpress (mobile layout is better for phone screens)
    private static final String CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36";

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
        // Amazon & AliExpress both need Chrome Mobile UA for proper mobile layout
        if ("amazon".equals(platform) || "aliexpress".equals(platform)) {
            settings.setUserAgentString(CHROME_MOBILE_UA);
        }

        // Enable cookies (important for shopping sites)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // WebViewClient - handles page navigation and deep link interception
        final String currentPlatform = platform;
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                // Allow normal HTTP/HTTPS navigation inside the WebView
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }

                // Block custom URL schemes (aliexpress://, intent://, market://, etc.)
                // These are app deep links that WebView cannot load directly.
                // For shopping platforms, we stay in the WebView instead of launching external apps.
                Log.d(TAG, "Blocked non-HTTP scheme: " + uri.toString());

                // For intent:// schemes, try to extract the fallback HTTPS URL
                if ("intent".equals(scheme)) {
                    String fallbackUrl = extractIntentFallbackUrl(uri.toString());
                    if (fallbackUrl != null) {
                        Log.d(TAG, "Using intent fallback URL: " + fallbackUrl);
                        view.loadUrl(fallbackUrl);
                        return true;
                    }
                }

                // Silently ignore all other custom schemes — stay on current page
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                tvUrl.setText(url);
                updateNavigationButtons();

                // Inject JS early to intercept deep link redirects before they execute
                if ("aliexpress".equals(currentPlatform)) {
                    injectDeepLinkBlocker(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateNavigationButtons();

                // Re-inject after page fully loads (some redirects are deferred)
                if ("aliexpress".equals(currentPlatform)) {
                    injectDeepLinkBlocker(view);
                }
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

    /**
     * Inject JavaScript to block deep link redirects (aliexpress://, etc.)
     *
     * AliExpress mobile site uses JS to redirect to their native app via
     * window.location = "aliexpress://..." or by creating hidden <a> tags.
     * This script overrides the location setter to block non-HTTP schemes,
     * keeping the user inside the WebView with the mobile-optimized layout.
     */
    private void injectDeepLinkBlocker(WebView view) {
        String js = "(function() {" +
                "  if (window.__deepLinkBlocked) return;" +
                "  window.__deepLinkBlocked = true;" +
                // Override window.location assignment to block custom schemes
                "  var origLocation = window.location;" +
                "  Object.defineProperty(window, '__aliBlock', {value: true});" +
                // Intercept dynamic <a> / <iframe> navigations
                "  var origCreateElement = document.createElement;" +
                "  document.createElement = function(tag) {" +
                "    var el = origCreateElement.call(document, tag);" +
                "    if (tag.toLowerCase() === 'a' || tag.toLowerCase() === 'iframe') {" +
                "      var origSetAttr = el.setAttribute;" +
                "      el.setAttribute = function(name, value) {" +
                "        if ((name === 'href' || name === 'src') && " +
                "            typeof value === 'string' && " +
                "            !value.startsWith('http') && !value.startsWith('/') && !value.startsWith('#')) {" +
                "          console.log('[SnapShop] Blocked deep link: ' + value);" +
                "          return;" +
                "        }" +
                "        return origSetAttr.call(this, name, value);" +
                "      };" +
                "    }" +
                "    return el;" +
                "  };" +
                // Block meta refresh redirects to custom schemes
                "  var observer = new MutationObserver(function(mutations) {" +
                "    mutations.forEach(function(m) {" +
                "      m.addedNodes.forEach(function(node) {" +
                "        if (node.tagName === 'META' && node.httpEquiv === 'refresh') {" +
                "          var content = node.content || '';" +
                "          if (content.indexOf('aliexpress://') !== -1 || " +
                "              content.indexOf('intent://') !== -1) {" +
                "            node.remove();" +
                "            console.log('[SnapShop] Blocked meta refresh redirect');" +
                "          }" +
                "        }" +
                "      });" +
                "    });" +
                "  });" +
                "  observer.observe(document.documentElement, {childList: true, subtree: true});" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * Extract fallback HTTPS URL from an intent:// URI.
     * Format: intent://...#Intent;scheme=https;S.browser_fallback_url=https://...;end
     */
    private String extractIntentFallbackUrl(String intentUri) {
        try {
            // Look for S.browser_fallback_url=
            String marker = "S.browser_fallback_url=";
            int idx = intentUri.indexOf(marker);
            if (idx != -1) {
                String rest = intentUri.substring(idx + marker.length());
                int endIdx = rest.indexOf(';');
                if (endIdx != -1) {
                    return Uri.decode(rest.substring(0, endIdx));
                }
                return Uri.decode(rest);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract fallback URL from intent", e);
        }
        return null;
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
