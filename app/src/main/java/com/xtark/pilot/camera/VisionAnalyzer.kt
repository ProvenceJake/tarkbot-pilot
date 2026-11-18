package com.xtark.pilot.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class VisionAnalyzer(
    private val barcodeScanner: BarcodeScanner,
    private val personDetector: PersonBodyDetector,
    private val trackingModeProvider: () -> TrackingMode,
    private val targetColorProvider: () -> TargetColor,
    private val selectedPersonIdProvider: () -> Int?,
    private val callback: Callback
) : ImageAnalysis.Analyzer {
    interface Callback {
        fun onTarget(result: TargetResult)
        fun onPeople(result: PeopleResult)
        fun onQrCode(value: String)
        fun onAnalyzerError(message: String)
    }

    private val colorDetector = ColorTargetDetector()
    private var lastQrValue: String? = null
    private var lastQrScanMs = 0L
    private var lastPersonScanMs = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            if (trackingModeProvider() == TrackingMode.COLOR) {
                callback.onTarget(colorDetector.detect(imageProxy, targetColorProvider()))
                val qrTask = maybeScanQr(image)
                qrTask?.addOnCompleteListener { imageProxy.close() } ?: imageProxy.close()
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastPersonScanMs >= PERSON_SCAN_INTERVAL_MS) {
                lastPersonScanMs = now
                callback.onPeople(
                    personDetector.detect(
                        imageProxy,
                        selectedPersonIdProvider(),
                        now
                    )
                )
            }
            val qrTask = maybeScanQr(image)
            qrTask?.addOnCompleteListener { imageProxy.close() } ?: imageProxy.close()
        } catch (error: Throwable) {
            Log.e(TAG, "Vision analysis failed in ${trackingModeProvider()} mode", error)
            imageProxy.close()
            callback.onAnalyzerError(
                "视觉分析失败：${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun maybeScanQr(image: InputImage): Task<List<Barcode>>? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastQrScanMs < QR_SCAN_INTERVAL_MS) return null
        lastQrScanMs = now
        return barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (!value.isNullOrBlank() && value != lastQrValue) {
                    lastQrValue = value
                    callback.onQrCode(value)
                }
            }
            .addOnFailureListener { error ->
                callback.onAnalyzerError(error.message ?: "二维码识别失败")
            }
    }

    companion object {
        private const val QR_SCAN_INTERVAL_MS = 850L
        private const val PERSON_SCAN_INTERVAL_MS = 160L
        private const val TAG = "TarkbotVision"
    }
}
