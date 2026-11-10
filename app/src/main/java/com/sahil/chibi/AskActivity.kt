package com.sahil.chibi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** "Ask Nana": select text in any app, or share text to Nana, and she explains / summarizes it. */
class AskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else if (t.isNotEmpty()) {
            try {
                startForegroundService(
                    Intent(this, CompanionService::class.java)
                        .setAction(CompanionService.ACT_ASK)
                        .putExtra("text", t.take(4000))
                )
            } catch (e: Exception) {}
        }
        finish()
    }
}
