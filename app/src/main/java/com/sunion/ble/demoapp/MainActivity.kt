package com.sunion.ble.demoapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sunion.ble.demoapp.ui.theme.lightColors
import com.sunion.ble.demoapp.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        permissions.entries.forEach {
            Timber.d("${it.key} = ${it.value}")
            val isGranted = it.value
            if (isGranted) {
                // 權限已獲取
                Timber.d("${it.key} = ${it.value} isGranted")
            } else {
                // 權限被拒絕
                Timber.d("${it.key} = ${it.value} isDeny")
            }
        }
    }

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            //granted
            Timber.d("requestBluetooth isGranted")
        }else{
            //deny
            Timber.d("requestBluetooth isDeny")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberNavController()
            AppTheme(colors = lightColors()) {
                    NavigationComponent(navController = navController)
            }
        }

        val permissionsToRequest = mutableListOf<String>()

        // 判斷是否需要通知權限 (Android 13+, Tiramisu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 判斷藍牙權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要新的藍牙權限
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 以下，藍牙掃描通常只需要位置權限
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)

            // 請求開啟藍牙功能 (這是 Intent，不是 Permission)
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetooth.launch(enableBtIntent)
        }

        // 一次性發出所有權限請求
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun NavigationComponent(navController: NavHostController) {
    val homeViewModel = viewModel<HomeViewModel>()
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeNavHost(
                homeViewModel,
            )
        }
    }
}