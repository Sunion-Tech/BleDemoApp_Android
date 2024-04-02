package com.sunion.ble.demoapp.data.api

import com.sunion.core.ble.entity.ProductionGetRequest
import com.sunion.core.ble.entity.ProductionGetResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceApiRepository @Inject constructor(
    private val deviceAPI: DeviceAPI
) {

    suspend fun getProduction(
        code: String,
    ): ProductionGetResponse {
        val request = ProductionGetRequest(
            code = code,
        )
        return deviceAPI.getProduction(request = request)
    }

}