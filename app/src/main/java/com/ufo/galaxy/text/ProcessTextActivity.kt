package com.ufo.galaxy.text

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ufo.galaxy.ui.MainActivity

/**
 * 全局文本入口 —— 任意 App 里选中文字 → 系统菜单"问 Galaxy"。
 *
 * 透明中转 Activity:接住 ACTION_PROCESS_TEXT 的选中文本,转交给
 * MainActivity 预填进聊天输入(EXTRA_PREFILL),随即结束自身。
 * 这是"全系统入口"——不必先打开 App 即可把任意文字丢给 Galaxy。
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (selected.isNotEmpty()) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = ACTION_PREFILL_CHAT
                    putExtra(EXTRA_PREFILL, selected)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
        finish()
    }

    companion object {
        const val ACTION_PREFILL_CHAT = "com.ufo.galaxy.ACTION_PREFILL_CHAT"
        const val EXTRA_PREFILL = "com.ufo.galaxy.EXTRA_PREFILL"
    }
}
