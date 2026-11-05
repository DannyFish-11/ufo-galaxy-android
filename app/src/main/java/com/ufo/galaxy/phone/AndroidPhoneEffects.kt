package com.ufo.galaxy.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.nlp.AppAliasRegistry
import com.ufo.galaxy.phone.PhoneActionCatalog.Need
import com.ufo.galaxy.service.HardwareKeyListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PhoneEffects] 在 Android 上的实现:通知、定位、传感器、通讯录、日历、备好让人点的事、打开应用、截屏。
 *
 * 每一项先看权限(人在手机上给了没有),没给就说没给,不去碰。发短信、打电话、加日程用系统自己的界面
 * (`ACTION_SENDTO` / `ACTION_DIAL` / `ACTION_INSERT`),手机只把内容填好、弹通知,**人点了**才发、才拨、才存 ——
 * 所以不申请 `SEND_SMS` / `CALL_PHONE` / `WRITE_CALENDAR`。
 */
class AndroidPhoneEffects(private val context: Context) : PhoneEffects {

    override fun available(need: Need): Boolean = when (need) {
        Need.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        Need.LOCATION -> granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        Need.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
        Need.CALENDAR -> granted(Manifest.permission.READ_CALENDAR)
        Need.SCREEN -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && HardwareKeyListener.instance != null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── 通知 ────────────────────────────────────────────────────────────────

    override fun notify(title: String, text: String) {
        post(title, text, UFOGalaxyApplication.CHANNEL_MESSAGES, null, NEXT_ID.incrementAndGet())
    }

    private fun post(title: String, text: String, channel: String, tap: PendingIntent?, id: Int) {
        if (!available(Need.NOTIFICATIONS)) throw PhoneActionFailed("手机上没打开 Galaxy 的通知")
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (tap != null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { tap?.let { setContentIntent(it) } }
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: throw PhoneActionFailed("这台手机弹不了通知")
        nm.notify(id, n)
    }

    // ── 定位 ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // 下面先核了位置权限
    override suspend fun location(): LocationFix {
        if (!available(Need.LOCATION)) throw PhoneActionFailed("手机上没给 Galaxy 位置权限")
        val lm = context.getSystemService(LocationManager::class.java) ?: throw PhoneActionFailed("这台手机没有定位服务")
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) throw PhoneActionFailed("手机的定位是关着的")
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_LOCATION_MS) return last.toFix()
        val provider = PREFERRED_PROVIDERS.firstOrNull { it in providers } ?: providers.first()
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) currentLocation(lm, provider) else null
        return (fresh ?: last)?.toFix()
            ?: throw PhoneActionFailed("手机一时定不到位(在室内,或 Galaxy 在后台而位置权限不是「始终允许」时常见)")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission") // 调用方已核位置权限
    private suspend fun currentLocation(lm: LocationManager, provider: String): Location? =
        withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        }

    private fun Location.toFix() =
        LocationFix(latitude, longitude, if (hasAccuracy()) accuracy else null, time, provider.orEmpty())

    // ── 传感器 ──────────────────────────────────────────────────────────────

    override suspend fun sensor(name: String): SensorReading {
        val (type, unit) = SENSORS[name]
            ?: throw PhoneActionFailed("不认识这个传感器:$name;能读的有 ${SENSORS.keys.joinToString("、")}")
        val sm = context.getSystemService(SensorManager::class.java) ?: throw PhoneActionFailed("这台手机读不了传感器")
        val sensor = sm.getDefaultSensor(type) ?: throw PhoneActionFailed("这台手机没有 $name 传感器")
        val values = withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine<FloatArray> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.copyOf())
                    }

                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    cont.resumeWithException(PhoneActionFailed("$name 传感器开不起来"))
                }
            }
        } ?: throw PhoneActionFailed("$name 传感器 ${SENSOR_TIMEOUT_MS / 1000} 秒内没给读数")
        return SensorReading(name, values.toList(), unit)
    }

    // ── 通讯录、日历 ────────────────────────────────────────────────────────

    override fun contacts(query: String, limit: Int): List<Contact> {
        if (!available(Need.CONTACTS)) throw PhoneActionFailed("手机上没给 Galaxy 通讯录权限")
        val out = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(0).orEmpty()
                val number = c.getString(1).orEmpty()
                if (number.isNotBlank() && out.none { it.name == name && it.number == number }) out += Contact(name, number)
            }
        }
        return out
    }

    override fun calendar(fromMs: Long, toMs: Long, limit: Int): List<CalendarEvent> {
        if (!available(Need.CALENDAR)) throw PhoneActionFailed("手机上没给 Galaxy 日历权限")
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val out = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += CalendarEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getInt(4) == 1)
            }
        }
        return out
    }

    // ── 要人点一下的事 ──────────────────────────────────────────────────────

    override fun askToTap(prepared: Prepared) {
        val intent = when (prepared) {
            is Prepared.Sms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", prepared.to, null))
                .putExtra("sms_body", prepared.text)
            is Prepared.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", prepared.number, null))
            is Prepared.CalendarInsert -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, prepared.title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, prepared.beginMs)
                prepared.endMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                prepared.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            is Prepared.OpenApp -> context.packageManager.getLaunchIntentForPackage(prepared.packageName)
                ?: throw PhoneActionFailed("「${prepared.label}」打不开")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = NEXT_ID.incrementAndGet()
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (prepared) {
            is Prepared.Sms -> "智能体帮你备好了一条短信"
            is Prepared.Dial -> "智能体帮你备好了拨号"
            is Prepared.CalendarInsert -> "智能体帮你备好了一条日程"
            is Prepared.OpenApp -> "智能体想帮你打开「${prepared.label}」"
        }
        post(title, "${prepared.summary}。点这里确认", UFOGalaxyApplication.CHANNEL_ALERTS, tap, id)
    }

    // ── 应用 ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // 带 Flags 的重载要 API 33;minSdk 26
    override fun findApp(app: String): Prepared.OpenApp? {
        val pm = context.packageManager
        val wanted = AppAliasRegistry.resolveApp(app).trim()
        if (pm.getLaunchIntentForPackage(wanted) != null) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(wanted, 0)).toString() }.getOrDefault(wanted)
            return Prepared.OpenApp(wanted, label)
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val hit = apps.firstOrNull { it.second.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.second.contains(wanted, ignoreCase = true) }
        return hit?.let { Prepared.OpenApp(it.first, it.second) }
    }

    override fun openApp(app: Prepared.OpenApp): AppOpened {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: throw PhoneActionFailed("「${app.label}」打不开")
        // Android 10 起,应用在后台不能直接起界面;有悬浮窗权限的例外。没有就弹通知让人点。
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return AppOpened.OPENED
        }
        askToTap(app)
        return AppOpened.WAITING_FOR_TAP
    }

    // ── 截屏 ────────────────────────────────────────────────────────────────

    override suspend fun screenshotJpeg(): ByteArray = withContext(Dispatchers.IO) {
        val service = HardwareKeyListener.instance ?: throw PhoneActionFailed("Galaxy 的无障碍服务没开,截不了屏")
        val outcome = service.capture()
        val bytes = outcome.bytes?.takeIf { it.isNotEmpty() }
            ?: throw PhoneActionFailed(if (outcome.timedOut) "截屏超时了" else "截屏没成功(错误码 ${outcome.errorCode})")
        shrink(bytes)
    }

    /** 太大就缩到长边 [MAX_SCREENSHOT_EDGE]、重压一次 —— 截图要过网络进模型,原图没必要。 */
    private fun shrink(jpeg: ByteArray): ByteArray {
        if (jpeg.size <= MAX_SCREENSHOT_BYTES) return jpeg
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val scale = MAX_SCREENSHOT_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else {
            bmp
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        }
    }

    companion object {
        private val NEXT_ID = AtomicInteger(42_000)
        private const val FRESH_LOCATION_MS = 2 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MS = 20_000L
        private const val SENSOR_TIMEOUT_MS = 3_000L
        private const val MAX_SCREENSHOT_BYTES = 300 * 1024
        private const val MAX_SCREENSHOT_EDGE = 1280
        private val PREFERRED_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")

        private val SENSORS: Map<String, Pair<Int, String>> = mapOf(
            "accelerometer" to (Sensor.TYPE_ACCELEROMETER to "m/s²"),
            "gyroscope" to (Sensor.TYPE_GYROSCOPE to "rad/s"),
            "magnetic_field" to (Sensor.TYPE_MAGNETIC_FIELD to "μT"),
            "light" to (Sensor.TYPE_LIGHT to "lx"),
            "pressure" to (Sensor.TYPE_PRESSURE to "hPa"),
            "proximity" to (Sensor.TYPE_PROXIMITY to "cm"),
            "ambient_temperature" to (Sensor.TYPE_AMBIENT_TEMPERATURE to "°C"),
            "relative_humidity" to (Sensor.TYPE_RELATIVE_HUMIDITY to "%"),
        )
    }
}
