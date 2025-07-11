package com.simplemobiletools.dialer.services

import android.app.KeyguardManager
import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.simplemobiletools.dialer.activities.CallActivity
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.isOutgoing
import com.simplemobiletools.dialer.extensions.powerManager
import com.simplemobiletools.dialer.helpers.CallManager
import com.simplemobiletools.dialer.helpers.CallNotificationManager
import com.simplemobiletools.dialer.helpers.NoCall
import android.util.Log
import android.telecom.VideoProfile
import android.os.Handler
import android.os.Looper

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val autoAnswerDelay = 1000L
    private val autoAnswerHandler = Handler(Looper.getMainLooper())
    private var autoAnswerRunnable: Runnable? = null
    private var autoEndRunnable: Runnable? = null

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
                Log.i("AutoCall", "Call ended")
            } else {
                callNotificationManager.setupNotification()
                if (state == Call.STATE_ACTIVE) {
                    Log.i("AutoCall", "Call picked up")
                }
            }
        }
    }

    private fun scheduleAutoAnswer(call: Call) {
        cancelAutoAnswer() // Cancel any pending auto-answer
        
        autoAnswerRunnable = Runnable {
            try {
                if (call.state == Call.STATE_RINGING) {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    Log.d("AutoAnswer", "Call answered automatically")
                }
            } catch (e: SecurityException) {
                Log.e("AutoAnswer", "Permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e("AutoAnswer", "Error answering call: ${e.message}")
            }
        }
        
        autoAnswerHandler.postDelayed(autoAnswerRunnable!!, autoAnswerDelay)
    }

    private fun cancelAutoAnswer() {
        autoAnswerRunnable?.let {
            autoAnswerHandler.removeCallbacks(it)
            autoAnswerRunnable = null
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        if (call.state == Call.STATE_RINGING) {
            Log.i("AutoCall", "Incoming call detected")
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            //scheduleAutoAnswer(call)
        }

        val isScreenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        if (!powerManager.isInteractive || call.isOutgoing() || isScreenLocked || config.alwaysShowFullscreen) {
            try {
                callNotificationManager.setupNotification(true)
                startActivity(CallActivity.getStartIntent(this))
            } catch (e: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        } else {
            callNotificationManager.setupNotification()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}
