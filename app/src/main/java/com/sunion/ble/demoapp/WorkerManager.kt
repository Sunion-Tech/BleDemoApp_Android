package com.sunion.ble.demoapp

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import timber.log.Timber

object WorkerManager {

    fun enqueueUnique(
        context: Context,
        name: String,
        request: OneTimeWorkRequest,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        Timber.Forest.d("Enqueuing unique worker: $name")
        WorkManager.getInstance(context)
            .enqueueUniqueWork(name, policy, request)
    }

    fun cancel(context: Context, name: String) {
        Timber.Forest.d("Cancelling worker: $name")
        WorkManager.getInstance(context).cancelUniqueWork(name)
    }

    fun getWorkStatusLive(context: Context, name: String): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(name)
    }

    fun isRunning(context: Context, name: String): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(name).get()
        return infos.any { it.state == WorkInfo.State.RUNNING }
    }

    fun isEnqueuedOrRunning(context: Context, name: String): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(name).get()
        return infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    fun cancelAll(context: Context) {
        Timber.Forest.d("Cancelling all workers")
        WorkManager.getInstance(context).cancelAllWork()
    }
}