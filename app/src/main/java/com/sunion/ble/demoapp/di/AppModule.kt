package com.sunion.ble.demoapp.di

import com.sunion.core.ble.AppSchedulers
import com.sunion.core.ble.Scheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@InstallIn(SingletonComponent::class)
@Module(includes = [AppModule.Bind::class])
object AppModule {
    @InstallIn(SingletonComponent::class)
    @Module
    abstract class Bind {
        @Binds
        abstract fun bindScheduler(appSchedulers: AppSchedulers): Scheduler
    }
}