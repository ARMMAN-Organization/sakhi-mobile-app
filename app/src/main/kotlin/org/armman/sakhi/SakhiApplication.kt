package org.armman.sakhi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler
import org.armman.sakhi.data.forms.DynamicFormSyncScheduler
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
  }
}
