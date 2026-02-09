package com.example.snapshop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Helper for opening URLs using Chrome Custom Tabs.
 *
 * Benefits over Intent.ACTION_VIEW:
 * - Shares Chrome cookies/session → avoids Amazon 503 bot detection
 * - Stays visually inside the app (custom toolbar color, animations)
 * - Faster page load (pre-warming connection)
 * - Smooth back-navigation returns to SnapShop
 */
object CustomTabHelper {

    // SnapShop brand purple for the toolbar
    private const val TOOLBAR_COLOR = 0xFF512DA8.toInt()

    // Amazon Shopping app package name
    private const val AMAZON_APP_PACKAGE = "com.amazon.mShop.android.shopping"

    /**
     * Open a URL in Chrome Custom Tab.
     * Falls back to default browser if Chrome is unavailable.
     */
    fun openUrl(context: Context, url: String) {
        try {
            val customTabsIntent = buildCustomTabsIntent()
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            // Fallback: default browser
            fallbackToBrowser(context, url)
        }
    }

    /**
     * Open an Amazon URL with smart routing:
     * 1. Try Amazon Shopping app first (best UX, no bot detection)
     * 2. Fallback to Chrome Custom Tab
     * 3. Final fallback to default browser
     */
    fun openAmazonUrl(context: Context, url: String) {
        // Try Amazon app first
        if (tryOpenInAmazonApp(context, url)) return

        // Fallback to Custom Tab
        openUrl(context, url)
    }

    /**
     * Try to open URL in the Amazon Shopping app.
     * Returns true if successful, false if app not installed.
     */
    private fun tryOpenInAmazonApp(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.setPackage(AMAZON_APP_PACKAGE)
            // Verify the app can handle this intent
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Build a styled CustomTabsIntent matching SnapShop's theme.
     */
    private fun buildCustomTabsIntent(): CustomTabsIntent {
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(TOOLBAR_COLOR)
            .setNavigationBarColor(Color.BLACK)
            .build()

        return CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colorParams)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
    }

    /**
     * Final fallback: open in whatever browser is available.
     */
    private fun fallbackToBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }
}
