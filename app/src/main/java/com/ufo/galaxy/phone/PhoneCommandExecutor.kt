package com.ufo.galaxy.phone

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlinx.coroutines.CancellationException

/** 一件事没做成;原因是说给人(和智能体)听的。 */
class PhoneActionFailed(message: String) : Exception(message)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMs: Long,
    val provider: String,
)

data class SensorReading(val sensor: String, val values: List<Float>, val unit: String)

data class Contact(val name: String, val number: String)

data class CalendarEvent(
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val location: String,
    val allDay: Boolean,
)

/** 要人在手机上点一下才真的做的事。手机只把它备好、弹通知。 */
sealed class Prepared {
    abstract val summary: String

    data class Sms(val to: String, val text: String) : Prepared() {
        override val summary: String get() = "给 $to 发短信:$text"
    }

    data class Dial(val number: String) : Prepared() {
        override val summary: String get() = "拨打 $number"
    }

    data class CalendarInsert(val title: String, val beginMs: Long, val endMs: Long?, val location: String?) : Prepared() {
        override val summary: String get() = "新日程:$title"
    }

    data class OpenApp(val packageName: String, val label: String) : Prepared() {
        override val summary: String get() = "打开「$label」"
    }
}

enum class AppOpened { OPENED, WAITING_FOR_TAP }

/**
 * 手机上真正动手的那一层。Android 实现见 `AndroidPhoneEffects`;做不成就抛 [PhoneActionFailed],带上原因。
 */
interface PhoneEffects {
    fun available(need: PhoneActionCatalog.Need): Boolean
    fun notify(title: String, text: String)
    suspend fun location(): LocationFix
    suspend fun sensor(name: String): SensorReading
    fun contacts(query: String, limit: Int): List<Contact>
    fun calendar(fromMs: Long, toMs: Long, limit: Int): List<CalendarEvent>

    /** 把 [prepared] 备好,弹一条通知,人点了才做。 */
    fun askToTap(prepared: Prepared)

    /** 按应用名或包名找一个能打开的应用;没装返回 null。 */
    fun findApp(app: String): Prepared.OpenApp?

    /** 能直接打开就打开;不能(在后台、没有悬浮窗权限)就弹通知让人点。 */
    fun openApp(app: Prepared.OpenApp): AppOpened

    suspend fun screenshotJpeg(): ByteArray
}

data class CommandOutcome(val success: Boolean, val data: JsonObject, val error: String?)

/**
 * 中心经 `devices__invoke` 让手机做的一件事:查 [PhoneActionCatalog]、核参数、交给 [PhoneEffects]、
 * 把结果写成 `command_result` 的内容。不认识的、没给权限的、参数不对的**立刻**回原因 ——
 * 中心那边在等,沉默只会让它干等 30 秒再报超时。
 *
 * 纯 Kotlin,不碰 Android。
 */
class PhoneCommandExecutor(
    private val effects: PhoneEffects,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    suspend fun execute(action: String, params: JsonObject): CommandOutcome {
        val spec = PhoneActionCatalog.BY_NAME[action] ?: return fail(notOffered(action))
        val missing = spec.needs.filterNot(effects::available)
        if (missing.isNotEmpty()) return fail("手机上没打开:" + missing.joinToString("、") { it.label })
        return try {
            when (action) {
                "notification" -> notification(params)
                "location" -> location()
                "sensor_data" -> sensor(params)
                "contacts" -> contacts(params)
                "calendar" -> calendar(params)
                "calendar_add" -> calendarAdd(params)
                "sms" -> sms(params)
                "phone_call" -> phoneCall(params)
                "app_control" -> appControl(params)
                "screen_capture" -> screenCapture()
                else -> fail(notOffered(action))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneActionFailed) {
            fail(e.message ?: "没做成")
        } catch (e: Exception) {
            fail("手机上出错了:${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 各个动作 ────────────────────────────────────────────────────────────

    private fun notification(p: JsonObject): CommandOutcome {
        val title = str(p, "title").take(MAX_TITLE).ifBlank { DEFAULT_TITLE }
        val text = str(p, "text").take(MAX_TEXT)
        if (text.isBlank()) return fail("通知要有 text")
        effects.notify(title, text)
        return ok { addProperty("shown", true) }
    }

    private suspend fun location(): CommandOutcome {
        val fix = effects.location()
        return ok {
            addProperty("latitude", fix.latitude)
            addProperty("longitude", fix.longitude)
            fix.accuracyMeters?.let { addProperty("accuracy_m", it) }
            addProperty("age_s", ((now() - fix.timeMs) / 1000).coerceAtLeast(0))
            addProperty("provider", fix.provider)
        }
    }

    private suspend fun sensor(p: JsonObject): CommandOutcome {
        val name = str(p, "sensor").lowercase()
        if (name.isBlank()) return fail("要说读哪个传感器(sensor)")
        val r = effects.sensor(name)
        return ok {
            addProperty("sensor", r.sensor)
            add("values", JsonArray().apply { r.values.forEach { add(it) } })
            addProperty("unit", r.unit)
        }
    }

    private fun contacts(p: JsonObject): CommandOutcome {
        val query = str(p, "query")
        if (query.isBlank()) return fail("要说查谁(query)")
        val found = effects.contacts(query, int(p, "limit", 5, 1, 20))
        return ok {
            add(
                "contacts",
                JsonArray().apply {
                    found.forEach { c -> add(JsonObject().apply { addProperty("name", c.name); addProperty("number", c.number) }) }
                },
            )
            if (found.isEmpty()) addProperty("note", "通讯录里没找到「$query」")
        }
    }

    private fun calendar(p: JsonObject): CommandOutcome {
        val from = time(p, "from") ?: now()
        val to = time(p, "to") ?: (from + DAY_MS)
        if (to <= from) return fail("to 要晚于 from")
        if (to - from > MAX_RANGE_MS) return fail("一次最多查 90 天")
        val events = effects.calendar(from, to, int(p, "limit", 10, 1, 50))
        return ok {
            add(
                "events",
                JsonArray().apply {
                    events.forEach { e ->
                        add(
                            JsonObject().apply {
                                addProperty("title", e.title)
                                addProperty("start", iso(e.beginMs))
                                addProperty("end", iso(e.endMs))
                                if (e.location.isNotBlank()) addProperty("location", e.location)
                                addProperty("all_day", e.allDay)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun calendarAdd(p: JsonObject): CommandOutcome {
        val title = str(p, "title")
        val start = time(p, "start")
        if (title.isBlank() || start == null) return fail("新日程要有 title 和 start")
        val end = time(p, "end")
        if (end != null && end <= start) return fail("end 要晚于 start")
        return waitForTap(Prepared.CalendarInsert(title, start, end, str(p, "location").ifBlank { null }))
    }

    private fun sms(p: JsonObject): CommandOutcome {
        val to = phoneNumber(str(p, "to")) ?: return fail("要给一个能发短信的号码(to);名字先用 contacts 查号码")
        val text = str(p, "text")
        if (text.isBlank()) return fail("短信要有 text")
        return waitForTap(Prepared.Sms(to, text))
    }

    private fun phoneCall(p: JsonObject): CommandOutcome {
        val number = phoneNumber(str(p, "number")) ?: return fail("要给一个号码(number);名字先用 contacts 查号码")
        return waitForTap(Prepared.Dial(number))
    }

    private fun appControl(p: JsonObject): CommandOutcome {
        val app = str(p, "app")
        if (app.isBlank()) return fail("要说打开哪个应用(app)")
        val found = effects.findApp(app) ?: return fail("手机上没找到「$app」这个应用")
        return when (effects.openApp(found)) {
            AppOpened.OPENED -> ok {
                addProperty("opened", true)
                addProperty("app", found.label)
            }
            AppOpened.WAITING_FOR_TAP -> waiting(found)
        }
    }

    private suspend fun screenCapture(): CommandOutcome {
        val jpeg = effects.screenshotJpeg()
        return ok {
            addProperty("format", "jpeg")
            addProperty("bytes", jpeg.size)
            addProperty("image_base64", Base64.getEncoder().encodeToString(jpeg))
        }
    }

    private fun waitForTap(prepared: Prepared): CommandOutcome {
        effects.askToTap(prepared)
        return waiting(prepared)
    }

    private fun waiting(prepared: Prepared): CommandOutcome = ok {
        addProperty("waiting_for_person", true)
        addProperty("said", "已在手机上备好(${prepared.summary}),要人在手机上点一下才会真的做")
    }

    // ── 参数 ────────────────────────────────────────────────────────────────

    private fun str(p: JsonObject, key: String): String {
        val v = p.get(key)
        return if (v != null && v.isJsonPrimitive) v.asString.trim() else ""
    }

    private fun int(p: JsonObject, key: String, default: Int, min: Int, max: Int): Int {
        val v = p.get(key)
        val n = if (v != null && v.isJsonPrimitive) v.asString.trim().toIntOrNull() else null
        return (n ?: default).coerceIn(min, max)
    }

    /** 毫秒数,或 ISO 时间:带时区的、本地时间(按手机时区)、只有日期(当天零点)。认不出抛 [PhoneActionFailed]。 */
    private fun time(p: JsonObject, key: String): Long? {
        val raw = str(p, key)
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return it }
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(raw).atZone(zone()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(raw).atStartOfDay(zone()).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    throw PhoneActionFailed("$key 看不懂:$raw(用 2026-10-25T18:00 这样的写法或毫秒)")
                }
            }
        }
    }

    private fun iso(ms: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()).toString()

    private fun phoneNumber(raw: String): String? {
        val compact = raw.replace(Regex("[\\s()-]"), "")
        return compact.takeIf { PHONE.matches(it) }
    }

    private fun ok(fill: JsonObject.() -> Unit): CommandOutcome = CommandOutcome(true, JsonObject().apply(fill), null)

    private fun fail(reason: String): CommandOutcome = CommandOutcome(false, JsonObject(), reason)

    private fun notOffered(action: String): String = when (action) {
        "goal_execution", "automation" ->
            "整段目标请用 devices__delegate 交给手机上的桥接 Agent(它在后台做、做完回报);直接调用中心只等 30 秒"
        "camera" -> "这一版手机还不能直接拍照(要人在手机前)"
        "voice_input" -> "这一版手机还不能直接听一段话(要人在手机前)"
        else -> "手机不认识这个动作:$action"
    }

    companion object {
        const val DEFAULT_TITLE = "Galaxy"
        const val MAX_TITLE = 60
        const val MAX_TEXT = 500
        private const val DAY_MS = 24L * 3600 * 1000
        private const val MAX_RANGE_MS = 90 * DAY_MS
        private val PHONE = Regex("^\\+?[0-9*#]{3,20}$")
    }
}

/**
 * `command` / `command_result` 帧。和手表同一个约定:V2 `UnifiedConnectionManager.send_command_and_wait`
 * 发 `{type: command, command_id, command, params}`,按 `command_id` 认领回来的结果,读 `payload`。
 */
object DeviceCommandFrames {

    /**
     * 中心让手机直接做一件事:顶层有 `command_id` 和字符串 `command`。旧式 `command` 帧(payload 里是一个目标、
     * 没有 `command_id`)不是这个,照旧当 `task_assign` 走本地循环。
     */
    fun isDeviceCommand(root: JsonObject): Boolean =
        text(root.get("type")) == "command" && text(root.get("command_id")).isNotBlank() &&
            text(root.get("command")).isNotBlank()

    fun commandId(root: JsonObject): String = text(root.get("command_id"))

    fun action(root: JsonObject): String = text(root.get("command"))

    fun params(root: JsonObject): JsonObject = root.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    fun resultFrame(deviceId: String, commandId: String, outcome: CommandOutcome, nowMs: Long): JsonObject =
        JsonObject().apply {
            addProperty("version", "3.0")
            addProperty("type", "command_result")
            addProperty("device_id", deviceId)
            addProperty("command_id", commandId)
            // 网关两种认领方式都认:按 command_id 与按相关 id
            addProperty("correlation_id", commandId)
            addProperty("timestamp", nowMs)
            addProperty("success", outcome.success)
            add(
                "payload",
                JsonObject().apply {
                    addProperty("success", outcome.success)
                    for ((k, v) in outcome.data.entrySet()) add(k, v)
                    if (!outcome.success) addProperty("error", outcome.error ?: "没做成")
                },
            )
        }

    private fun text(e: JsonElement?): String =
        if (e != null && e.isJsonPrimitive && (e as JsonPrimitive).isString) e.asString.trim() else ""
}
