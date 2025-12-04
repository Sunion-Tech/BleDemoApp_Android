package com.sunion.ble.demoapp

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.os.Build
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.sunion.core.ble.entity.EventState
import com.sunion.core.ble.entity.LockConnectionInfo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.sunion.core.ble.ReactiveStatefulConnection
import com.sunion.core.ble.entity.Access
import com.sunion.core.ble.entity.Alert
import com.sunion.core.ble.entity.DeviceStatus
import com.sunion.core.ble.toHexString
import com.sunion.core.ble.usecase.IncomingSunionBleNotificationUseCase
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import androidx.core.net.toUri

@HiltWorker
class OtaWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    // 用 EntryPointAccessors 從 Application 取得 Hilt DI 成員
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            OtaWorkerEntryPoint::class.java
        )
    }

    private val statefulConnection by lazy { entryPoint.statefulConnection() }
    private val incomingSunionBleNotificationUseCase by lazy { entryPoint.incomingSunionBleNotificationUseCase() }
    private val lockOTAUseCase by lazy { entryPoint.lockOTAUseCase() }

    private var currentFileUri: Uri? = null
    private var fileSize: Int = 0
    private val currentTarget = 0
    private val otaTimeout = 60_000L

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result = coroutineScope {
        currentFileUri = workerParams.inputData.getString("fileUri")?.toUri()
        val iv = workerParams.inputData.getString("iv") ?: return@coroutineScope Result.failure()
        val signature = workerParams.inputData.getString("signature") ?: return@coroutineScope Result.failure()
        val expectedHash = workerParams.inputData.getString("hash256") ?: return@coroutineScope Result.failure()

        val connectionInfo = try {
            parseLockInfo()
        } catch (e: Exception) {
            Timber.e(e, "無法解析 LockConnectionInfo")
            return@coroutineScope Result.failure()
        }

        OtaNotificationManager.createNotificationChannel(context)
        updateProgress(0)

        try {
            val lockInfo = connectToDevice(connectionInfo)
            if (lockInfo == null) {
                Timber.e("Connect to lockInfo fail or worker stopped")
                Result.failure()
            }

            val notificationJob = launch {
                incomingSunionBleNotificationUseCase()
                    .onEach { notification ->
                        Timber.d("Incoming notification: $notification")
                    }
                    .catch { Timber.e(it, "IncomingSunionBleNotification error") }
                    .collect()
            }

            otaUpdate(currentTarget, signature, iv, expectedHash)

            notificationJob.cancel()
            disconnectFromDevice()
            showCompletedNotification()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "OTA fail with exception: $e")
            disconnectFromDevice()
            showFailNotification()
            Result.failure()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun updateProgress(progress: Int) {
        setForeground(createForegroundInfo(progress))
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val notification = OtaNotificationManager.buildNotification(context, progress, 100)
        return ForegroundInfo(1001, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showCompletedNotification() {
        val notification = OtaNotificationManager.buildCompletedNotification(context)
        val manager = NotificationManagerCompat.from(context)
        manager.notify(1002, notification)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showFailNotification() {
        val notification = OtaNotificationManager.buildFailNotification(context)
        val manager = NotificationManagerCompat.from(context)
        manager.notify(1003, notification)
    }

    private fun parseLockInfo(): LockConnectionInfo {
        val json = inputData.getString("lockInfo")
            ?: throw IllegalArgumentException("LockConnectionInfo missing in inputData")
        return Gson().fromJson(json, LockConnectionInfo::class.java)
    }

    private suspend fun connectToDevice(info: LockConnectionInfo): LockConnectionInfo? = coroutineScope {
        var finalInfo: LockConnectionInfo? = null

        val connectionJob = launch {
            statefulConnection.connState
                .onEach { event ->
                    when (event.status) {
                        EventState.READY -> Timber.d("🔌 Connection READY")
                        EventState.SUCCESS -> {
                            if (event.data?.first == true) {
                                Timber.d("Connection SUCCESS")
                                finalInfo = info.copy(
                                    permission = statefulConnection.lockConnectionInfo.permission,
                                    keyTwo = statefulConnection.lockConnectionInfo.keyTwo,
                                    permanentToken = statefulConnection.lockConnectionInfo.permanentToken
                                )
                            }
                        }
                        EventState.ERROR -> {
                            val msg = event.message ?: "Unknown Error"
                            Timber.e("Connection ERROR: $msg")
                            this@coroutineScope.cancel("Connection failed")
                        }
                        else -> Unit
                    }
                }
                .catch { Timber.e("connState 錯誤: $it") }
                .collect()
        }

        launch {
            Timber.d("開始連線到 ${info.macAddress} ...")
            statefulConnection.establishConnection(
                macAddress = info.macAddress,
                keyOne = info.keyOne,
                oneTimeToken = info.oneTimeToken,
                permanentToken = info.permanentToken,
                model = info.model,
                isSilentlyFail = false
            )
        }

        withTimeoutOrNull(otaTimeout) {
            while (finalInfo == null && isActive) delay(200)
        } ?: run {
            connectionJob.cancel()
            return@coroutineScope null
        }

        connectionJob.cancel()
        return@coroutineScope finalInfo
    }

    private fun disconnectFromDevice() {
        statefulConnection.disconnect()
        Timber.d("裝置斷線")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun otaUpdate(target: Int, signature: String, iv: String, checkHash: String) {
        if (currentFileUri == null || !fileCheck(checkHash)) {
            Timber.w("未設定檔案或 Hash 不符")
            return
        }

        val inputStream: InputStream = applicationContext.contentResolver
            .openInputStream(currentFileUri!!) ?: run {
            Timber.d("開啟 InputStream 失敗")
            return
        }

        val chunkSize = 128
        val buffer = ByteArray(chunkSize)

        try {
            inputStream.use {
                fileSize = it.available()
                lockOTAUseCase.setOTAStart(target, fileSize)

                var totalSent = 0
                var blockNumber = 0
                var lastReportedProgress = -1

                while (true) {
                    val read = it.read(buffer)
                    if (read == -1) break

                    val success = withStepTimeout {
                        lockOTAUseCase.transferOTAData(
                            blockNumber * chunkSize,
                            buffer.copyOf(read)
                        )
                    }

                    if (success == null) {
                        Timber.e("transferOTAData timeout at block=$blockNumber")
                        lockOTAUseCase.setOTACancel(target)
                        return
                    }

                    totalSent += read
                    blockNumber++

                    val progress = (totalSent * 100 / fileSize).coerceAtMost(100)
                    if (progress - lastReportedProgress >= 1) {
                        lastReportedProgress = progress
                        updateProgress(progress)
                    }
                }

                val result = withStepTimeout {
                    lockOTAUseCase.setOTAFinish(target, fileSize, iv, signature)
                }

                if (result == null) {
                    Timber.e("setOTAFinish timeout")
                    lockOTAUseCase.setOTACancel(target)
                } else {
                    Timber.d("OTA finish: $result")
                }

            }
        } catch (e: Exception) {
            Timber.e("OTA error:$e")
            lockOTAUseCase.setOTACancel(target)
        }

    }

    private fun fileCheck(expectedHash: String): Boolean {
        val functionName = ::fileCheck.name
        if (currentFileUri == null) return false
        val inputStream = applicationContext.contentResolver
            .openInputStream(currentFileUri!!) ?: return false

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        inputStream.close()

        val hashResult = digest.digest().toHexString()
        Timber.d("$functionName Hash Check: $hashResult")
        return hashResult == expectedHash
    }

    private suspend fun <T> withStepTimeout(
        timeout: Long = otaTimeout,
        block: suspend () -> T
    ): T? {
        return try {
            withTimeout(timeout) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.e("Step timeout after $timeout ms: ${e.message}")
            null
        }
    }

}