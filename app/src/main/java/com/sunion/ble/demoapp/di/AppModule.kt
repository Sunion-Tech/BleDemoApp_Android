package com.sunion.ble.demoapp.di

import android.app.Application
import android.content.Context
import android.os.PowerManager
import com.google.gson.GsonBuilder
import com.sunion.ble.demoapp.BuildConfig
import com.sunion.ble.demoapp.data.api.DeviceAPI
import com.sunion.ble.demoapp.data.api.DeviceApiRepository
import com.sunion.ble.demoapp.data.api.ErrorInterceptor
import com.sunion.ble.demoapp.data.api.NetworkErrorRetryInterceptor
import com.sunion.ble.demoapp.data.api.ToastHttpException
import com.sunion.core.ble.AppSchedulers
import com.sunion.core.ble.Scheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module(includes = [AppModule.Bind::class])
object AppModule {

    @Provides
    @Singleton
    fun providePowerManager(@ApplicationContext context: Context):
            PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Provides
    @Singleton
    fun provideContext(application: Application): Context {
        return application.applicationContext
    }
    @Provides
    @Singleton
    fun provideToastHttpException(context: Context) = ToastHttpException(context)
    @Provides
    @Singleton
    fun provideOkHttpClient(toastHttpException: ToastHttpException): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(NetworkErrorRetryInterceptor())
            .addInterceptor(ErrorInterceptor(toastHttpException))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideDeviceApi(client: OkHttpClient): DeviceAPI = Retrofit.Builder()
        .baseUrl(BuildConfig.API_GATEWAY_ENDPOINT)
        .client(client)
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .addConverterFactory(
            GsonConverterFactory.create(
                GsonBuilder()
                    .setLenient()
                    .disableHtmlEscaping()
                    .create()))
        .build()
        .create(DeviceAPI::class.java)

    @Provides
    @Singleton
    fun provideDeviceApiRepository(deviceAPI: DeviceAPI) =
        DeviceApiRepository(deviceAPI)


    @InstallIn(SingletonComponent::class)
    @Module
    abstract class Bind {
        @Binds
        abstract fun bindScheduler(appSchedulers: AppSchedulers): Scheduler
    }
}