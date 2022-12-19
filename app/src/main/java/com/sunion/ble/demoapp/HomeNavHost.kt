package com.sunion.ble.demoapp

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sunion.ble.demoapp.ui.screen.HomeScreen
import com.sunion.ble.demoapp.ui.screen.ScanQRCodeScreen

@Composable
fun HomeNavHost(viewModel: HomeViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute.Home.route) {
        composable(HomeRoute.Home.route) {
            viewModel.lockConnectionInfo ?: viewModel.init()
            HomeScreen(viewModel = viewModel, navController = navController)
        }
        composable(HomeRoute.Scan.route) {
            ScanQRCodeScreen(viewModel = viewModel, navController = navController)
        }
    }
}

sealed class HomeRoute(val route: String) {
    object Home : HomeRoute("Home")
    object Scan : HomeRoute("Scan")
}