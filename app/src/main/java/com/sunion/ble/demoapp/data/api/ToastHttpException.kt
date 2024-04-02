package com.sunion.ble.demoapp.data.api

import android.content.Context
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToastHttpException @Inject constructor(content: Context){
    private val mContext = content
    var apiName = ""
    operator fun invoke(e: Throwable) = CoroutineScope(Dispatchers.IO).launch{
        val msg = when (e) {
            is SocketTimeoutException -> "API:$apiName Timeout"

            is HttpException -> {
                when(e.code()){
                    400 -> "API:$apiName Bad Request"

                    401 -> {
                        //ProcessPhoenix.triggerRebirth(mContext)
                        "API:$apiName Unauthorized"
                    }

                    else -> "API:$apiName HttpException"
                }
            }
            is UnknownHostException -> {
                "API:$apiName No Network"
            }
            else -> "API:$apiName ${e.message}"
        }
        Timber.e(msg)
        Looper.prepare()
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show()
        Looper.loop()
    }
}