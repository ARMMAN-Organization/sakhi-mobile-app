package org.armman.sakhi.data.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Test double for [ConnectivityObserver] backed by a [MutableStateFlow] so tests can drive
 * connectivity transitions deterministically. */
class FakeConnectivityObserver(initial: Boolean = false) : ConnectivityObserver {
  private val state = MutableStateFlow(initial)

  fun set(online: Boolean) {
    state.value = online
  }

  override fun observe(): Flow<Boolean> = state
}
