package com.ufo.galaxy

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PR-URL-REQUIRED: 首次启动配置引导
 *
 * 引导用户完成以下步骤：
 * 1. 输入 V2 服务器 IP/端口
 * 2. 选择连接模式（LAN / Tailscale）
 * 3. 测试连接
 * 4. 保存配置并进入主界面
 *
 * This Activity is shown instead of [MainActivity] when [AppSettings.isConfigured]
 * returns false (blank gateway host or invalid port).
 */
class FirstTimeSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FirstTimeSetup"

        // C3-FIX: Auto-discovery candidate hosts in priority order
        private const val DISCOVER_PORT = 9000
        private const val DISCOVER_TIMEOUT_MS = 3000
        private val AUTO_DISCOVER_HOSTS = listOf(
            "localhost",
            "127.0.0.1"
        )
        // Common Tailscale IP ranges (100.64.0.0/10 CGNAT space used by Tailscale)
        private val TAILSCALE_CANDIDATES = (1..20).map { "100.64.0.$it" } +
            (1..10).map { "100.100.100.$it" }
    }

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var cbTailscale: CheckBox
    private lateinit var btnTest: Button
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first_time_setup)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        cbTailscale = findViewById(R.id.cbTailscale)
        btnTest = findViewById(R.id.btnTest)
        btnSave = findViewById(R.id.btnSave)

        // Tailscale checkbox: auto-fill 100.x range hint when checked
        cbTailscale.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && etHost.text.isNullOrBlank()) {
                etHost.hint = "100.x.x.x (Tailscale IP)"
            } else if (!isChecked) {
                etHost.hint = getString(R.string.setup_host_hint)
            }
        }

        // C3-FIX: Start auto-discovery in the background when activity is created
        startAutoDiscovery()

        btnTest.setOnClickListener {
            val host = etHost.text.toString().trim()
            val portText = etPort.text.toString().trim()
            val port = portText.toIntOrNull() ?: 0

            if (host.isEmpty()) {
                etHost.error = getString(R.string.setup_host_required)
                return@setOnClickListener
            }
            if (port <= 0 || port > 65535) {
                etPort.error = getString(R.string.setup_port_invalid)
                return@setOnClickListener
            }

            testConnection(host, port)
        }

        btnSave.setOnClickListener {
            val host = etHost.text.toString().trim()
            val portText = etPort.text.toString().trim()
            val port = portText.toIntOrNull() ?: 0

            if (host.isEmpty()) {
                etHost.error = getString(R.string.setup_host_required)
                return@setOnClickListener
            }
            // PR-URL-VALIDATE: reject invalid hostname/IP formats
            if (!host.matches(Regex("^[a-zA-Z0-9._-]+$") ) &&
                !host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) &&
                !host.matches(Regex("^100\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                etHost.error = "Invalid hostname or IP format"
                return@setOnClickListener
            }
            if (port <= 0 || port > 65535) {
                etPort.error = getString(R.string.setup_port_invalid)
                return@setOnClickListener
            }

            // Save configuration
            try {
                val settings = AppSettings.getInstance(applicationContext)
                settings.gatewayHost = host
                settings.gatewayPort = port
                settings.useTls = cbTailscale.isChecked && host.startsWith("https")

                Log.i(TAG, "Configuration saved: host=$host, port=$port")
                Toast.makeText(this, getString(R.string.setup_saved), Toast.LENGTH_SHORT).show()

                // Launch main activity
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Performs a lightweight connectivity test by attempting to open a TCP socket
     * to the given [host] and [port].
     */
    private fun testConnection(host: String, port: Int) {
        btnTest.isEnabled = false
        btnTest.text = getString(R.string.setup_testing)

        // CRITICAL-7: Use lifecycleScope coroutine instead of raw Thread to prevent Activity leak
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, port), 5000)
                        true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Connection test failed: ${e.message}")
                    false
                }
            }

            btnTest.isEnabled = true
            btnTest.text = getString(R.string.setup_test_button)
            if (reachable) {
                Toast.makeText(this@FirstTimeSetupActivity, getString(R.string.setup_test_success), Toast.LENGTH_SHORT).show()
                btnSave.isEnabled = true
            } else {
                Toast.makeText(this@FirstTimeSetupActivity, getString(R.string.setup_test_failed), Toast.LENGTH_LONG).show()
                // Allow saving even if test fails — the server may be temporarily unreachable
                btnSave.isEnabled = true
            }
        }
    }

    /**
     * C3-FIX: Auto-discovery of gateway server.
     *
     * Attempts to connect to a list of candidate hosts in order:
     * 1. localhost:9000  (V2 backend on same device via emulator/adb)
     * 2. 127.0.0.1:9000 (loopback explicit)
     * 3. Common Tailscale IPs (100.64.x.x range)
     *
     * If a reachable host is found, pre-fills the host field and enables the save button.
     * This runs in a background coroutine tied to the Activity lifecycle.
     */
    private fun startAutoDiscovery() {
        lifecycleScope.launch {
            val discovered = withContext(Dispatchers.IO) {
                // Try localhost and 127.0.0.1 first
                for (host in AUTO_DISCOVER_HOSTS) {
                    Log.d(TAG, "Auto-discover: trying $host:$DISCOVER_PORT")
                    if (testHostConnectivity(host, DISCOVER_PORT)) {
                        Log.i(TAG, "Auto-discover: found gateway at $host:$DISCOVER_PORT")
                        return@withContext host
                    }
                }
                // Try common Tailscale IPs
                for (host in TAILSCALE_CANDIDATES) {
                    Log.d(TAG, "Auto-discover: trying Tailscale candidate $host:$DISCOVER_PORT")
                    if (testHostConnectivity(host, DISCOVER_PORT)) {
                        Log.i(TAG, "Auto-discover: found Tailscale gateway at $host:$DISCOVER_PORT")
                        return@withContext host
                    }
                }
                null
            }

            if (discovered != null) {
                etHost.setText(discovered)
                etPort.setText(DISCOVER_PORT.toString())
                btnSave.isEnabled = true
                Toast.makeText(
                    this@FirstTimeSetupActivity,
                    "Auto-discovered gateway at $discovered:$DISCOVER_PORT",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Log.i(TAG, "Auto-discover: no gateway found, waiting for manual input")
            }
        }
    }

    /**
     * C3-FIX: Lightweight TCP connectivity test for a single host/port.
     *
     * @return true if a TCP connection can be established within [timeoutMs].
     */
    private fun testHostConnectivity(host: String, port: Int, timeoutMs: Int = DISCOVER_TIMEOUT_MS): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Host connectivity test failed for $host:$port: ${e.message}")
            false
        }
    }
}
