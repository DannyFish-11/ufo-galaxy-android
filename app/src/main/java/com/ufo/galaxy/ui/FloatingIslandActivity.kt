package com.ufo.galaxy.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.service.EnhancedFloatingService
import kotlinx.coroutines.launch

/**
 * Transparent overlay Activity that hosts the floating island (灵动岛) UI
 * and the cross-device / local mode toggle (PR-MODE-TOGGLE).
 *
 * This Activity is declared in AndroidManifest.xml with
 * `android:theme="@style/Theme.UFOGalaxy.Transparent"` and
 * `android:launchMode="singleInstance"` so that it acts as a lightweight
 * overlay entry point.
 *
 * When launched it starts [EnhancedFloatingService] (which shows the actual
 * floating window) and displays a compact mode-toggle UI so the user can
 * switch between local-only and cross-device execution without opening the
 * full settings screen.
 */
class FloatingIslandActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FloatingIslandActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "FloatingIslandActivity onCreate")

        // Ensure the EnhancedFloatingService is running.
        startService(Intent(this, EnhancedFloatingService::class.java))

        // Show the mode-toggle UI.
        setContentView(R.layout.activity_floating_island)
        setupModeToggle()
        setupCloseButton()
    }

    /**
     * PR-MODE-TOGGLE: Configures the cross-device / local mode toggle button.
     *
     * Tapping the button flips [AppSettings.crossDeviceEnabled] and starts or
     * stops the [RuntimeController] accordingly.
     */
    private fun setupModeToggle() {
        val modeToggle = findViewById<Button>(R.id.btnModeToggle)
        val settings = UFOGalaxyApplication.appSettings
        val runtimeController = UFOGalaxyApplication.runtimeController

        updateModeButton(modeToggle, settings.crossDeviceEnabled)

        modeToggle.setOnClickListener {
            val newMode = !settings.crossDeviceEnabled
            settings.crossDeviceEnabled = newMode
            updateModeButton(modeToggle, newMode)

            // Notify RuntimeController of the mode change
            lifecycleScope.launch {
                if (newMode) {
                    try {
                        runtimeController.start()
                        Toast.makeText(this@FloatingIslandActivity, "跨设备模式已开启", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start runtime controller: ${e.message}", e)
                        Toast.makeText(this@FloatingIslandActivity, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    try {
                        runtimeController.stop()
                        Toast.makeText(this@FloatingIslandActivity, "本地模式", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to stop runtime controller: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Updates the mode toggle button appearance based on the current mode.
     */
    private fun updateModeButton(button: Button, isCrossDevice: Boolean) {
        button.text = if (isCrossDevice) "\uD83C\uDF10 跨设备" else "\uD83D\uDCF1 本地"
        button.setBackgroundColor(
            if (isCrossDevice) Color.parseColor("#4CAF50")
            else Color.parseColor("#757575")
        )
    }

    /**
     * Sets up the close button to dismiss this overlay Activity.
     */
    private fun setupCloseButton() {
        val closeButton = findViewById<Button>(R.id.btnClose)
        closeButton.setOnClickListener {
            finish()
        }
    }
}
