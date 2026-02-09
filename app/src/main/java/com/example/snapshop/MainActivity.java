package com.example.snapshop;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

/**
 * SnapShop - Home Navigation Screen
 *
 * Two main features:
 * 1. Detect & On-Chain: YOLO26 object detection + Solana memo (existing)
 * 2. SnapShop Camera: Detect objects + search products + buy with USDC (new)
 */
public class MainActivity extends AppCompatActivity {

    private CardView cardDetect;
    private CardView cardShop;
    private Button btnConnectWallet;
    private TextView tvWalletStatus;

    // Solana Wallet Helper
    private WalletHelper walletHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        cardDetect = findViewById(R.id.cardDetect);
        cardShop = findViewById(R.id.cardShop);
        btnConnectWallet = findViewById(R.id.btnConnectWallet);
        tvWalletStatus = findViewById(R.id.tvWalletStatus);

        // Initialize wallet helper
        walletHelper = new WalletHelper(this);

        // Card 1: Detect & On-Chain
        cardDetect.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DetectActivity.class);
            startActivity(intent);
        });

        // Card 2: SnapShop Camera
        cardShop.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ShopCameraActivity.class);
            startActivity(intent);
        });

        // Wallet connection button
        btnConnectWallet.setOnClickListener(v -> handleWalletClick());
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
     * Wallet callback
     */
    private final WalletHelper.WalletCallback walletCallback = new WalletHelper.WalletCallback() {
        @Override
        public void onConnected(@NonNull String address) {
            updateWalletUI(true);
            Toast.makeText(MainActivity.this, "Wallet connected!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected() {
            updateWalletUI(false);
            Toast.makeText(MainActivity.this, "Wallet disconnected", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onError(@NonNull String message) {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(MainActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onNoWalletFound() {
            btnConnectWallet.setEnabled(true);
            btnConnectWallet.setText("Connect Wallet");
            Toast.makeText(MainActivity.this, "No wallet app found. Please install Seed Vault Wallet.", Toast.LENGTH_LONG).show();
        }
    };

    /**
     * Update wallet UI state
     */
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
