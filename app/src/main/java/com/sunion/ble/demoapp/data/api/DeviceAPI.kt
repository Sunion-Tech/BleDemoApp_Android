package com.sunion.ble.demoapp.data.api

import com.sunion.ble.demoapp.BuildConfig
import com.sunion.core.ble.entity.ProductionGetRequest
import com.sunion.core.ble.entity.ProductionGetResponse
import retrofit2.http.*

interface DeviceAPI {
    @POST("production/get")
    suspend fun getProduction(@Header("x-api-key") apiKey: String = BuildConfig.API_KEY, @Body request: ProductionGetRequest): ProductionGetResponse
}