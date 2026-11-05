package com.ufo.galaxy.service

import com.ufo.galaxy.service.ForegroundTypes.Kind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机在兜里时智能体也读得到位置:常驻服务在应用可见时带上 location 类型起;在后台(粘性重启、开机)
 * 只用 specialUse —— Android 14 起在后台以 location 起前台服务会抛 SecurityException,常驻服务就起不来了。
 */
class ForegroundTypesTest {

    @Test
    fun `location rides along only when it is granted and the app itself is on screen`() {
        assertEquals(setOf(Kind.SPECIAL_USE, Kind.LOCATION), ForegroundTypes.forConnection(locationGranted = true, fromVisibleUi = true))
        assertEquals(setOf(Kind.SPECIAL_USE), ForegroundTypes.forConnection(locationGranted = true, fromVisibleUi = false))
        assertEquals(setOf(Kind.SPECIAL_USE), ForegroundTypes.forConnection(locationGranted = false, fromVisibleUi = true))
        assertEquals(ForegroundTypes.PLAIN, ForegroundTypes.forConnection(locationGranted = false, fromVisibleUi = false))
    }

    @Test
    fun `coming back to the app adds location once it is granted and the service lacks it`() {
        assertTrue(ForegroundTypes.needsUpgrade(locationGranted = true, running = setOf(Kind.SPECIAL_USE)))
        assertFalse(ForegroundTypes.needsUpgrade(locationGranted = true, running = setOf(Kind.SPECIAL_USE, Kind.LOCATION)))
        assertFalse("没给位置权限不用补", ForegroundTypes.needsUpgrade(locationGranted = false, running = setOf(Kind.SPECIAL_USE)))
        assertFalse("服务没在跑不由这里起", ForegroundTypes.needsUpgrade(locationGranted = true, running = null))
    }

    private fun appDir(): File {
        val candidates = listOf(File("."), File("app"), File("../app"))
        return candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("找不到 app 模块目录,试过:${candidates.map { it.absolutePath }}")
    }

    private fun source(path: String) = File(appDir(), path).readText()

    @Test
    fun `only the app's own screen asks for location and the service can always fall back`() {
        val manifest = source("src/main/AndroidManifest.xml").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val decl = manifest.substringAfter("android:name=\".service.GalaxyConnectionService\"").substringBefore("</service>")
        assertTrue("清单里没声明 location 类型", decl.contains("android:foregroundServiceType=\"specialUse|location\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION"))

        val activity = source("src/main/java/com/ufo/galaxy/ui/MainActivity.kt")
        assertTrue("界面拉起服务时没说自己在前台", activity.contains("GalaxyConnectionService.createVisibleEntryIntent(this)"))
        assertTrue("回到界面时没补 location", activity.substringAfter("override fun onResume()").substringBefore("override fun onPause()").contains("GalaxyConnectionService.upgradeIfNeeded(this)"))
        assertFalse("开机自启是后台,不能说自己在前台", source("src/main/java/com/ufo/galaxy/service/BootReceiver.kt").contains("createVisibleEntryIntent"))

        val service = source("src/main/java/com/ufo/galaxy/service/GalaxyConnectionService.kt")
        val start = service.substringAfter("private fun startForegroundWithTypes(").substringBefore("\n    }\n")
        assertTrue("带 location 起不来时没有退回 specialUse", start.contains("catch (e: SecurityException)") && start.contains("ForegroundTypes.PLAIN"))
        assertTrue(service.substringAfter("override fun onStartCommand(").substringBefore("return START_STICKY").contains("startForegroundWithTypes("))
    }
}
