package com.sunion.ble.demoapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HiltApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        if(BuildConfig.DEBUG){
            Timber.plant(Timber.DebugTree())
        }
    }

    // WorkManager 2.9 起全面 Kotlin 化，getWorkManagerConfiguration() 已改為唯讀屬性
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}