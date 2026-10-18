package com.example.callrecorderhelper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Periodically removes items whose 30-day recovery window has expired. */
class TrashPurgeJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = getSharedPreferences("recording_library", Context.MODE_PRIVATE)
        val files = File(getExternalFilesDir(null), "Recordings").listFiles().orEmpty()
        val editor = prefs.edit()
        var changed = false
        files.filter { it.isFile }.forEach { file ->
            val key = "trash:${file.name}"
            if (prefs.contains(key) && System.currentTimeMillis() - prefs.getLong(key, System.currentTimeMillis()) >= TimeUnit.DAYS.toMillis(30)) {
                file.delete()
                editor.remove(key).remove("favorite:${file.name}")
                changed = true
            }
        }
        if (changed) editor.apply()
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 30971
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TrashPurgeJobService::class.java))
                .setPersisted(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .build()
            scheduler.schedule(job)
        }
    }
}
