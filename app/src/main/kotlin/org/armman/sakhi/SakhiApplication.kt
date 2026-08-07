package org.armman.sakhi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.armman.sakhi.data.forms.ReconnectLookupWarmer
import org.armman.sakhi.data.motherlink.ReconnectMotherDetailsWarmer
import org.armman.sakhi.data.sync.LegacyPeriodicSyncCleanup
import javax.inject.Inject

/**
 * Application entry point; enables Hilt dependency injection app-wide.
 *
 * Also supplies WorkManager's [Configuration] with a Hilt-aware [HiltWorkerFactory] so
 * `@HiltWorker`-annotated workers (e.g. the enrollment sync worker) can have their dependencies
 * injected. WorkManager's default auto-initialization is disabled in the manifest in favor of
 * this on-demand [Configuration.Provider] path — that's what the standard Hilt+WorkManager setup
 * requires.
 *
 * **No sync is scheduled here.** Earlier builds started a 15-minute periodic sync for the
 * enrollment and dynamic-form queues from [onCreate]; SRS §3A.1 specifies data sync as a manual
 * trigger only, so that scheduling is gone and the Sakhi's Data Upload tap
 * ([org.armman.sakhi.data.sync.ManualSyncTrigger]) is the only thing that starts an upload.
 */
@HiltAndroidApp
class SakhiApplication : Application(), Configuration.Provider {

  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var reconnectLookupWarmer: ReconnectLookupWarmer
  @Inject lateinit var reconnectMotherDetailsWarmer: ReconnectMotherDetailsWarmer
  @Inject lateinit var legacyPeriodicSyncCleanup: LegacyPeriodicSyncCleanup

  /** Process-lifetime scope for app-wide background collectors (connectivity → lookup warming).
   * Never cancelled — it lives as long as the process, which is exactly the intended lifetime. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      .build()

  override fun onCreate() {
    super.onCreate()
    // Transitional: WorkManager's periodic work survives app upgrade, so devices already in the
    // field would keep auto-syncing on the old 15-minute tick unless it's explicitly cancelled.
    legacyPeriodicSyncCleanup.cancelLegacyPeriodicWork()
    // Reference-data warming only — moves no beneficiary data, so it stays event-driven while
    // actual uploads remain manual.
    reconnectLookupWarmer.start(applicationScope)
    reconnectMotherDetailsWarmer.start(applicationScope)
  }
}
