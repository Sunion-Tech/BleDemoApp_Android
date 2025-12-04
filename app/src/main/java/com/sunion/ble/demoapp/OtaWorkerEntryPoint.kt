package com.sunion.ble.demoapp

import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.usecase.IncomingSunionBleNotificationUseCase
import com.sunion.core.ble.usecase.LockOTAUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OtaWorkerEntryPoint {
    fun statefulConnection(): ReactiveStatefulConnection
    fun incomingSunionBleNotificationUseCase(): IncomingSunionBleNotificationUseCase
    fun lockOTAUseCase(): LockOTAUseCase
}