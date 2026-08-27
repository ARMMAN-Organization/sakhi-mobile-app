package org.armman.sakhi.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the device currently has a network path to the internet. Abstracted so
 * [org.armman.sakhi.data.auth.RemoteAuthRepository] can be unit-tested with a fake. */
interface ConnectivityChecker {
  fun isOnline(): Boolean
}

@Singleton
class AndroidConnectivityChecker @Inject constructor(
  @ApplicationContext private val context: Context,
) : ConnectivityChecker {
  override fun isOnline(): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    // Deliberately NOT requiring NET_CAPABILITY_VALIDATED here (unlike the pre-CR-fix version).
    // VALIDATED is Android's own background probe confirming the link truly reaches the internet
    // -- on weak/rural mobile data it can lag several seconds behind the network actually being
    // usable, or flap false during that window. Every caller of isOnline() only uses it to decide
    // whether to ATTEMPT an immediate online submit before falling back to the offline queue
    // (see e.g. RoomDynamicFormDraftRepository.submitDraft) -- a false negative here doesn't
    // corrupt anything, it just silently skips a real chance to sync immediately and defers to
    // the next manual Data Upload instead, with no feedback to the Sakhi that it happened. Basing
    // the decision on NET_CAPABILITY_INTERNET alone (the OS's own claim that this network *should*
    // reach the internet) removes that lag; a genuinely bad connection still fails fast as an
    // IOException on the real network call, which every caller already treats as retryable/queued.
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}
