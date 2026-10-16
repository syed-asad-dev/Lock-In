package com.app.lockin

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

private const val WORK_TAG = "AdmissionEvaluationWork"

/**
 * Persistent platform worker for external admission review. Network I/O is
 * never performed in PackageInstallReceiver; JobService runs it on a worker
 * thread and has bounded HTTP timeouts in AdmissionBackendClient.
 */
class AdmissionEvaluationJobService : JobService() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AdmissionEvaluation").apply { isDaemon = true }
    }
    private var activeJob: Future<*>? = null
    private val stopped = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        stopped.set(false)
        activeJob = executor.submit {
            var retry = false
            try {
                // A single coalesced job drains all pending records. This
                // avoids one JobScheduler entry per package and prevents a
                // package hash collision from dropping a review.
                val pending = AdmissionGateManager.getPendingRecords(this@AdmissionEvaluationJobService)
                for (record in pending) {
                    if (stopped.get()) {
                        retry = true
                        break
                    }
                    val audit = AdmissionGateManager.preparePendingAudit(
                        this@AdmissionEvaluationJobService,
                        record.packageName
                    ) ?: continue
                    val result = AdmissionBackendClient.evaluate(
                        this@AdmissionEvaluationJobService,
                        audit
                    )
                    val applied = AdmissionGateManager.applyExternalResult(
                        this@AdmissionEvaluationJobService,
                        record.packageName,
                        result,
                        audit
                    )
                    if ((result.decision == ClassificationDecision.UNKNOWN && result.retryable) ||
                        (!applied && AdmissionGateManager.getRecord(
                            this@AdmissionEvaluationJobService,
                            record.packageName
                        )?.status == AdmissionStatus.PENDING)
                    ) {
                        retry = true
                    }
                }
            } catch (e: Exception) {
                Log.e(WORK_TAG, "Admission evaluation worker failed", e)
                retry = true
            } finally {
                if (!stopped.get()) {
                    jobFinished(params, retry)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        activeJob?.cancel(true)
        activeJob = null
        // Preserve pending state and ask JobScheduler to retry after backoff.
        return true
    }

    override fun onDestroy() {
        stopped.set(true)
        activeJob?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}

object AdmissionWorkScheduler {
    private const val JOB_ID = 0x4C494E
    private const val EXTRA_TRIGGER_PACKAGE = "triggerPackage"

    fun enqueue(context: Context, packageName: String? = null) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return
        val extras = PersistableBundle().apply {
            if (!packageName.isNullOrBlank()) putString(EXTRA_TRIGGER_PACKAGE, packageName)
        }
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, AdmissionEvaluationJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(extras)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = scheduler.schedule(job)
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w(WORK_TAG, "Unable to enqueue admission evaluation job: result=$result")
        }
    }

    /** Kept as an explicit API for callers; pending state is the source of truth. */
    fun cancel(context: Context, packageName: String?) {
        // Do not cancel the coalesced job for one package: another pending
        // package may share it. The worker re-reads authoritative state.
        Log.d(WORK_TAG, "Ignoring per-package cancellation for ${packageName ?: "all"}; state remains authoritative")
    }
}
