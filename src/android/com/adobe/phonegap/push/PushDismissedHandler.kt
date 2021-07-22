package com.adobe.phonegap.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PushDismissedHandler : BroadcastReceiver(), PushConstants {
  companion object {
    private const val LOG_TAG: String = "Push_DismissedHandler"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val extras = intent.extras
    val fcm = FCMService()
    val action = intent.action
    val notID = intent.getIntExtra(PushConstants.NOT_ID, 0)
    if (action == PushConstants.PUSH_DISMISSED) {
      Log.d(LOG_TAG, "PushDismissedHandler = $extras")
      Log.d(LOG_TAG, "not id = $notID")
      fcm.setNotification(notID, "")
    }
  }
}
