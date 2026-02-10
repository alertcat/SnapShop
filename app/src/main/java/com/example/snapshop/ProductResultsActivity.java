package com.example.snapshop;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * ProductResultsActivity - Display product search results
 *
 * Shows search results from multiple shopping platforms.
 * Phase 1 (MVP): WebView/browser redirect with affiliate links
 * Phase 2: Native product cards with Google Vision API results
 */
public class ProductResultsActivity extends AppCompatActivity {

    private static final String TAG = "ProductResults";

    private TextView tvSearchQuery;
    private Button btnBack;
    private Button btnAmazon;
    private Button btnEbay;
    private Button btnAliExpress;
    private Button btnBuyUsdc;
    private Button btnConnectWallet;
    private TextView tvWalletStatus;

    private WalletHelper walletHelper;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_results);

        // Get search query from intent
        searchQuery = getIntent().getStringExtra("search_query");
        if (searchQuery == null) searchQuery = "";
        String source = getIntent().getStringExtra("search_source");

        // Initialize views
        tvSearchQuery = findViewById(R.id.tvSearchQuery);
        btnBack = findViewById(R.id.btnBack);
        btnAmazon = findViewById(R.id.btnAmazon);
        btnEbay = findViewById(R.id.btnEbay);
        btnAliExpress = findViewById(R.id.btnAliExpress);
        btnBuyUsdc = findViewById(R.id.btnBuyUsdc);
        btnConnectWallet = findViewById(R.id.btnConnectWallet);
        tvWalletStatus = findViewById(R.id.tvWalletStatus);

        // Initialize wallet
        walletHelper = new WalletHelper(this);

        // Display search query
        tvSearchQuery.setText("Searching: " + searchQuery);

        // Back button
        btnBack.setOnClickListener(v -> finish());

        // Shopping platform buttons — in-app WebView (keeps user inside the app)
        btnAmazon.setOnClickListener(v -> {
            String amazonUrl = ShopHelper.INSTANCE.buildAmazonSearchUrl(searchQuery);
            // Try Amazon app first (best UX), fallback to in-app WebView
            if (!tryOpenAmazonApp(amazonUrl)) {
                openInAppWebView(amazonUrl, "amazon");
            }
        });
        btnEbay.setOnClickListener(v ->
                openInAppWebView(ShopHelper.INSTANCE.buildEbaySearchUrl(searchQuery), "ebay"));
        btnAliExpress.setOnClickListener(v ->
                openInAppWebView(ShopHelper.INSTANCE.buildAliExpressSearchUrl(searchQuery), "aliexpress"));

        // Buy with USDC button - opens Bitrefill for Amazon gift card
        btnBuyUsdc.setOnClickListener(v -> handleBuyWithUsdc());

        // Wallet connection
        btnConnectWallet.setOnClickListener(v -> handleWalletClick());
    }

    /**
     * Handle "Buy with USDC" button
     * Opens Bitrefill embed widget in in-app WebView
     */
    private void handleBuyWithUsdc() {
        if (!walletHelper.isConnected()) {
            Toast.makeText(this, "Please connect wallet first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Open Bitrefill embed widget in in-app WebView
        String bitrefillUrl = ShopHelper.INSTANCE.buildBitrefillEmbedUrl();
        openInAppWebView(bitrefillUrl, "bitrefill");

        Toast.makeText(this,
                "Buy an Amazon gift card with USDC on Bitrefill,\nthen use it to purchase your item!",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Handle wallet button click
     */
    private void handleWalletClick() {
        if (walletHelper.isConnected()) {
            walletHelper.disconnect(walletCallback);
        } else {
            btnConnectWallet.setEnabled(false);
            btnConnectWallet.setText("Connecting...");
            walletHelper.connect(walletCallback);
        }
    }

    /**
     * Open URL in the in-app ShopWebViewActivity.
     */
    private void openInAppWebView(String url, String platform) {
        Intent intent = new Intent(this, ShopWebViewActivity.class);
        intent.putExtra(ShopWebViewActivity.EXTRA_URL, url);
        intent.putExtra(ShopWebViewActivity.EXTRA_PLATFORM, platform);
        startActivity(intent);
    }

    /**
     * Try to open URL in the Amazon Shopping app.
     * Returns true if the app was found and opened.
     */
    private boolean tryOpenAmazonApp(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.amazon.mShop.android.shopping");
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return true;
            }
        } catch (ActivityNotFoundException e) {
            // App not installed
        }
        return false;
    }

    private final WalletHelper.WalletCallback walletCallback = new WalletHelper.WalletCallback() {
        @Override
        public void onConnected(@NonNull String address) {
            updateWalletUI(true);
            Toast.makeText(ProductResultsActivity.this, "Wallet connected!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected() {
            updateWalletUI(false);
        }

        @Override
        public void onError(@NonNull String message) {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(ProductResultsActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onNoWalletFound() {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(ProductResultsActivity.this, "No wallet found", Toast.LENGTH_LONG).show();
        }
    };

    private void updateWalletUI(boolean connected) {
        if (connected) {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Disconnect");
            btnConnectWallet.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            tvWalletStatus.setText(walletHelper.getShortAddress());
            tvWalletStatus.setTextColor(0xFF4CAF50);
        } else {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            btnConnectWallet.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF512DA8));
            tvWalletStatus.setText("Not Connected");
            tvWalletStatus.setTextColor(0xFF888888);
        }
    }
}
