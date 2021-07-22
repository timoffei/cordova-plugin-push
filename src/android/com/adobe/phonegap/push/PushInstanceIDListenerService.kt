package com.adobe.phonegap.push

import android.util.Log
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessagingService

class PushInstanceIDListenerService : FirebaseMessagingService(), PushConstants {
  companion object {
    private const val LOG_TAG: String = "Push_InsIdService"
  }

  override fun onNewToken(s: String) {
    super.onNewToken(s)
    FirebaseInstanceId.getInstance().instanceId
      .addOnSuccessListener { instanceIdResult ->
        // Get updated InstanceID token.
        val refreshedToken = instanceIdResult.token
        Log.d(LOG_TAG, "Refreshed token: $refreshedToken")

        // TODO: Implement this method to send any registration to your app's servers.
        //sendRegistrationToServer(refreshedToken);
      }
  }
}
