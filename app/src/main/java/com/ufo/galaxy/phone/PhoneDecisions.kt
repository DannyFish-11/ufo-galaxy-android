package com.ufo.galaxy.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.network.GalaxyWebSocketClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机上回答中心智能体的提问(V2 设计文档第 8 节第 2 步):`decision_request` 弹成一条通知,
 * 选项不多于三个时直接放成按钮,点开通知是一个小对话框列出全部选项;人点哪个就把哪个回给中心
 * ([DecisionFrames.humanInputFrame]),通知收起。别处先答了、超时了,中心发 `decision_withdraw`,这里收起通知。
 *
 * 只有通知打得开时手机才声明能回答提问([PhoneSelfDescription])—— 弹不出来的提问只会让中心白等。
 */
object PhoneDecisions {
    private const val TAG = "PhoneDecisions"
    internal const val EXTRA_DECISION = "decision_id"
    internal const val EXTRA_OPTION = "option_id"

    @Volatile
    private var client: GalaxyWebSocketClient? = null

    /** 还没答的提问(点开通知时要用)。 */
    private val open = ConcurrentHashMap<String, DecisionFrames.Decision>()
    private val seq = AtomicLong(0)

    fun install(client: GalaxyWebSocketClient, context: Context) {
        this.client = client
        val app = context.applicationContext
        client.addListener(object : GalaxyWebSocketClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected() = Unit
            override fun onMessage(message: String) = Unit
            override fun onError(error: String) = Unit

            override fun onDecisionFrame(frameJson: String) {
                DecisionFrames.parseRequest(frameJson)?.let { show(app, it) }
                DecisionFrames.parseWithdraw(frameJson)?.let { dismiss(app, it) }
            }
        })
    }

    internal fun pending(decisionId: String): DecisionFrames.Decision? = open[decisionId]

    /** 人点了一个选项:回给中心,收起通知。 */
    internal fun answer(context: Context, decisionId: String, optionId: String?, text: String? = null) {
        val c = client
        if (c == null) {
            Log.w(TAG, "answer before install; dropped $decisionId")
            return
        }
        val frame = DecisionFrames.humanInputFrame(
            c.currentDeviceId(), decisionId, optionId, text, seq.incrementAndGet(), System.currentTimeMillis(),
        )
        if (!c.sendJson(frame.toString())) Log.w(TAG, "human_input for $decisionId was not sent (not connected)")
        dismiss(context, decisionId)
    }

    private fun show(context: Context, d: DecisionFrames.Decision) {
        open[d.decisionId] = d
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val tap = PendingIntent.getActivity(
            context,
            requestCode(d.decisionId, ""),
            Intent(context, DecisionActivity::class.java)
                .putExtra(EXTRA_DECISION, d.decisionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, UFOGalaxyApplication.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(d.title)
            .setContentText(d.summary.ifBlank { "点开回答" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(d.summary.ifBlank { "点开回答" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tap)
            .setAutoCancel(false)
        for (option in DecisionFrames.buttons(d)) {
            val reply = PendingIntent.getBroadcast(
                context,
                requestCode(d.decisionId, option.id),
                Intent(context, DecisionReplyReceiver::class.java)
                    .putExtra(EXTRA_DECISION, d.decisionId)
                    .putExtra(EXTRA_OPTION, option.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, option.label, reply)
        }
        runCatching { nm.notify(notificationId(d.decisionId), builder.build()) }
            .onFailure { Log.w(TAG, "decision notification not shown: ${it.message}") }
    }

    private fun dismiss(context: Context, decisionId: String) {
        open.remove(decisionId)
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(decisionId))
    }

    private fun notificationId(decisionId: String) = 0x5D000000 or (decisionId.hashCode() and 0x00FFFFFF)

    private fun requestCode(decisionId: String, optionId: String) = "$decisionId/$optionId".hashCode()
}

/** 通知上的选项按钮。 */
class DecisionReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decisionId = intent.getStringExtra(PhoneDecisions.EXTRA_DECISION).orEmpty()
        val option = intent.getStringExtra(PhoneDecisions.EXTRA_OPTION).orEmpty()
        if (decisionId.isNotBlank() && option.isNotBlank()) PhoneDecisions.answer(context, decisionId, option)
    }
}

/** 点开提问通知:一个小对话框,列出全部选项;没有选项的提问给一个输入框。 */
class DecisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val decisionId = intent.getStringExtra(PhoneDecisions.EXTRA_DECISION).orEmpty()
        val d = PhoneDecisions.pending(decisionId)
        if (d == null) {
            // 已经在别处答了、或撤回了
            finish()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (d.summary.isNotBlank()) body.addView(TextView(this).apply { text = d.summary })
        lateinit var dialog: AlertDialog
        if (d.options.isEmpty()) {
            val input = android.widget.EditText(this).apply { hint = "回一句话" }
            body.addView(input)
            dialog = AlertDialog.Builder(this)
                .setTitle(d.title)
                .setView(body)
                .setPositiveButton("发送") { _, _ -> PhoneDecisions.answer(this, d.decisionId, null, input.text.toString()) }
                .setNegativeButton("稍后", null)
                .create()
        } else {
            for (option in d.options) {
                body.addView(Button(this).apply {
                    text = option.label
                    setOnClickListener {
                        PhoneDecisions.answer(this@DecisionActivity, d.decisionId, option.id)
                        dialog.dismiss()
                    }
                })
            }
            dialog = AlertDialog.Builder(this).setTitle(d.title).setView(body).setNegativeButton("稍后", null).create()
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }
}
