package com.trackstudio.rfidmanager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log

class RfidSoundPlayer(context: Context) {

    companion object {
        private const val TAG = "RfidSoundPlayer"
        const val SOUND_BEEP = 1
        const val SOUND_ERROR = 2
    }

    private val appContext = context.applicationContext
    private val soundMap = HashMap<Int, Int>()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var soundPool: SoundPool? = null

    fun init() {
        // Idempotent: a second init() must not leak the previous pool.
        release()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(audioAttributes)
            .build()
            .apply {
                soundMap[SOUND_BEEP] = load(appContext, R.raw.barcodebeep, 1)
                soundMap[SOUND_ERROR] = load(appContext, R.raw.serror, 1)
            }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundMap.clear()
    }

    fun play(id: Int, rate: Float = 1f) {
        val soundId = soundMap[id] ?: return
        val pool = soundPool ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        try {
            pool.play(soundId, volume, volume, 1, 0, rate.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play sound $id", e)
        }
    }
}
