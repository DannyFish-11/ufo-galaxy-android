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

        private const val DISCOVER_PORT = 9000
        private const val DISCOVER_TIMEOUT_MS = 3000

        // 环回只在"模拟器 + adb reverse tcp:9000"这一种开发场景下有意义：
        // 真机上 localhost 指向手机自己，网关在 PC 上。所以它排在 mDNS 之后，
        // 且只试这一个、只花一次超时。
        private val LOOPBACK_FALLBACK_HOSTS = listOf("127.0.0.1")
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
                // TLS-FIX: the old expression `cbTailscale.isChecked && host.startsWith("https")`
                // was dead logic — the host field holds a bare IP/hostname (validated above to
                // reject URL forms), so it never starts with "https" and useTls was therefore
                // ALWAYS overwritten to false, silently disabling TLS (contradicting the
                // SECURITY-FIX-R5 default of true). Correct semantics: Tailscale traffic is
                // already encrypted by WireGuard, so plain ws:// is intentional there; for any
                // other host preserve the existing setting (the Network Settings screen has a
                // dedicated TLS switch for changing it).
                if (cbTailscale.isChecked) {
                    settings.useTls = false
                }

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
     * 1. mDNS(`_galaxy._tcp`)—— 网关自己广播它在哪；
     * 2. 环回 —— 仅对"模拟器 + adb reverse"这一种开发场景有意义。
     *
     * 改前这里是**猜 IP**：`100.64.0.1..20` 加 `100.100.100.1..10`，共 30 个地址，
     * 每个 3 秒 TCP 超时逐个试，最坏在首屏卡 90 秒。而 Tailscale 从
     * 100.64.0.0/10 分配 —— 那是四百多万个地址，猜前 20 个和不猜没有区别；
     * `100.100.100.100` 更是 MagicDNS 自己的地址，不会有任何节点在那里。
     *
     * 网关侧一直在广播 `_galaxy._tcp`（`galaxy_gateway/bootstrap/lifecycle.py`），
     * 手表侧也一直在听 —— 只有手机端没接这条线，才退化成了猜。
     *
     * 找到就填进输入框并放开保存按钮；找不到不是错误，用户手工输入或走配对短码。
     */
    private fun startAutoDiscovery() {
        lifecycleScope.launch {
            // 先问 mDNS：网关自己会说它在哪，不需要猜。
            val found = com.ufo.galaxy.network.GatewayDiscovery(this@FirstTimeSetupActivity).discover()
            val discovered = found?.host ?: withContext(Dispatchers.IO) {
                LOOPBACK_FALLBACK_HOSTS.firstOrNull { host ->
                    Log.d(TAG, "Auto-discover: 回落试环回 $host:$DISCOVER_PORT")
                    testHostConnectivity(host, DISCOVER_PORT)
                }
            }
            val discoveredPort = found?.port ?: DISCOVER_PORT

            if (discovered != null) {
                etHost.setText(discovered)
                etPort.setText(discoveredPort.toString())
                btnSave.isEnabled = true
                Toast.makeText(
                    this@FirstTimeSetupActivity,
                    "已自动发现网关 $discovered:$discoveredPort",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Log.i(TAG, "Auto-discover: 未发现网关，等待手工输入")
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
