package com.adobe.phonegap.push

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput

class PushHandlerActivity : Activity(), PushConstants {
  companion object {
    private const val LOG_TAG = "Push_HandlerActivity"
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

    var foreground = getIntent().extras!!.getBoolean("foreground", true)
    val startOnBackground = getIntent().extras!!
      .getBoolean(PushConstants.START_IN_BACKGROUND, false)

    val dismissed = getIntent().extras!!
      .getBoolean(PushConstants.DISMISSED, false)
    Log.d(LOG_TAG, "dismissed = $dismissed")

    if (!startOnBackground) {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.cancel(FCMService.getAppName(this), notId)
    }

    val isPushPluginActive = PushPlugin.isActive()
    val inline = processPushBundle(isPushPluginActive, intent)

    if (inline && Build.VERSION.SDK_INT < Build.VERSION_CODES.N && !startOnBackground) {
      foreground = true
    }

    Log.d(LOG_TAG, "bringToForeground = $foreground")
    finish()

    if (!dismissed) {
      Log.d(LOG_TAG, "isPushPluginActive = $isPushPluginActive")

      if (!isPushPluginActive && foreground && inline) {
        Log.d(LOG_TAG, "forceMainActivityReload")
        forceMainActivityReload(false)
      } else if (startOnBackground) {
        Log.d(LOG_TAG, "startOnBackgroundTrue")
        forceMainActivityReload(true)
      } else {
        Log.d(LOG_TAG, "don't want main activity")
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
      val originalExtras = extras.getBundle(PushConstants.PUSH_BUNDLE)
      originalExtras!!.putBoolean(PushConstants.FOREGROUND, false)
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

    val notificationManager = this.getSystemService(
      NOTIFICATION_SERVICE
    ) as NotificationManager
    notificationManager.cancelAll()
  }
}
