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
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }
}
