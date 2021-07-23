package com.adobe.phonegap.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.Spanned
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.adobe.phonegap.push.BackgroundActionButtonHandler
import com.adobe.phonegap.push.PushDismissedHandler
import com.adobe.phonegap.push.PushHandlerActivity
import com.adobe.phonegap.push.PushPlugin.Companion.isActive
import com.adobe.phonegap.push.PushPlugin.Companion.isInForeground
import com.adobe.phonegap.push.PushPlugin.Companion.sendExtras
import com.adobe.phonegap.push.PushPlugin.Companion.setApplicationIconBadgeNumber
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.*

@Suppress("HardCodedStringLiteral")
@SuppressLint("NewApi", "LogConditional")
class FCMService : FirebaseMessagingService() {
  val context: Context
    get() = applicationContext

  fun setNotification(notId: Int, message: String?) {
    var messageList = messageMap[notId]

    if (messageList == null) {
      messageList = ArrayList()
      messageMap[notId] = messageList
    }

    if (message == null || message.isEmpty()) {
      messageList.clear()
    } else {
      messageList.add(message)
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val from = message.from
    Log.d(LOG_TAG, "onMessageReceived (from=$from)")

    var extras = Bundle()

    message.notification?.let {
      extras.putString(PushConstants.TITLE, it.title)
      extras.putString(PushConstants.MESSAGE, it.body)
      extras.putString(PushConstants.SOUND, it.sound)
      extras.putString(PushConstants.ICON, it.icon)
      extras.putString(PushConstants.COLOR, it.color)
    }

    for ((key, value) in message.data) {
      extras.putString(key, value)
    }

    if (isAvailableSender(from)) {
      val prefs = context.getSharedPreferences(
        PushConstants.COM_ADOBE_PHONEGAP_PUSH,
        MODE_PRIVATE
      )

      val messageKey = prefs.getString(PushConstants.MESSAGE_KEY, PushConstants.MESSAGE)
      val titleKey = prefs.getString(PushConstants.TITLE_KEY, PushConstants.TITLE)

      extras = normalizeExtras(extras, messageKey, titleKey)

      // Clear Badge
      val clearBadge = prefs.getBoolean(PushConstants.CLEAR_BADGE, false)
      if (clearBadge) {
        setApplicationIconBadgeNumber(context, 0)
      }

      // Foreground
      extras.putBoolean(PushConstants.FOREGROUND, isInForeground)

      // if we are in the foreground and forceShow is `false` only send data
      val forceShow = prefs.getBoolean(PushConstants.FORCE_SHOW, false)
      if (!forceShow && isInForeground) {
        Log.d(LOG_TAG, "Do Not Force & Is In Foreground")
        extras.putBoolean(PushConstants.COLDSTART, false)
        sendExtras(extras)
      } else if (forceShow && isInForeground) {
        Log.d(LOG_TAG, "Force & Is In Foreground")
        extras.putBoolean(PushConstants.COLDSTART, false)
        showNotificationIfPossible(extras)
      } else {
        Log.d(LOG_TAG, "In Background")
        extras.putBoolean(PushConstants.COLDSTART, isActive)
        showNotificationIfPossible(extras)
      }
    }
  }

  private fun replaceKey(
    oldKey: String,
    newKey: String,
    extras: Bundle,
    newExtras: Bundle
  ) {
    /*
     * Change a values key in the extras bundle
     */
    var value = extras[oldKey]
    if (value != null) {
      when (value) {
        is String -> {
          value = localizeKey(newKey, value)
          newExtras.putString(newKey, value as String?)
        }

        is Boolean -> {
          newExtras.putBoolean(newKey, (value as Boolean?)!!)
        }

        is Number -> {
          newExtras.putDouble(newKey, value.toDouble())
        }

        else -> {
          newExtras.putString(newKey, value.toString())
        }
      }
    }
  }

  private fun localizeKey(key: String, value: String): String {
    /*
     * Normalize localization for key
     */
    return when (key) {
      PushConstants.TITLE,
      PushConstants.MESSAGE,
      PushConstants.SUMMARY_TEXT -> {
        try {
          val localeObject = JSONObject(value)
          val localeKey = localeObject.getString(PushConstants.LOC_KEY)
          val localeFormatData = ArrayList<String>()

          if (!localeObject.isNull(PushConstants.LOC_DATA)) {
            val localeData = localeObject.getString(PushConstants.LOC_DATA)
            val localeDataArray = JSONArray(localeData)

            for (i in 0 until localeDataArray.length()) {
              localeFormatData.add(localeDataArray.getString(i))
            }
          }

          val resourceId = context.resources.getIdentifier(
            localeKey,
            "string",
            context.packageName
          )

          if (resourceId != 0) {
            context.resources.getString(resourceId, *localeFormatData.toTypedArray())
          } else {
            Log.d(LOG_TAG, "Can't Find Locale Resource (key=$localeKey)")
            value
          }
        } catch (e: JSONException) {
          Log.d(LOG_TAG, "No Locale Found (key= $key, error=${e.message})")
          value
        }
      }
      else -> value
    }
  }

  private fun normalizeKey(
    key: String,
    messageKey: String?,
    titleKey: String?,
    newExtras: Bundle
  ): String {
    /*
     * Replace alternate keys with our canonical value
     */
    return when {
      key == PushConstants.BODY
        || key == PushConstants.ALERT
        || key == PushConstants.MP_MESSAGE
        || key == PushConstants.GCM_NOTIFICATION_BODY
        || key == PushConstants.TWILIO_BODY
        || key == messageKey
        || key == PushConstants.AWS_PINPOINT_BODY
      -> {
        PushConstants.MESSAGE
      }

      key == PushConstants.TWILIO_TITLE || key == PushConstants.SUBJECT || key == titleKey -> {
        PushConstants.TITLE
      }

      key == PushConstants.MSGCNT || key == PushConstants.BADGE -> {
        PushConstants.COUNT
      }

      key == PushConstants.SOUNDNAME || key == PushConstants.TWILIO_SOUND -> {
        PushConstants.SOUND
      }

      key == PushConstants.AWS_PINPOINT_PICTURE -> {
        newExtras.putString(PushConstants.STYLE, PushConstants.STYLE_PICTURE)
        PushConstants.PICTURE
      }

      key.startsWith(PushConstants.GCM_NOTIFICATION) -> {
        key.substring(PushConstants.GCM_NOTIFICATION.length + 1, key.length)
      }

      key.startsWith(PushConstants.GCM_N) -> {
        key.substring(PushConstants.GCM_N.length + 1, key.length)
      }

      key.startsWith(PushConstants.UA_PREFIX) -> {
        key.substring(PushConstants.UA_PREFIX.length + 1, key.length).lowercase()
      }

      key.startsWith(PushConstants.AWS_PINPOINT_PREFIX) -> {
        key.substring(PushConstants.AWS_PINPOINT_PREFIX.length + 1, key.length)
      }

      else -> key
    }
  }

  private fun normalizeExtras(
    extras: Bundle,
    messageKey: String?,
    titleKey: String?
  ): Bundle {
    /*
     * Parse bundle into normalized keys.
     */
    Log.d(LOG_TAG, "normalize extras")

    val it: Iterator<String> = extras.keySet().iterator()
    val newExtras = Bundle()

    while (it.hasNext()) {
      val key = it.next()
      Log.d(LOG_TAG, "key = $key")

      // If normalizeKey, the key is "data" or "message" and the value is a json object extract
      // This is to support parse.com and other services. Issue #147 and pull #218
      if (key == PushConstants.PARSE_COM_DATA || key == PushConstants.MESSAGE || key == messageKey) {
        val json = extras[key]

        // Make sure data is json object stringified
        if (json is String && json.startsWith("{")) {
          Log.d(LOG_TAG, "extracting nested message data from key = $key")

          try {
            // If object contains message keys promote each value to the root of the bundle
            val data = JSONObject(json)
            if (
              data.has(PushConstants.ALERT)
              || data.has(PushConstants.MESSAGE)
              || data.has(PushConstants.BODY)
              || data.has(PushConstants.TITLE)
              || data.has(messageKey)
              || data.has(titleKey)
            ) {
              val jsonIter = data.keys()

              while (jsonIter.hasNext()) {
                var jsonKey = jsonIter.next()
                Log.d(LOG_TAG, "key = data/$jsonKey")

                var value = data.getString(jsonKey)
                jsonKey = normalizeKey(jsonKey, messageKey, titleKey, newExtras)
                value = localizeKey(jsonKey, value)
                newExtras.putString(jsonKey, value)
              }
            } else if (data.has(PushConstants.LOC_KEY) || data.has(PushConstants.LOC_DATA)) {
              val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
              Log.d(LOG_TAG, "replace key $key with $newKey")
              replaceKey(key, newKey, extras, newExtras)
            }
          } catch (e: JSONException) {
            Log.e(LOG_TAG, "normalizeExtras: JSON exception")
          }
        } else {
          val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
          Log.d(LOG_TAG, "replace key $key with $newKey")
          replaceKey(key, newKey, extras, newExtras)
        }
      } else if (key == "notification") {
        val value = extras.getBundle(key)
        val iterator: Iterator<String> = value!!.keySet().iterator()

        while (iterator.hasNext()) {
          val notifkey = iterator.next()
          Log.d(LOG_TAG, "notifkey = $notifkey")
          val newKey = normalizeKey(notifkey, messageKey, titleKey, newExtras)
          Log.d(LOG_TAG, "replace key $notifkey with $newKey")
          var valueData = value.getString(notifkey)
          valueData = localizeKey(newKey, valueData!!)
          newExtras.putString(newKey, valueData)
        }
        continue
        // In case we weren't working on the payload data node or the notification node,
        // normalize the key.
        // This allows to have "message" as the payload data key without colliding
        // with the other "message" key (holding the body of the payload)
        // See issue #1663
      } else {
        val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
        Log.d(LOG_TAG, "replace key $key with $newKey")
        replaceKey(key, newKey, extras, newExtras)
      }
    } // while
    return newExtras
  }

  private fun extractBadgeCount(extras: Bundle?): Int {
    var count = -1

    try {
      extras?.getString(PushConstants.COUNT)?.let {
        count = it.toInt()
      }
    } catch (e: NumberFormatException) {
      Log.e(LOG_TAG, e.localizedMessage, e)
    }

    return count
  }

  private fun showNotificationIfPossible(extras: Bundle?) {
    // Send a notification if there is a message or title, otherwise just send data
    extras?.let {
      val message = it.getString(PushConstants.MESSAGE)
      val title = it.getString(PushConstants.TITLE)
      val contentAvailable = it.getString(PushConstants.CONTENT_AVAILABLE)
      val forceStart = it.getString(PushConstants.FORCE_START)
      val badgeCount = extractBadgeCount(extras)

      if (badgeCount >= 0) {
        setApplicationIconBadgeNumber(context, badgeCount)
      }

      if (badgeCount == 0) {
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancelAll()
      }

      Log.d(LOG_TAG, "message=$message")
      Log.d(LOG_TAG, "title=$title")
      Log.d(LOG_TAG, "contentAvailable=$contentAvailable")
      Log.d(LOG_TAG, "forceStart=$forceStart")
      Log.d(LOG_TAG, "badgeCount=$badgeCount")

      val hasMessage = message != null && message.isNotEmpty()
      val hasTitle = title != null && title.isNotEmpty()

      if (hasMessage || hasTitle) {
        Log.d(LOG_TAG, "Create Notification")

        if (!hasTitle) {
          extras.putString(PushConstants.TITLE, getAppName(this))
        }

        createNotification(extras)
      }

      if (!isActive && forceStart == "1") {
        Log.d(LOG_TAG, "The app is not running, attempting to start in the background")

        val intent = Intent(this, PushHandlerActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putExtra(PushConstants.PUSH_BUNDLE, extras)
          putExtra(PushConstants.START_IN_BACKGROUND, true)
          putExtra(PushConstants.FOREGROUND, false)
        }

        startActivity(intent)
      } else if (contentAvailable == "1") {
        Log.d(
          LOG_TAG,
          "The app is not running and content available is true, sending notification event"
        )

        sendExtras(extras)
      }
    }
  }

  private fun createNotification(extras: Bundle?) {
    val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val appName = getAppName(this)
    val notId = parseInt(PushConstants.NOT_ID, extras)
    val notificationIntent = Intent(this, PushHandlerActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.NOT_ID, notId)
    }
    val random = SecureRandom()
    var requestCode = random.nextInt()
    val contentIntent = PendingIntent.getActivity(
      this,
      requestCode,
      notificationIntent,
      PendingIntent.FLAG_UPDATE_CURRENT
    )
    val dismissedNotificationIntent = Intent(
      this,
      PushDismissedHandler::class.java
    ).apply {
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.NOT_ID, notId)
      putExtra(PushConstants.DISMISSED, true)

      action = PushConstants.PUSH_DISMISSED
    }

    requestCode = random.nextInt()

    val deleteIntent = PendingIntent.getBroadcast(
      this,
      requestCode,
      dismissedNotificationIntent,
      PendingIntent.FLAG_CANCEL_CURRENT
    )

    var mBuilder: NotificationCompat.Builder?

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      var channelID = extras!!.getString(PushConstants.ANDROID_CHANNEL_ID)

      // if the push payload specifies a channel use it
      if (channelID != null) {
        mBuilder = NotificationCompat.Builder(context, channelID)
      } else {
        val channels = mNotificationManager.notificationChannels
        channelID = if (channels.size == 1) {
          channels[0].id
        } else {
          PushConstants.DEFAULT_CHANNEL_ID
        }
        Log.d(LOG_TAG, "Using channel ID = $channelID")
        mBuilder = NotificationCompat.Builder(context, channelID!!)
      }
    } else {
      mBuilder = NotificationCompat.Builder(context)
    }
    mBuilder.setWhen(System.currentTimeMillis())
      .setContentTitle(fromHtml(extras!!.getString(PushConstants.TITLE)))
      .setTicker(fromHtml(extras.getString(PushConstants.TITLE)))
      .setContentIntent(contentIntent)
      .setDeleteIntent(deleteIntent)
      .setAutoCancel(true)

    val prefs = context.getSharedPreferences(
      PushConstants.COM_ADOBE_PHONEGAP_PUSH,
      MODE_PRIVATE
    )
    val localIcon = prefs.getString(PushConstants.ICON, null)
    val localIconColor = prefs.getString(PushConstants.ICON_COLOR, null)
    val soundOption = prefs.getBoolean(PushConstants.SOUND, true)
    val vibrateOption = prefs.getBoolean(PushConstants.VIBRATE, true)

    Log.d(LOG_TAG, "stored icon=$localIcon")
    Log.d(LOG_TAG, "stored iconColor=$localIconColor")
    Log.d(LOG_TAG, "stored sound=$soundOption")
    Log.d(LOG_TAG, "stored vibrate=$vibrateOption")

    /*
     * Notification Vibration
     */
    setNotificationVibration(extras, vibrateOption, mBuilder)

    /*
     * Notification Icon Color
     *
     * Sets the small-icon background color of the notification.
     * To use, add the `iconColor` key to plugin android options
     *
     */
    setNotificationIconColor(extras.getString(PushConstants.COLOR), mBuilder, localIconColor)

    /*
     * Notification Icon
     *
     * Sets the small-icon of the notification.
     *
     * - checks the plugin options for `icon` key
     * - if none, uses the application icon
     *
     * The icon value must be a string that maps to a drawable resource.
     * If no resource is found, falls
     *
     */
    setNotificationSmallIcon(extras, mBuilder, localIcon)

    /*
     * Notification Large-Icon
     *
     * Sets the large-icon of the notification
     *
     * - checks the gcm data for the `image` key
     * - checks to see if remote image, loads it.
     * - checks to see if assets image, Loads It.
     * - checks to see if resource image, LOADS IT!
     * - if none, we don't set the large icon
     *
     */
    setNotificationLargeIcon(extras, mBuilder)

    /*
     * Notification Sound
     */
    if (soundOption) {
      setNotificationSound(extras, mBuilder)
    }

    /*
     *  LED Notification
     */
    setNotificationLedColor(extras, mBuilder)

    /*
     *  Priority Notification
     */
    setNotificationPriority(extras, mBuilder)

    /*
     * Notification message
     */
    setNotificationMessage(notId, extras, mBuilder)

    /*
     * Notification count
     */
    setNotificationCount(extras, mBuilder)

    /*
     *  Notification ongoing
     */
    setNotificationOngoing(extras, mBuilder)

    /*
     * Notification count
     */
    setVisibility(extras, mBuilder)

    /*
     * Notification add actions
     */
    createActions(extras, mBuilder, notId)
    mNotificationManager.notify(appName, notId, mBuilder.build())
  }

  private fun updateIntent(
    intent: Intent,
    callback: String,
    extras: Bundle?,
    foreground: Boolean,
    notId: Int
  ) {
    intent.apply {
      putExtra(PushConstants.CALLBACK, callback)
      putExtra(PushConstants.PUSH_BUNDLE, extras)
      putExtra(PushConstants.FOREGROUND, foreground)
      putExtra(PushConstants.NOT_ID, notId)
    }
  }

  private fun createActions(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
    notId: Int
  ) {
    Log.d(LOG_TAG, "create actions: with in-line")
    val actions = extras!!.getString(PushConstants.ACTIONS)
    if (actions != null) {
      try {
        val actionsArray = JSONArray(actions)
        val wActions = ArrayList<NotificationCompat.Action>()
        for (i in 0 until actionsArray.length()) {
          val min = 1
          val max = 2000000000
          val random = SecureRandom()
          val uniquePendingIntentRequestCode = random.nextInt(max - min + 1) + min
          Log.d(LOG_TAG, "adding action")
          val action = actionsArray.getJSONObject(i)
          Log.d(LOG_TAG, "adding callback = " + action.getString(PushConstants.CALLBACK))
          val foreground = action.optBoolean(PushConstants.FOREGROUND, true)
          val inline = action.optBoolean("inline", false)
          var intent: Intent?
          var pIntent: PendingIntent?
          if (inline) {
            Log.d(
              LOG_TAG,
              "Version: " + Build.VERSION.SDK_INT + " = " + Build.VERSION_CODES.M
            )
            intent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity")
              Intent(this, PushHandlerActivity::class.java)
            } else {
              Log.d(LOG_TAG, "push receiver")
              Intent(this, BackgroundActionButtonHandler::class.java)
            }
            updateIntent(
              intent,
              action.getString(PushConstants.CALLBACK),
              extras,
              foreground,
              notId
            )
            pIntent = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity for notId $notId")
              PendingIntent.getActivity(
                this,
                uniquePendingIntentRequestCode,
                intent,
                PendingIntent.FLAG_ONE_SHOT
              )
            } else {
              Log.d(LOG_TAG, "push receiver for notId $notId")
              PendingIntent.getBroadcast(
                this,
                uniquePendingIntentRequestCode,
                intent,
                PendingIntent.FLAG_ONE_SHOT
              )
            }
          } else if (foreground) {
            intent = Intent(this, PushHandlerActivity::class.java)
            updateIntent(
              intent,
              action.getString(PushConstants.CALLBACK),
              extras,
              foreground,
              notId
            )
            pIntent = PendingIntent.getActivity(
              this, uniquePendingIntentRequestCode,
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT
            )
          } else {
            intent = Intent(this, BackgroundActionButtonHandler::class.java)
            updateIntent(
              intent,
              action.getString(PushConstants.CALLBACK),
              extras,
              foreground,
              notId
            )
            pIntent = PendingIntent.getBroadcast(
              this, uniquePendingIntentRequestCode,
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT
            )
          }
          val actionBuilder = NotificationCompat.Action.Builder(
            getImageId(action.optString(PushConstants.ICON, "")),
            action.getString(PushConstants.TITLE),
            pIntent
          )
          var remoteInput: RemoteInput? = null
          if (inline) {
            Log.d(LOG_TAG, "create remote input")
            val replyLabel =
              action.optString(PushConstants.INLINE_REPLY_LABEL, "Enter your reply here")
            remoteInput =
              RemoteInput.Builder(PushConstants.INLINE_REPLY).setLabel(replyLabel).build()
            actionBuilder.addRemoteInput(remoteInput)
          }
          var wAction: NotificationCompat.Action? = actionBuilder.build()
          wActions.add(actionBuilder.build())
          if (inline) {
            mBuilder.addAction(wAction)
          } else {
            mBuilder.addAction(
              getImageId(action.optString(PushConstants.ICON, "")),
              action.getString(PushConstants.TITLE),
              pIntent
            )
          }
          wAction = null
          pIntent = null
        }
        mBuilder.extend(NotificationCompat.WearableExtender().addActions(wActions))
        wActions.clear()
      } catch (e: JSONException) {
        // nope
      }
    }
  }

  private fun setNotificationCount(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val count = extractBadgeCount(extras)
    if (count >= 0) {
      Log.d(LOG_TAG, "count =[$count]")
      mBuilder.setNumber(count)
    }
  }

  private fun setVisibility(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val visibilityStr = extras!!.getString(PushConstants.VISIBILITY)
    if (visibilityStr != null) {
      try {
        val visibility = visibilityStr.toInt()
        if (visibility >= NotificationCompat.VISIBILITY_SECRET
          && visibility <= NotificationCompat.VISIBILITY_PUBLIC
        ) {
          mBuilder.setVisibility(visibility)
        } else {
          Log.e(LOG_TAG, "Visibility parameter must be between -1 and 1")
        }
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }
    }
  }

  private fun setNotificationVibration(
    extras: Bundle?,
    vibrateOption: Boolean,
    mBuilder: NotificationCompat.Builder
  ) {
    val vibrationPattern = extras!!.getString(PushConstants.VIBRATION_PATTERN)
    if (vibrationPattern != null) {
      val items = vibrationPattern
        .replace("\\[".toRegex(), "")
        .replace("\\]".toRegex(), "")
        .split(",").toTypedArray()
      val results = LongArray(items.size)
      for (i in items.indices) {
        try {
          results[i] = items[i].trim { it <= ' ' }.toLong()
        } catch (nfe: NumberFormatException) {
        }
      }
      mBuilder.setVibrate(results)
    } else {
      if (vibrateOption) {
        mBuilder.setDefaults(Notification.DEFAULT_VIBRATE)
      }
    }
  }

  private fun setNotificationOngoing(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val ongoing = java.lang.Boolean.parseBoolean(extras!!.getString(PushConstants.ONGOING, "false"))
    mBuilder.setOngoing(ongoing)
  }

  private fun setNotificationMessage(
    notId: Int,
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder
  ) {
    val message = extras!!.getString(PushConstants.MESSAGE)
    val style = extras.getString(PushConstants.STYLE, PushConstants.STYLE_TEXT)
    if (PushConstants.STYLE_INBOX == style) {
      setNotification(notId, message)
      mBuilder.setContentText(fromHtml(message))
      val messageList = messageMap[notId]!!
      val sizeList = messageList.size
      if (sizeList > 1) {
        val sizeListMessage = sizeList.toString()
        var stacking: String? = "$sizeList more"
        if (extras.getString(PushConstants.SUMMARY_TEXT) != null) {
          stacking = extras.getString(PushConstants.SUMMARY_TEXT)
          stacking = stacking!!.replace("%n%", sizeListMessage)
        }
        val notificationInbox = NotificationCompat.InboxStyle()
          .setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
          .setSummaryText(fromHtml(stacking))
        for (i in messageList.indices.reversed()) {
          notificationInbox.addLine(fromHtml(messageList[i]))
        }
        mBuilder.setStyle(notificationInbox)
      } else {
        val bigText = NotificationCompat.BigTextStyle()
        if (message != null) {
          bigText.bigText(fromHtml(message))
          bigText.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
          mBuilder.setStyle(bigText)
        }
      }
    } else if (PushConstants.STYLE_PICTURE == style) {
      setNotification(notId, "")
      val bigPicture = NotificationCompat.BigPictureStyle()
      bigPicture.bigPicture(getBitmapFromURL(extras.getString(PushConstants.PICTURE)))
      bigPicture.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
      bigPicture.setSummaryText(fromHtml(extras.getString(PushConstants.SUMMARY_TEXT)))
      mBuilder.setContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
      mBuilder.setContentText(fromHtml(message))
      mBuilder.setStyle(bigPicture)
    } else {
      setNotification(notId, "")
      val bigText = NotificationCompat.BigTextStyle()
      if (message != null) {
        mBuilder.setContentText(fromHtml(message))
        bigText.bigText(fromHtml(message))
        bigText.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
        val summaryText = extras.getString(PushConstants.SUMMARY_TEXT)
        if (summaryText != null) {
          bigText.setSummaryText(fromHtml(summaryText))
        }
        mBuilder.setStyle(bigText)
      }
      /*
      else {
          mBuilder.setContentText("<missing message content>");
      }
      */
    }
  }

  private fun setNotificationSound(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    var soundname = extras!!.getString(PushConstants.SOUNDNAME)
    if (soundname == null) {
      soundname = extras.getString(PushConstants.SOUND)
    }
    if (PushConstants.SOUND_RINGTONE == soundname) {
      mBuilder.setSound(Settings.System.DEFAULT_RINGTONE_URI)
    } else if (soundname != null && !soundname.contentEquals(PushConstants.SOUND_DEFAULT)) {
      val sound = Uri
        .parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/raw/" + soundname)
      Log.d(LOG_TAG, sound.toString())
      mBuilder.setSound(sound)
    } else {
      mBuilder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
    }
  }

  private fun setNotificationLedColor(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val ledColor = extras!!.getString(PushConstants.LED_COLOR)
    if (ledColor != null) {
      // Converts parse Int Array from ledColor
      val items = ledColor
        .replace("\\[".toRegex(), "")
        .replace("\\]".toRegex(), "")
        .split(",").toTypedArray()
      val results = IntArray(items.size)
      for (i in items.indices) {
        try {
          results[i] = items[i].trim { it <= ' ' }.toInt()
        } catch (nfe: NumberFormatException) {
        }
      }
      if (results.size == 4) {
        mBuilder.setLights(
          Color.argb(results[0], results[1], results[2], results[3]),
          500,
          500
        )
      } else {
        Log.e(LOG_TAG, "ledColor parameter must be an array of length == 4 (ARGB)")
      }
    }
  }

  private fun setNotificationPriority(extras: Bundle?, mBuilder: NotificationCompat.Builder) {
    val priorityStr = extras!!.getString(PushConstants.PRIORITY)
    if (priorityStr != null) {
      try {
        val priority = priorityStr.toInt()
        if (priority >= NotificationCompat.PRIORITY_MIN
          && priority <= NotificationCompat.PRIORITY_MAX
        ) {
          mBuilder.priority = priority
        } else {
          Log.e(LOG_TAG, "Priority parameter must be between -2 and 2")
        }
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }
    }
  }

  private fun getCircleBitmap(bitmap: Bitmap?): Bitmap? {
    if (bitmap == null) {
      return null
    }
    val output = Bitmap.createBitmap(
      bitmap.width,
      bitmap.height,
      Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(output)
    val color = Color.RED
    val paint = Paint()
    val rect = Rect(0, 0, bitmap.width, bitmap.height)
    val rectF = RectF(rect)
    paint.isAntiAlias = true
    canvas.drawARGB(0, 0, 0, 0)
    paint.color = color
    val cx = (bitmap.width / 2).toFloat()
    val cy = (bitmap.height / 2).toFloat()
    val radius = if (cx < cy) cx else cy
    canvas.drawCircle(cx, cy, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(bitmap, rect, rect, paint)
    bitmap.recycle()
    return output
  }

  private fun setNotificationLargeIcon(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder
  ) {
    val gcmLargeIcon = extras!!.getString(PushConstants.IMAGE) // from gcm
    val imageType = extras.getString(PushConstants.IMAGE_TYPE, PushConstants.IMAGE_TYPE_SQUARE)
    if (gcmLargeIcon != null && "" != gcmLargeIcon) {
      if (gcmLargeIcon.startsWith("http://") || gcmLargeIcon.startsWith("https://")) {
        val bitmap = getBitmapFromURL(gcmLargeIcon)
        if (PushConstants.IMAGE_TYPE_SQUARE.equals(imageType, ignoreCase = true)) {
          mBuilder.setLargeIcon(bitmap)
        } else {
          val bm = getCircleBitmap(bitmap)
          mBuilder.setLargeIcon(bm)
        }
        Log.d(LOG_TAG, "using remote large-icon from gcm")
      } else {
        val assetManager = assets
        val istr: InputStream
        try {
          istr = assetManager.open(gcmLargeIcon)
          val bitmap = BitmapFactory.decodeStream(istr)
          if (PushConstants.IMAGE_TYPE_SQUARE.equals(imageType, ignoreCase = true)) {
            mBuilder.setLargeIcon(bitmap)
          } else {
            val bm = getCircleBitmap(bitmap)
            mBuilder.setLargeIcon(bm)
          }
          Log.d(LOG_TAG, "using assets large-icon from gcm")
        } catch (e: IOException) {
          var largeIconId = 0
          largeIconId = getImageId(gcmLargeIcon)
          if (largeIconId != 0) {
            val largeIconBitmap = BitmapFactory.decodeResource(context.resources, largeIconId)
            mBuilder.setLargeIcon(largeIconBitmap)
            Log.d(LOG_TAG, "using resources large-icon from gcm")
          } else {
            Log.d(LOG_TAG, "Not setting large icon")
          }
        }
      }
    }
  }

  private fun getImageId(icon: String): Int {
    var iconId = context.resources.getIdentifier(icon, PushConstants.DRAWABLE, context.packageName)
    if (iconId == 0) {
      iconId = context.resources.getIdentifier(icon, "mipmap", context.packageName)
    }
    return iconId
  }

  private fun setNotificationSmallIcon(
    extras: Bundle?,
    mBuilder: NotificationCompat.Builder,
    localIcon: String?
  ) {
    var iconId = 0
    val icon = extras!!.getString(PushConstants.ICON)
    if (icon != null && "" != icon) {
      iconId = getImageId(icon)
      Log.d(LOG_TAG, "using icon from plugin options")
    } else if (localIcon != null && "" != localIcon) {
      iconId = getImageId(localIcon)
      Log.d(LOG_TAG, "using icon from plugin options")
    }
    if (iconId == 0) {
      Log.d(LOG_TAG, "no icon resource found - using application icon")
      iconId = context.applicationInfo.icon
    }
    mBuilder.setSmallIcon(iconId)
  }

  private fun setNotificationIconColor(
    color: String?,
    mBuilder: NotificationCompat.Builder,
    localIconColor: String?
  ) {
    var iconColor = 0
    if (color != null && "" != color) {
      try {
        iconColor = Color.parseColor(color)
      } catch (e: IllegalArgumentException) {
        Log.e(LOG_TAG, "couldn't parse color from android options")
      }
    } else if (localIconColor != null && "" != localIconColor) {
      try {
        iconColor = Color.parseColor(localIconColor)
      } catch (e: IllegalArgumentException) {
        Log.e(LOG_TAG, "couldn't parse color from android options")
      }
    }
    if (iconColor != 0) {
      mBuilder.color = iconColor
    }
  }

  /**
   * @return Bitmap from URL
   */
  fun getBitmapFromURL(strURL: String?): Bitmap? {
    return try {
      val url = URL(strURL)
      val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        doInput = true
        connect()
      }
      val input = connection.inputStream
      BitmapFactory.decodeStream(input)
    } catch (e: IOException) {
      e.printStackTrace()
      null
    }
  }

  private fun parseInt(value: String, extras: Bundle?): Int {
    var retval = 0
    try {
      retval = extras!!.getString(value)!!.toInt()
    } catch (e: NumberFormatException) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.message)
    } catch (e: Exception) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.message)
    }
    return retval
  }

  private fun fromHtml(source: String?): Spanned? {
    return if (source != null) Html.fromHtml(source) else null
  }

  private fun isAvailableSender(from: String?): Boolean {
    val sharedPref = context.getSharedPreferences(
      PushConstants.COM_ADOBE_PHONEGAP_PUSH,
      MODE_PRIVATE
    )
    val savedSenderID = sharedPref.getString(PushConstants.SENDER_ID, "")
    Log.d(LOG_TAG, "sender id = $savedSenderID")
    return from == savedSenderID || from!!.startsWith("/topics/")
  }

  companion object {
    private const val LOG_TAG = "Push_FCMService"

    private val messageMap = HashMap<Int, ArrayList<String?>>()
    fun getAppName(context: Context): String {
      return context.packageManager.getApplicationLabel(context.applicationInfo) as String
    }
  }
}
