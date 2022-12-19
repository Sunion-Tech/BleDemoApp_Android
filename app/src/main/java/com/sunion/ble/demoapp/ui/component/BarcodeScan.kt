package com.sunion.ble.demoapp.ui.component

import android.app.Activity
import android.view.LayoutInflater
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BarcodeScan(
    onScanResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    var lock by remember { mutableStateOf(false) }
    val current = LocalContext.current
    var captureManager by remember { mutableStateOf<CaptureManager?>(null) }

    DisposableEffect(key1 = true, effect = {
        onDispose {
            captureManager?.apply {
                onPause()
                onDestroy()
            }
        }
    })

    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context)
                .inflate(
                    com.sunion.ble.demoapp.R.layout.view_barcode_scaner,
                    null,
                    false
                ) as DecoratedBarcodeView)
                .apply {
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    captureManager = CaptureManager(current as Activity, this)
                    captureManager?.decode()
                    decodeContinuous {
                        if (lock)
                            return@decodeContinuous
                        it.text?.let { barCodeOrQr ->
                            onScanResult(barCodeOrQr)
                            captureManager?.onPause()
                            lock = true
                            coroutineScope.launch {
                                delay(2000)
                                lock = false
                            }
                        }
                    }
                    barcodeView.resume()
                }
        },
        update = {
            if (isTorchOn) it.setTorchOn()
            else it.setTorchOff()
        },
        modifier = modifier
    )
}