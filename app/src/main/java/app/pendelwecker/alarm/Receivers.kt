package app.pendelwecker.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Wird vom Alarm geweckt und prüft genau die Fahrt, für die er gestellt war. Die Prüfung läuft
 * direkt hier (goAsync, max. ~10 s), weil ein Alarm im Ruhemodus der App kurz Netz und Rechenzeit
 * gibt. Das teure Routing für eine Ersatzverbindung, Netzfehler und Zeitüberschreitungen übernimmt
 * WorkManager mit Netzwerk-Bedingung und Wiederholung.
 */
class CheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profileId = intent.getIntExtra(CheckScheduler.EXTRA_PROFILE, -1)
        val at = intent.getLongExtra(CheckScheduler.EXTRA_AT, -1)
        val day = intent.getLongExtra(CheckScheduler.EXTRA_DATE, Long.MIN_VALUE)
        val app = context.applicationContext
        if (profileId < 0 || at < 0 || day == Long.MIN_VALUE) {
            // Alter Alarm ohne Angaben (vor einem Update gestellt): nur neu planen.
            CheckScheduler.scheduleNext(app)
            return
        }
        val job = CheckJob(profileId, at, LocalDate.ofEpochDay(day))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = withTimeoutOrNull(8_500) { CommuteRunner.runScheduled(app, job, allowRouting = false) }
                when {
                    outcome == null || outcome.retry -> CheckWorker.enqueue(app, job)
                    outcome.needsRouting -> CheckWorker.enqueue(app, job)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CheckWorker.enqueue(app, job)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Eine geplante Prüfung: welche Fahrt, welcher Prüfzeitpunkt, welcher Fahrttag. */
data class CheckJob(val profileId: Int, val atEpochMs: Long, val serviceDate: LocalDate)

/** Plant nach Neustart, App-Update, geänderter Alarm-Berechtigung, Uhrzeit oder Zeitzone neu. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        CheckScheduler.scheduleNext(context.applicationContext)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

/**
 * Ausweichweg mit Netzwerk-Bedingung: wiederholt eine geplante Prüfung nach Netzfehler oder
 * Zeitüberschreitung und sucht die Ersatzverbindung. Ohne Fahrt-Angaben ("Jetzt prüfen" im Widget)
 * werden alle aktiven Fahrten aktualisiert, ohne Benachrichtigung.
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getInt(KEY_PROFILE, -1)
        return try {
            if (profileId < 0) {
                CommuteRunner.refreshAll(applicationContext)
                Result.success()
            } else {
                val job = CheckJob(profileId, inputData.getLong(KEY_AT, 0), LocalDate.ofEpochDay(inputData.getLong(KEY_DATE, 0)))
                val outcome = CommuteRunner.runScheduled(applicationContext, job, allowRouting = true)
                if (outcome.retry && runAttemptCount < 3) Result.retry() else Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_PROFILE = "profile"
        private const val KEY_AT = "at"
        private const val KEY_DATE = "date"

        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Geplante Prüfung einer Fahrt nachholen bzw. mit Routing vervollständigen. */
        fun enqueue(context: Context, job: CheckJob) {
            val request = OneTimeWorkRequestBuilder<CheckWorker>()
                .setConstraints(NETWORK)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_PROFILE to job.profileId, KEY_AT to job.atEpochMs, KEY_DATE to job.serviceDate.toEpochDay()))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("pendel-check-${job.profileId}", ExistingWorkPolicy.REPLACE, request)
        }

        /** Alle aktiven Fahrten aktualisieren (Widget-Knopf), ohne Benachrichtigung. */
        fun enqueueRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(NETWORK).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pendel-refresh", ExistingWorkPolicy.KEEP, request)
        }
    }
}
