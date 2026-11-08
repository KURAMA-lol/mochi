package com.sahil.chibi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Makes Nana selectable as the phone's default digital assistant (power-button / corner swipe / hold-home gesture). */
class NanaAssistService : VoiceInteractionService()

class NanaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NanaSession(this)
}

class NanaSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            context.startForegroundService(Intent(context, CompanionService::class.java).setAction(CompanionService.ACT_TALK))
        } catch (e: Exception) {}
        hide()
    }
}

/** Required by the assistant declaration; Nana does not provide speech recognition itself. */
class NanaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) {}
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
