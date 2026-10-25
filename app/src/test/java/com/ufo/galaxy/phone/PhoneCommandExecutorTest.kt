package com.ufo.galaxy.phone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufo.galaxy.phone.PhoneActionCatalog.Need
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中心经 `devices__invoke` 让手机直接做一件事(`command` → `command_result`,和手表同一个约定)。
 *
 * 钉住的:只报做得了的;不认识的、没给权限的、参数不对的立刻回原因(不让中心干等 30 秒);
 * 发短信、打电话、加日程只备好、要人点;结果帧的形状是 V2 认领的那种。
 */
class PhoneCommandExecutorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMs = LocalDateTime.of(2026, 10, 25, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private class FakeEffects(var granted: Set<Need> = Need.values().toSet()) : PhoneEffects {
        val calls = mutableListOf<String>()
        val tapped = mutableListOf<Prepared>()
        var calendarRange: Pair<Long, Long>? = null
        var openResult = AppOpened.OPENED
        var fix = LocationFix(31.23, 121.47, 12f, 0L, "gps")
        var locationFails: String? = null

        override fun available(need: Need) = need in granted
        override fun notify(title: String, text: String) { calls += "notify:$title:$text" }
        override suspend fun location(): LocationFix {
            locationFails?.let { throw PhoneActionFailed(it) }
            return fix
        }
        override suspend fun sensor(name: String) = SensorReading(name, listOf(1f, 2f, 3f), "m/s²")
        override fun contacts(query: String, limit: Int) =
            listOf(Contact("张三", "13800000000"), Contact("张三丰", "13900000000")).filter { query in it.name }.take(limit)
        override fun calendar(fromMs: Long, toMs: Long, limit: Int): List<CalendarEvent> {
            calendarRange = fromMs to toMs
            return listOf(CalendarEvent("周会", fromMs + 3_600_000, fromMs + 7_200_000, "3 楼", false))
        }
        override fun askToTap(prepared: Prepared) { tapped += prepared }
        override fun findApp(app: String) = if (app == "微信") Prepared.OpenApp("com.tencent.mm", "微信") else null
        override fun openApp(app: Prepared.OpenApp): AppOpened { calls += "open:${app.packageName}"; return openResult }
        override suspend fun screenshotJpeg() = byteArrayOf(1, 2, 3)
    }

    private fun exec(fx: FakeEffects) = PhoneCommandExecutor(fx, now = { nowMs }, zone = { zone })

    private fun params(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `only actions whose needs are met are reported`() {
        assertEquals(PhoneActionCatalog.ACTIONS.map { it.name }, PhoneActionCatalog.reportable { true })
        val noPermissions = PhoneActionCatalog.reportable { false }
        assertEquals(listOf("sensor_data"), noPermissions)
        val withNotifications = PhoneActionCatalog.reportable { it == Need.NOTIFICATIONS }
        assertTrue(withNotifications.containsAll(listOf("notification", "sms", "phone_call", "calendar_add", "app_control")))
        assertFalse("没给位置权限就不报位置", "location" in withNotifications)
        assertFalse("contacts" in withNotifications || "calendar" in withNotifications || "screen_capture" in withNotifications)
    }

    @Test
    fun `nothing that cannot be done here is in the catalog`() {
        val names = PhoneActionCatalog.ACTIONS.map { it.name }
        listOf("camera", "voice_input", "automation", "goal_execution").forEach { assertFalse(it in names) }
        assertTrue(PhoneActionCatalog.ACTIONS.all { it.description.isNotBlank() && it.description.length <= 200 })
    }

    @Test
    fun `unknown and not-offered actions answer at once with a reason`() = runTest {
        val fx = FakeEffects()
        val goal = exec(fx).execute("goal_execution", JsonObject())
        assertFalse(goal.success)
        assertTrue(goal.error!!.contains("devices__delegate"))
        assertTrue(exec(fx).execute("camera", JsonObject()).error!!.contains("拍照"))
        assertTrue(exec(fx).execute("fly", JsonObject()).error!!.contains("不认识"))
        assertTrue(fx.calls.isEmpty())
    }

    @Test
    fun `an action whose permission is missing says which one and does nothing`() = runTest {
        val fx = FakeEffects(granted = setOf(Need.NOTIFICATIONS))
        val r = exec(fx).execute("location", JsonObject())
        assertFalse(r.success)
        assertTrue(r.error!!.contains("位置"))
    }

    @Test
    fun `notification needs text and is shown`() = runTest {
        val fx = FakeEffects()
        assertFalse(exec(fx).execute("notification", params("""{"title":"提醒"}""")).success)
        val r = exec(fx).execute("notification", params("""{"text":"三点开会"}"""))
        assertTrue(r.success)
        assertEquals(listOf("notify:Galaxy:三点开会"), fx.calls)
    }

    @Test
    fun `location reports coordinates and how old the fix is`() = runTest {
        val fx = FakeEffects()
        fx.fix = fx.fix.copy(timeMs = nowMs - 30_000)
        val r = exec(fx).execute("location", JsonObject())
        assertTrue(r.success)
        assertEquals(31.23, r.data["latitude"].asDouble, 1e-9)
        assertEquals(30, r.data["age_s"].asInt)
        fx.locationFails = "手机在后台拿不到位置"
        val failed = exec(fx).execute("location", JsonObject())
        assertFalse(failed.success)
        assertEquals("手机在后台拿不到位置", failed.error)
    }

    @Test
    fun `contacts are searched by name and an empty result says so`() = runTest {
        val fx = FakeEffects()
        val r = exec(fx).execute("contacts", params("""{"query":"张三","limit":1}"""))
        assertEquals(1, r.data["contacts"].asJsonArray.size())
        val none = exec(fx).execute("contacts", params("""{"query":"李四"}"""))
        assertTrue(none.success && none.data["contacts"].asJsonArray.size() == 0 && none.data.has("note"))
        assertFalse(exec(fx).execute("contacts", JsonObject()).success)
    }

    @Test
    fun `calendar defaults to the next 24 hours and reads local or zoned times`() = runTest {
        val fx = FakeEffects()
        val r = exec(fx).execute("calendar", JsonObject())
        assertEquals(nowMs to nowMs + 24 * 3_600_000L, fx.calendarRange)
        assertEquals("2026-10-25T10:00", r.data["events"].asJsonArray[0].asJsonObject["start"].asString)

        exec(fx).execute("calendar", params("""{"from":"2026-10-25T18:00","to":"2026-10-25T20:00+08:00"}"""))
        val from = LocalDateTime.of(2026, 10, 25, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(from to from + 2 * 3_600_000L, fx.calendarRange)

        assertFalse(exec(fx).execute("calendar", params("""{"from":"明天","to":"后天"}""")).success)
        assertFalse(exec(fx).execute("calendar", params("""{"from":"2026-10-26","to":"2026-10-25"}""")).success)
    }

    @Test
    fun `sms calls and new events are only prepared for the person to tap`() = runTest {
        val fx = FakeEffects()
        val sms = exec(fx).execute("sms", params("""{"to":"138 0000 0000","text":"我晚点到"}"""))
        assertTrue(sms.success && sms.data["waiting_for_person"].asBoolean)
        assertEquals(Prepared.Sms("13800000000", "我晚点到"), fx.tapped[0])

        exec(fx).execute("phone_call", params("""{"number":"+86-138-0000-0000"}"""))
        assertEquals(Prepared.Dial("+8613800000000"), fx.tapped[1])

        exec(fx).execute("calendar_add", params("""{"title":"牙医","start":"2026-10-26T15:00"}"""))
        assertTrue(fx.tapped[2] is Prepared.CalendarInsert)

        assertFalse("名字不是号码", exec(fx).execute("sms", params("""{"to":"张三","text":"hi"}""")).success)
        assertFalse(exec(fx).execute("phone_call", JsonObject()).success)
        assertEquals(3, fx.tapped.size)
    }

    @Test
    fun `opening an app opens it or waits for a tap and a missing app says so`() = runTest {
        val fx = FakeEffects()
        val opened = exec(fx).execute("app_control", params("""{"app":"微信"}"""))
        assertTrue(opened.data["opened"].asBoolean)
        fx.openResult = AppOpened.WAITING_FOR_TAP
        assertTrue(exec(fx).execute("app_control", params("""{"app":"微信"}""")).data["waiting_for_person"].asBoolean)
        val missing = exec(fx).execute("app_control", params("""{"app":"不存在"}"""))
        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("没找到"))
    }

    @Test
    fun `a screenshot comes back as base64 jpeg`() = runTest {
        val r = exec(FakeEffects()).execute("screen_capture", JsonObject())
        assertEquals("AQID", r.data["image_base64"].asString)
        assertEquals("jpeg", r.data["format"].asString)
    }

    @Test
    fun `an unexpected error becomes a failed result instead of silence`() = runTest {
        val fx = object : PhoneEffects by FakeEffects() {
            override suspend fun sensor(name: String): SensorReading = throw IllegalStateException("传感器坏了")
        }
        val r = PhoneCommandExecutor(fx).execute("sensor_data", params("""{"sensor":"light"}"""))
        assertFalse(r.success)
        assertTrue(r.error!!.contains("传感器坏了"))
    }

    // ── 帧 ──────────────────────────────────────────────────────────────────

    @Test
    fun `a direct command is told apart from an old goal-carrying command`() {
        val direct = params("""{"type":"command","command_id":"cmd_1","command":"location","params":{"a":1}}""")
        assertTrue(DeviceCommandFrames.isDeviceCommand(direct))
        assertEquals("cmd_1", DeviceCommandFrames.commandId(direct))
        assertEquals("location", DeviceCommandFrames.action(direct))
        assertEquals(1, DeviceCommandFrames.params(direct)["a"].asInt)

        val legacy = params("""{"type":"command","payload":{"task_id":"t1","goal":"打开设置"}}""")
        assertFalse(DeviceCommandFrames.isDeviceCommand(legacy))
        assertFalse(
            "没有 command_id 就认领不回来,不是直接动作",
            DeviceCommandFrames.isDeviceCommand(params("""{"type":"command","command":"打开设置","payload":{"goal":"打开设置"}}""")),
        )
        assertFalse(DeviceCommandFrames.isDeviceCommand(params("""{"type":"task_assign","command_id":"x","command":"y"}""")))
        assertTrue(DeviceCommandFrames.params(params("""{"type":"command","command_id":"c","command":"x"}""")).size() == 0)
    }

    @Test
    fun `the result frame has the shape the center claims by command_id`() {
        val ok = DeviceCommandFrames.resultFrame(
            "phone-1", "cmd_1", CommandOutcome(true, JsonObject().apply { addProperty("latitude", 1.5) }, null), 42L,
        )
        assertEquals("command_result", ok["type"].asString)
        assertEquals("cmd_1", ok["command_id"].asString)
        assertEquals("cmd_1", ok["correlation_id"].asString)
        assertEquals("phone-1", ok["device_id"].asString)
        assertTrue(ok["payload"].asJsonObject["success"].asBoolean)
        assertEquals(1.5, ok["payload"].asJsonObject["latitude"].asDouble, 0.0)

        val bad = DeviceCommandFrames.resultFrame("phone-1", "cmd_2", CommandOutcome(false, JsonObject(), "没给位置权限"), 42L)
        assertFalse(bad["payload"].asJsonObject["success"].asBoolean)
        assertEquals("没给位置权限", bad["payload"].asJsonObject["error"].asString)
    }
}
