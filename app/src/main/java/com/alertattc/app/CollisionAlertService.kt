package com.alertattc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alertattc.app.audio.AlertSoundPlayer
import com.alertattc.app.detector.VehicleDetector
import com.alertattc.app.ttc.PanelCalibrator
import com.alertattc.app.ttc.TtcEngine
import com.alertattc.app.util.FpsMeter
import com.alertattc.app.util.ThermalMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

data class UiState(
    val running: Boolean = false,
    val calibrating: Boolean = true,
    val ttcS: Double? = null,
    val alerta: Boolean = false,
    val fps: Double = 0.0,
    val latencyMs: Long = 0,
    val usingGpu: Boolean = false,
    val corteFrac: Float = 0f,
    val status: String = "iniciando"
)

/**
 * Servico em primeiro plano (tipo camera) que sustenta camera + inferencia
 * + TtcEngine rodando mesmo com a Activity em segundo plano. A UI so
 * observa o StateFlow abaixo — nenhuma logica de detecao/TTC mora na
 * Activity.
 *
 * Sem Preview: a unica saida deste app e som, entao nao ha motivo pra
 * gastar bateria desenhando a camera na tela durante a operacao ativa.
 */
class CollisionAlertService : LifecycleService() {

    companion object {
        private const val TAG = "CollisionAlertService"
        private const val CHANNEL_ID = "alerta_ttc_channel"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.alertattc.app.action.STOP"

        /** Janela de calibracao do corte do painel, em ms de relogio (nao frames — fps ainda e desconhecido no arranque). */
        private const val CALIBRATION_MS = 8000L

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CollisionAlertService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CollisionAlertService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var detector: VehicleDetector
    private lateinit var soundPlayer: AlertSoundPlayer
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var analysisExecutor: ExecutorService

    private val ttcEngine = TtcEngine()
    private val fpsMeter = FpsMeter()
    private var calibratorRef: PanelCalibrator? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var sessionStartNanos = 0L
    private var lastFpsForWindow = 0.0

    // Escritas na thread do analysisExecutor, leituras na main thread
    // (watchdog / thermal callback) — @Volatile evita valor obsoleto.
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var unavailableStallWarned = false
    private var unavailableThermalWarned = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        soundPlayer = AlertSoundPlayer(lifecycleScope)
        thermalMonitor = ThermalMonitor(this) { throttling, status -> handleThermal(throttling, status) }
        thermalMonitor.start()
        analysisExecutor = Executors.newSingleThreadExecutor()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val d = VehicleDetector(applicationContext)
                d.init()
                detector = d
                withContext(Dispatchers.Main) { startCamera() }
            } catch (e: Exception) {
                Log.e(TAG, "falha ao carregar o modelo", e)
                _state.value = _state.value.copy(status = "erro ao carregar modelo: ${e.message}")
                soundPlayer.playUnavailable()
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                watchdogTick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            cameraProvider = provider
            sessionStartNanos = SystemClock.elapsedRealtimeNanos()
            _state.value = _state.value.copy(running = true, calibrating = true, status = "calibrando corte do painel")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ensureCalibrator(w: Int, h: Int): PanelCalibrator =
        calibratorRef ?: PanelCalibrator(w, h).also { calibratorRef = it }

    private fun onFrame(proxy: ImageProxy) {
        val frameStartNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameAtMs = SystemClock.elapsedRealtime()
        unavailableStallWarned = false
        try {
            val bitmap = imageProxyToBitmap(proxy)
            val tSec = (frameStartNanos - sessionStartNanos) / 1_000_000_000.0
            val fps = fpsMeter.tick()
            if (fps > 0 && abs(fps - lastFpsForWindow) > 0.5) {
                ttcEngine.updateWindowSizes(fps)
                lastFpsForWindow = fps
            }

            val calibrator = ensureCalibrator(bitmap.width, bitmap.height)

            if (!calibrator.done) {
                val result = detector.detect(bitmap)
                calibrator.feed(result.detections)
                val elapsedCalibMs = (frameStartNanos - sessionStartNanos) / 1_000_000
                if (elapsedCalibMs > CALIBRATION_MS) calibrator.finish()
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - frameStartNanos) / 1_000_000
                _state.value = _state.value.copy(
                    fps = fps, latencyMs = latencyMs, usingGpu = detector.usingGpu,
                    calibrating = !calibrator.done, corteFrac = calibrator.result,
                    status = if (calibrator.done) "corte calibrado em %.2f".format(calibrator.result)
                             else "calibrando corte do painel..."
                )
            } else {
                val corteH = (bitmap.height * calibrator.result).toInt().coerceIn(1, bitmap.height)
                val cropped = if (corteH == bitmap.height) bitmap
                              else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, corteH)
                val result = detector.detect(cropped)
                if (cropped !== bitmap) cropped.recycle()

                val ttc = ttcEngine.process(tSec, result.detections, bitmap.width)
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - frameStartNanos) / 1_000_000

                if (ttc.alertaDisparado) {
                    soundPlayer.playCollisionAlert()
                }

                _state.value = _state.value.copy(
                    running = true, calibrating = false, ttcS = ttc.ttcS,
                    alerta = ttc.baixoSeguidos >= TtcEngine.CONFIRMA_FRAMES,
                    fps = fps, latencyMs = latencyMs, usingGpu = detector.usingGpu,
                    corteFrac = calibrator.result, status = "monitorando"
                )
            }
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "erro processando frame", e)
            if (::soundPlayer.isInitialized) soundPlayer.playUnavailable()
            _state.value = _state.value.copy(status = "erro: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    /** Camera interrompida silenciosamente (sem excecao, so parou de chamar o analyzer). */
    private fun watchdogTick() {
        if (!_state.value.running) return
        val stalled = lastFrameAtMs > 0 && SystemClock.elapsedRealtime() - lastFrameAtMs > 2500
        if (stalled && !unavailableStallWarned) {
            unavailableStallWarned = true
            soundPlayer.playUnavailable()
            _state.value = _state.value.copy(status = "sem frames da camera — verifique")
        }
    }

    private fun handleThermal(throttling: Boolean, status: Int) {
        if (throttling && !unavailableThermalWarned) {
            unavailableThermalWarned = true
            soundPlayer.playUnavailable()
            _state.value = _state.value.copy(status = "aparelho esquentando (thermal=$status)")
        } else if (!throttling) {
            unavailableThermalWarned = false
        }
    }

    /** RGBA_8888 (CameraX 1.3+) copia direto para Bitmap.ARGB_8888: mesma ordem de bytes por pixel. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val paddedWidth = rowStride / pixelStride
        buffer.rewind()
        val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        val cropped = if (paddedWidth == width) full else Bitmap.createBitmap(full, 0, 0, width, height)
        if (cropped !== full) full.recycle()

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return cropped
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        if (rotated !== cropped) cropped.recycle()
        return rotated
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CollisionAlertService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.btn_stop), stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
        if (::soundPlayer.isInitialized) soundPlayer.release()
        if (::thermalMonitor.isInitialized) thermalMonitor.stop()
        _state.value = UiState()
    }
}
