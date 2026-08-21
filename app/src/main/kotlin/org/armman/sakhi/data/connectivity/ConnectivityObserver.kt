package org.armman.sakhi.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive connectivity signal: emits the current state immediately, then `true`/`false` on every
 * change. Kept separate from [ConnectivityChecker] (a one-shot `isOnline()`) so its many existing
 * callers/fakes are untouched — this interface exists specifically to drive event-driven work like
 * auto-retrying offline submissions the moment the network returns (see
 * [org.armman.sakhi.data.forms.ReconnectLookupWarmer]).
 */
interface ConnectivityObserver {
  /** `true` when there is an internet-validated network path. Distinct-until-changed. */
  fun observe(): Flow<Boolean>
}

@Singleton
class AndroidConnectivityObserver @Inject constructor(
  @ApplicationContext private val context: Context,
) : ConnectivityObserver {

  override fun observe(): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (manager == null) {
      trySend(false)
      close()
      return@callbackFlow
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        trySend(hasInternetCapableNetwork(manager))
      }

      override fun onLost(network: Network) {
        trySend(hasInternetCapableNetwork(manager))
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
      }
    }

    // Emit the current state up front so a collector started while already online/offline reacts
    // without waiting for the next change.
    trySend(hasInternetCapableNetwork(manager))

    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
    manager.registerNetworkCallback(request, callback)

    awaitClose { manager.unregisterNetworkCallback(callback) }
  }.distinctUntilChanged()

  // Deliberately NOT requiring NET_CAPABILITY_VALIDATED -- see AndroidConnectivityChecker.isOnline()
  // for the full rationale (VALIDATED's background probe can lag behind a genuinely usable
  // connection on weak/rural mobile data, producing a false "offline" read with no visible
  // feedback to the Sakhi). Kept in lockstep with that check so this reactive signal and the
  // one-shot isOnline() never disagree about the same network state.
  private fun hasInternetCapableNetwork(manager: ConnectivityManager): Boolean {
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}
