package io.ciyuantown.scan

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors

object QrImageDecoder {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var liveScanner: BarcodeScanner? = null
    @Volatile private var liveGeneration = 0
    @Volatile private var processing = false

    @JvmStatic fun startCamera() {
        stopCamera()
        liveScanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }

    @JvmStatic @Synchronized fun stopCamera() {
        liveGeneration++
        liveScanner?.close()
        liveScanner = null
        processing = false
    }

    @JvmStatic @Synchronized fun frame(proxy: ImageProxy, complete: (String) -> Unit) {
        val scanner = liveScanner
        val image = proxy.image
        if (scanner == null || processing || image == null) { proxy.close(); return }
        processing = true
        val token = liveGeneration
        try {
            scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    if (token == liveGeneration) codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let(complete)
                }
                .addOnCompleteListener { proxy.close(); if (token == liveGeneration) processing = false }
        } catch (_: Exception) { proxy.close(); if (token == liveGeneration) processing = false }
    }

    @JvmStatic
    fun decode(context: Context, path: String, complete: (String, String) -> Unit) {
        // Loading a large album photo must not block camera or UI frames.
        worker.execute {
            try {
                val uri = if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path) else Uri.fromFile(File(path))
                val image = InputImage.fromFilePath(context, uri)
                val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                scanner.process(image)
                    .addOnSuccessListener { codes -> complete(codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue ?: "", "") }
                    .addOnFailureListener { complete("", "图片识别失败，请重新选择") }
                    .addOnCompleteListener { scanner.close() }
            } catch (_: Exception) {
                main.post { complete("", "图片无法读取，请重新选择") }
            }
        }
    }
}
