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

/**
 * 手机上回答中心智能体的提问(V2 设计文档第 8 节第 2 步):`decision_request` 弹成一条通知,
 * 选项不多于三个时直接放成按钮,点开通知是一个小对话框列出全部选项;人点哪个就把哪个回给中心
 * ([DecisionFrames.humanInputFrame]),通知收起。别处先答了、超时了,中心发 `decision_withdraw`,这里收起通知。
 *
 * 一条提问只答一次([DecisionBook]):撤回了、过了 `timeout_ms`、已经答过的,再点也不发 ——
 * 决定结束后才到的回答,中心会当成人说的一句话交给智能体。通知到期自己收起(`setTimeoutAfter`)。
 *
 * 只有通知打得开时手机才声明能回答提问([PhoneSelfDescription])—— 弹不出来的提问只会让中心白等。
 */
object PhoneDecisions {
    private const val TAG = "PhoneDecisions"
    internal const val EXTRA_DECISION = "decision_id"
    internal const val EXTRA_OPTION = "option_id"
    /** 按钮里带着这条提问的到期时刻:进程重启后账是空的,靠它判断还算不算数。 */
    internal const val EXTRA_DEADLINE = "deadline_ms"

    @Volatile
    private var client: GalaxyWebSocketClient? = null

    /** 摆着的提问各自还算不算数。 */
    private val book = DecisionBook()

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
                DecisionFrames.parseWithdraw(frameJson)?.let { withdraw(app, it) }
            }
        })
    }

    internal fun pending(decisionId: String): DecisionFrames.Decision? = book.peek(decisionId, System.currentTimeMillis())

    /**
     * 人点了一个选项:回给中心,收起通知。撤回了、过期了、答过了就只收起通知、不发。
     * [deadlineHintMs] 是按钮里带的到期时刻(进程重启后账是空的时候用)。
     */
    internal fun answer(
        context: Context,
        decisionId: String,
        optionId: String?,
        text: String? = null,
        deadlineHintMs: Long? = null,
    ) {
        val c = client
        if (c == null) {
            Log.w(TAG, "answer before install; dropped $decisionId")
            dismiss(context, decisionId)
            return
        }
        if (!book.claim(decisionId, System.currentTimeMillis(), deadlineHintMs)) {
            Log.i(TAG, "decision $decisionId already closed (answered, withdrawn or timed out); reply not sent")
            dismiss(context, decisionId)
            return
        }
        val frame = DecisionFrames.humanInputFrame(c.currentDeviceId(), decisionId, optionId, text, System.currentTimeMillis())
        if (!c.sendJson(frame.toString())) Log.w(TAG, "human_input for $decisionId was not sent (not connected)")
        dismiss(context, decisionId)
    }

    private fun show(context: Context, d: DecisionFrames.Decision) {
        if (!book.open(d, System.currentTimeMillis())) return
        val deadline = book.deadlineOf(d.decisionId) ?: 0L
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
        d.timeoutMs?.let { builder.setTimeoutAfter(it) }
        for (option in DecisionFrames.buttons(d)) {
            val reply = PendingIntent.getBroadcast(
                context,
                requestCode(d.decisionId, option.id),
                Intent(context, DecisionReplyReceiver::class.java)
                    .putExtra(EXTRA_DECISION, d.decisionId)
                    .putExtra(EXTRA_OPTION, option.id)
                    .putExtra(EXTRA_DEADLINE, deadline),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, option.label, reply)
        }
        runCatching { nm.notify(notificationId(d.decisionId), builder.build()) }
            .onFailure { Log.w(TAG, "decision notification not shown: ${it.message}") }
    }

    /** 中心撤回了(别处答了、超时、取消、被顶掉):记成结过,收起通知(进程重启过、账里没有也收)。 */
    private fun withdraw(context: Context, decisionId: String) {
        book.withdraw(decisionId)
        dismiss(context, decisionId)
    }

    private fun dismiss(context: Context, decisionId: String) {
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
        val deadline = intent.getLongExtra(PhoneDecisions.EXTRA_DEADLINE, 0L).takeIf { it > 0 }
        if (decisionId.isNotBlank() && option.isNotBlank()) PhoneDecisions.answer(context, decisionId, option, null, deadline)
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
