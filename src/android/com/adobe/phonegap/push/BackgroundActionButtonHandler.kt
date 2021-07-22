package com.adobe.phonegap.push

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

class BackgroundActionButtonHandler : BroadcastReceiver(), PushConstants {
  companion object {
    private const val LOG_TAG: String = "Push_BGActionButton"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val extras = intent.extras
    Log.d(LOG_TAG, "BackgroundActionButtonHandler = $extras")

    val notId = intent.getIntExtra(PushConstants.NOT_ID, 0)
    Log.d(LOG_TAG, "not id = $notId")

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(FCMService.getAppName(context), notId)

    if (extras != null) {
      val originalExtras = extras.getBundle(PushConstants.PUSH_BUNDLE)
      originalExtras!!.putBoolean(PushConstants.FOREGROUND, false)
      originalExtras.putBoolean(PushConstants.COLDSTART, false)
      originalExtras.putString(
        PushConstants.ACTION_CALLBACK,
        extras.getString(PushConstants.CALLBACK)
      )

      val remoteInput = RemoteInput.getResultsFromIntent(intent)

      if (remoteInput != null) {
        val inputString = remoteInput.getCharSequence(PushConstants.INLINE_REPLY).toString()
        Log.d(LOG_TAG, "response: $inputString")
        originalExtras.putString(PushConstants.INLINE_REPLY, inputString)
      }

      PushPlugin.sendExtras(originalExtras)
    }
  }
}
