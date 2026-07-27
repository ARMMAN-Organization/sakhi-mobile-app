package org.armman.sakhi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
import org.armman.sakhi.data.forms.ReconnectSyncTrigger
import javax.inject.Inject

/**
 * Application entry point; enables Hilt dependency injection app-wide.
 *
 * Also supplies WorkManager's [Configuration] with a Hilt-aware [HiltWorkerFactory] so
 * `@HiltWorker`-annotated workers (e.g. the enrollment sync worker) can have their dependencies
 * injected. WorkManager's default auto-initialization is disabled in the manifest in favor of
 * this on-demand [Configuration.Provider] path — that's what the standard Hilt+WorkManager setup
 * requires.
 */
@HiltAndroidApp
class SakhiApplication : Application(), Configuration.Provider {

  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var enrollmentSyncScheduler: EnrollmentSyncScheduler
  @Inject lateinit var dynamicFormSyncScheduler: DynamicFormSyncScheduler
  @Inject lateinit var reconnectSyncTrigger: ReconnectSyncTrigger

  /** Process-lifetime scope for app-wide background collectors (connectivity → auto-sync). Never
   * cancelled — it lives as long as the process, which is exactly the intended lifetime. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      .build()

  override fun onCreate() {
    super.onCreate()
    // Standing safety net so any drafts left over from a prior process (killed before its
    // one-shot sync ran) still get picked up periodically, not just right after saving.
    enrollmentSyncScheduler.ensurePeriodicSyncScheduled()
    dynamicFormSyncScheduler.ensurePeriodicSyncScheduled()
    // Event-driven counterpart to the periodic safety net: retry pending offline submissions the
    // instant connectivity returns, so the Sakhi never has to reopen the app to trigger an upload.
    reconnectSyncTrigger.start(applicationScope)
  }
}
