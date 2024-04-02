package com.sunion.ble.demoapp.data.api

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.jetbrains.annotations.NotNull
import timber.log.Timber
import java.net.SocketTimeoutException

class NetworkErrorRetryInterceptor : Interceptor {
    var tryCount = 0

    @NotNull
    @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            var response : Response? = null
            var tryCount = 0
            do {
                try {
                    response = chain.proceed(chain.request())
                    break
                } catch (e: SocketTimeoutException) {
                    response?.close()
                    tryCount++
                    Timber.e("API request SocketTimeoutException, retrying $tryCount")
                    if (tryCount >= 10) throw SocketTimeoutException()
                }
            }while (tryCount<10)
        return response!!
    }
}