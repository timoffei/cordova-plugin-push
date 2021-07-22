package com.adobe.phonegap.push

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput

class BackgroundHandlerActivity : Activity(), PushConstants {
  companion object {
    private const val LOG_TAG: String = "Push_BackgroundHandlerActivity"
  }

  /*
   * this activity will be started if the user touches a notification that we own.
   * We send it's data off to the push plugin for processing.
   * If needed, we boot up the main activity to kickstart the application.
   * @see android.app.Activity#onCreate(android.os.Bundle)
   */
  public override fun onCreate(savedInstanceState: Bundle?) {
    val gcm = FCMService()
    val intent = intent
    val notId = intent.extras!!.getInt(PushConstants.NOT_ID, 0)
    Log.d(LOG_TAG, "not id = $notId")

    gcm.setNotification(notId, "")

    super.onCreate(savedInstanceState)
    Log.v(LOG_TAG, "onCreate")

    val callback = getIntent().extras!!.getString("callback")
    Log.d(LOG_TAG, "callback = $callback")

    val startOnBackground = getIntent().extras!!
      .getBoolean(PushConstants.START_IN_BACKGROUND, false)

    val dismissed = getIntent().extras!!
      .getBoolean(PushConstants.DISMISSED, false)
    Log.d(LOG_TAG, "dismissed = $dismissed")

    if (!startOnBackground) {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.cancel(FCMService.getAppName(this), notId)
    }

    val isPushPluginActive = PushPlugin.isActive

    processPushBundle(isPushPluginActive, intent)
    finish()

    if (!dismissed) {
      // Tap the notification, app should start.
      if (!isPushPluginActive) {
        forceMainActivityReload(false)
      } else {
        forceMainActivityReload(true)
      }
    }
  }

  /**
   * Takes the pushBundle extras from the intent,
   * and sends it through to the PushPlugin for processing.
   */
  private fun processPushBundle(isPushPluginActive: Boolean, intent: Intent): Boolean {
    val extras = getIntent().extras
    var remoteInput: Bundle? = null

    if (extras != null) {
      var originalExtras = extras.getBundle(PushConstants.PUSH_BUNDLE)

      if (originalExtras == null) {
        originalExtras = extras
        originalExtras.remove(PushConstants.FROM)
        originalExtras.remove(PushConstants.MESSAGE_ID)
        originalExtras.remove(PushConstants.COLLAPSE_KEY)
      }

      originalExtras.putBoolean(PushConstants.FOREGROUND, false)
      originalExtras.putBoolean(PushConstants.COLDSTART, !isPushPluginActive)
      originalExtras.putBoolean(PushConstants.DISMISSED, extras.getBoolean(PushConstants.DISMISSED))
      originalExtras.putString(
        PushConstants.ACTION_CALLBACK,
        extras.getString(PushConstants.CALLBACK)
      )
      originalExtras.remove(PushConstants.NO_CACHE)

      remoteInput = RemoteInput.getResultsFromIntent(intent)

      if (remoteInput != null) {
        val inputString = remoteInput.getCharSequence(PushConstants.INLINE_REPLY).toString()
        Log.d(LOG_TAG, "response: $inputString")
        originalExtras.putString(PushConstants.INLINE_REPLY, inputString)
      }

      PushPlugin.sendExtras(originalExtras)
    }
    return remoteInput == null
  }

  /**
   * Forces the main activity to re-launch if it's unloaded.
   */
  private fun forceMainActivityReload(startOnBackground: Boolean) {
    val pm = packageManager
    val launchIntent = pm.getLaunchIntentForPackage(applicationContext.packageName)
    val extras = intent.extras

    if (extras != null) {
      val originalExtras = extras.getBundle(PushConstants.PUSH_BUNDLE)

      if (originalExtras != null) {
        launchIntent!!.putExtras(originalExtras)
      }

      launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      launchIntent.addFlags(Intent.FLAG_FROM_BACKGROUND)
      launchIntent.putExtra(PushConstants.START_IN_BACKGROUND, startOnBackground)
    }

    startActivity(launchIntent)
  }

  override fun onResume() {
    super.onResume()

    val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancelAll()
  }
}
