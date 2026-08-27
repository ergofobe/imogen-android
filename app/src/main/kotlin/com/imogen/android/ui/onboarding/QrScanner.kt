package com.imogen.android.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * The camera, looking for one square.
 *
 * ZXing rather than ML Kit: ML Kit's barcode scanner wants Google Play services, and a
 * self-hosted photo library is exactly the sort of thing people run on a phone that does
 * not have them. Reading a QR code is a solved problem that does not need a download.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (!allowed) onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(modifier.fillMaxSize())
        return
    }

    // One analyser thread, held for as long as the scanner is on screen. Decoding on the
    // camera's own callback thread would stall the preview on every frame.
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Latched, because the decoder will happily read the same code thirty times a second
    // and every one of them would start pairing again.
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)

            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(executor) { image ->
                            val text = decode(image)
                            image.close()
                            if (text != null && !handled) {
                                handled = true
                                previewView.post { onScanned(text) }
                            }
                        }
                    }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(viewContext))

            previewView
        },
    )
}

private val reader = QRCodeReader()
private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
    // Spend the extra effort. A code on a screen a foot away, at an angle, in a room with
    // a window behind it, is the normal case rather than the hard one.
    put(DecodeHintType.TRY_HARDER, true)
}

/**
 * Reads the luminance plane straight out of the camera buffer.
 *
 * YUV_420_888's first plane is exactly the grey-scale image a QR decoder wants, so there
 * is no conversion to do — the bytes are already the right bytes.
 */
private fun decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val source = PlanarYUVLuminanceSource(
        bytes,
        plane.rowStride,
        image.height,
        0,
        0,
        image.width.coerceAtMost(plane.rowStride),
        image.height,
        false,
    )

    return runCatching {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }.getOrNull().also { reader.reset() }
}
