package org.armman.sakhi.data.connectivity

/** Test double with a mutable flag — shared by [org.armman.sakhi.data.auth.RemoteAuthRepositoryTest]
 * and [org.armman.sakhi.ui.login.LoginViewModelTest]. */
class FakeConnectivityChecker(var online: Boolean = true) : ConnectivityChecker {
  override fun isOnline(): Boolean = online
}
