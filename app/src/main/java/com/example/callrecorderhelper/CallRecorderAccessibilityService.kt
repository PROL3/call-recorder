package com.example.callrecorderhelper

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecorderAccessibilityService : AccessibilityService() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private lateinit var telephonyManager: TelephonyManager
    private var telephonyCallback: TelephonyCallback? = null

    companion object {
        private const val TAG = "CallRecorderService"
        private const val CHANNEL_ID = "call_recorder_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        createNotificationChannel()
        registerCallStateListener()
    }

    private fun registerCallStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyCallback = callback
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // שיחה נענתה או יוצאת פעילה
                if (!isRecording) {
                    startCallRecording()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // שיחה הסתיימה
                if (isRecording) {
                    stopCallRecording()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // טלפון מצלצל
            }
        }
    }

    private fun startCallRecording() {
        try {
            // העלאת השירות ל-Foreground עם סוג מיקרופון כדי למנוע חסימה באנדרואיד 14+
            val notification = buildNotification("מקליט שיחה פעילה...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val outputDir = File(getExternalFilesDir(null), "Recordings")
            if (!outputDir.exists()) outputDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(outputDir, "Call_$timestamp.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // VOICE_RECOGNITION הוא המפתח לעקיפת החסימה מתוך שירות נגישות
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d(TAG, "Recording started: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stopCallRecording()
        }
    }

    private fun stopCallRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.d(TAG, "Recording stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
            isRecording = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("מקליט שיחות פעיל")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // אין צורך לממש לוגיקה כאן עבור שיחות רגילות, אבל ניתן להשתמש בזה
        // כדי לזהות שיחות WhatsApp / Telegram לפי Package Name של האירוע!
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        }
        stopCallRecording()
    }
}