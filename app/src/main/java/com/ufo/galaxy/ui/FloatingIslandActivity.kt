package com.ufo.galaxy.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.ufo.galaxy.service.EnhancedFloatingService

/**
 * Transparent overlay Activity that hosts the floating island (灵动岛) UI.
 *
 * This Activity is declared in AndroidManifest.xml with
 * `android:theme="@style/Theme.UFOGalaxy.Transparent"` and
 * `android:launchMode="singleInstance"` so that it acts as a lightweight
 * overlay entry point.
 *
 * When launched it immediately starts [EnhancedFloatingService] (which shows
 * the actual floating window) and finishes itself so it does not appear in the
 * recent-apps list.
 */
class FloatingIslandActivity : Activity() {

    companion object {
        private const val TAG = "FloatingIslandActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "FloatingIslandActivity onCreate")

        // Make this Activity window invisible so only the floating overlay is shown.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        // Ensure the EnhancedFloatingService is running.
        startService(Intent(this, EnhancedFloatingService::class.java))

        // Finish immediately — the floating window is managed by EnhancedFloatingService.
        finish()
    }
}
