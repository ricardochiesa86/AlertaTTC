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
import com.alertattc.app.detector.Detection
import com.alertattc.app.detector.VehicleDetector
import com.alertattc.app.ttc.CalibrationPrefs
import com.alertattc.app.ttc.PanelCalibrator
import com.alertattc.app.ttc.TtcEngine
import com.alertattc.app.ttc.TtcResult
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
 * Frame usado para o preview de diagnostico opcional (botao "Ver
 * camera" na tela principal). E o MESMO bitmap que o VehicleDetector
 * analisou, nao um preview de camera separado — isso garante que as
 * caixas desenhadas batem exatamente com o que a deteccao viu, sem
 * duplicar a sessao de camera nem lidar com sincronizacao entre duas
 * fontes de frame.
 */
data class PreviewFrame(
    val bitmap: Bitmap,
    val frameW: Int,
    val frameH: Int,
    val rotationDegrees: Int,
    val corteFrac: Float,
    val corteYPx: Int,
    val detections: List<Detection>,
    val leader: Detection?,
    val leaderWidthPx: Float?
)

/**
 * Servico em primeiro plano (tipo camera) que sustenta camera + inferencia
 * + TtcEngine rodando mesmo com a Activity em segundo plano. A UI so
 * observa o StateFlow abaixo — nenhuma logica de detecao/TTC mora na
 * Activity.
 *
 * Sem Preview do CameraX: o preview de diagnostico (quando ligado pelo
 * usuario) reaproveita o bitmap ja decodificado para deteccao em vez de
 * abrir uma segunda saida de camera — mais simples e garante alinhamento
 * pixel-a-pixel entre o que se ve e o que foi detectado.
 */
class CollisionAlertService : LifecycleService() {

    companion object {
        private const val TAG = "CollisionAlertService"
        private const val TAG_DEBUG = "AlertaTTC-Debug"
        private const val CHANNEL_ID = "alerta_ttc_channel"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.alertattc.app.action.STOP"

        /** Janela de calibracao do corte do painel, em ms de relogio (nao frames — fps ainda e desconhecido no arranque). */
        private const val CALIBRATION_MS = 8000L

        /** Log de diagnostico completo a cada N frames — o volume por frame derrubaria o fps. */
        private const val DEBUG_LOG_EVERY_N_FRAMES = 10

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state

        private val _previewEnabled = MutableStateFlow(false)
        val previewEnabled: StateFlow<Boolean> = _previewEnabled

        private val _previewFrame = MutableStateFlow<PreviewFrame?>(null)
        val previewFrame: StateFlow<PreviewFrame?> = _previewFrame

        /** Consumido no proximo frame processado (thread do analysisExecutor) — ver onFrame. */
        @Volatile private var recalibrateRequested = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CollisionAlertService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CollisionAlertService::class.java).setAction(ACTION_STOP))
        }

        /** Padrao desligado: a tela de operacao fica limpa e o custo extra de copiar bitmap por frame nao existe. */
        fun setPreviewEnabled(enabled: Boolean) {
            _previewEnabled.value = enabled
            if (!enabled) _previewFrame.value = null
        }

        /**
         * Forca uma nova rodada de calibracao do corte do painel (ex.: suporte
         * ou carro mudou depois que a calibracao concluiu "sem painel" ou com
         * um corte que nao serve mais). Efeito no proximo frame processado.
         */
        fun requestRecalibration() {
            recalibrateRequested = true
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
    private var debugFrameCounter = 0
    private var startedSoundPlayed = false

    // Escritas na thread do analysisExecutor, leituras na main thread
    // (watchdog / thermal callback) — @Volatile evita valor obsoleto.
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var unavailableStallWarned = false
    private var unavailableThermalWarned = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        soundPlayer = AlertSoundPlayer(applicationContext, lifecycleScope)
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
            if (recalibrateRequested) {
                recalibrateRequested = false
                calibratorRef = null
                ttcEngine.reset()
                startedSoundPlayed = false
                Log.d(TAG_DEBUG, "recalibracao solicitada pela interface — reiniciando o calibrador do corte")
            }

            val decoded = imageProxyToBitmap(proxy)
            val bitmap = decoded.bitmap
            val tSec = (frameStartNanos - sessionStartNanos) / 1_000_000_000.0
            val fps = fpsMeter.tick()
            if (fps > 0 && abs(fps - lastFpsForWindow) > 0.5) {
                ttcEngine.updateWindowSizes(fps)
                lastFpsForWindow = fps
            }

            val calibrator = ensureCalibrator(bitmap.width, bitmap.height)
            val manualNoCrop = CalibrationPrefs.isCropDisabled(applicationContext)
            debugFrameCounter++
            val logThisFrame = debugFrameCounter % DEBUG_LOG_EVERY_N_FRAMES == 0

            if (!calibrator.done) {
                val result = detector.detect(bitmap)
                calibrator.feed(result.detections)
                val elapsedCalibMs = (frameStartNanos - sessionStartNanos) / 1_000_000
                if (elapsedCalibMs > CALIBRATION_MS) calibrator.finish()
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - frameStartNanos) / 1_000_000
                val effectiveCorte = if (manualNoCrop) PanelCalibrator.SEM_PAINEL_CORTE else calibrator.result

                if (logThisFrame) {
                    logCalibrating(bitmap.width, bitmap.height, decoded.rotationDegrees, calibrator, result.detections)
                }
                publishPreviewFrame(bitmap, decoded.rotationDegrees, effectiveCorte, result.detections, leader = null)

                _state.value = _state.value.copy(
                    fps = fps, latencyMs = latencyMs, usingGpu = detector.usingGpu,
                    calibrating = !calibrator.done, corteFrac = effectiveCorte,
                    status = corteStatusText(calibrator, effectiveCorte, manualNoCrop)
                )
            } else {
                val effectiveCorte = if (manualNoCrop) PanelCalibrator.SEM_PAINEL_CORTE else calibrator.result
                val corteH = (bitmap.height * effectiveCorte).toInt().coerceIn(1, bitmap.height)
                val cropped = if (corteH == bitmap.height) bitmap
                              else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, corteH)
                val result = detector.detect(cropped)
                if (cropped !== bitmap) cropped.recycle()

                val ttc = ttcEngine.process(tSec, result.detections, bitmap.width)
                val latencyMs = (SystemClock.elapsedRealtimeNanos() - frameStartNanos) / 1_000_000

                if (ttc.alertaDisparado) {
                    soundPlayer.playCollisionAlert()
                }
                if (!startedSoundPlayed) {
                    startedSoundPlayed = true
                    soundPlayer.playStarted()
                }

                if (logThisFrame) {
                    logMonitoring(
                        bitmap.width, bitmap.height, decoded.rotationDegrees, calibrator,
                        effectiveCorte, corteH, manualNoCrop, result.detections, ttc
                    )
                }
                publishPreviewFrame(bitmap, decoded.rotationDegrees, effectiveCorte, result.detections, leader = ttc.leader)

                _state.value = _state.value.copy(
                    running = true, calibrating = false, ttcS = ttc.ttcS,
                    alerta = ttc.baixoSeguidos >= TtcEngine.CONFIRMA_FRAMES,
                    fps = fps, latencyMs = latencyMs, usingGpu = detector.usingGpu,
                    corteFrac = effectiveCorte, status = corteStatusText(calibrator, effectiveCorte, manualNoCrop)
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

    /** Texto usado no status da tela e reaproveitado no log — sempre deixa claro qual corte esta em uso e por que. */
    private fun corteStatusText(calibrator: PanelCalibrator, effectiveCorte: Float, manualNoCrop: Boolean): String = when {
        manualNoCrop -> "corte desativado manualmente — quadro inteiro (corte %.2f)".format(effectiveCorte)
        calibrator.painelEncontrado == true -> "painel encontrado — corte calibrado em %.2f".format(effectiveCorte)
        calibrator.painelEncontrado == false -> "painel nao encontrado — quadro inteiro (corte %.2f)".format(effectiveCorte)
        else -> "calibrando corte do painel..."
    }

    /** So copia e publica o bitmap quando o preview esta ligado — custo zero no caminho padrao (desligado). */
    private fun publishPreviewFrame(
        bitmap: Bitmap, rotationDegrees: Int, corteFrac: Float, detections: List<Detection>, leader: Detection?
    ) {
        if (!_previewEnabled.value) return
        val corteYPx = (bitmap.height * corteFrac).toInt().coerceIn(0, bitmap.height)
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        _previewFrame.value = PreviewFrame(
            bitmap = copy,
            frameW = bitmap.width,
            frameH = bitmap.height,
            rotationDegrees = rotationDegrees,
            corteFrac = corteFrac,
            corteYPx = corteYPx,
            detections = detections,
            leader = leader,
            leaderWidthPx = leader?.width
        )
    }

    private fun logCalibrating(
        frameW: Int, frameH: Int, rotation: Int, calibrator: PanelCalibrator, deteccoes: List<Detection>
    ) {
        Log.d(TAG_DEBUG, "== frame (calibrando corte) ==")
        Log.d(TAG_DEBUG, "frame ${frameW}x${frameH} rotacao=${rotation}graus")
        Log.d(TAG_DEBUG, "corte provisorio: frac=%.3f (sem corte ate concluir a calibracao)".format(calibrator.result))
        Log.d(TAG_DEBUG, "deteccoes brutas do modelo: ${deteccoes.size}")
        dumpDeteccoes(deteccoes)
    }

    private fun logMonitoring(
        frameW: Int, frameH: Int, rotation: Int, calibrator: PanelCalibrator,
        effectiveCorte: Float, corteYPx: Int, manualNoCrop: Boolean,
        deteccoes: List<Detection>, ttc: TtcResult
    ) {
        Log.d(TAG_DEBUG, "== frame (monitorando) ==")
        Log.d(TAG_DEBUG, "frame ${frameW}x${frameH} rotacao=${rotation}graus")
        Log.d(TAG_DEBUG, "painel: ${if (calibrator.painelEncontrado == true) "encontrado" else "nao encontrado"}" +
                (if (manualNoCrop) " (corte manual desativado nos ajustes — sobrepoe a calibracao)" else ""))
        Log.d(TAG_DEBUG, "corte em uso: frac=%.3f px=%d (deteccao so roda acima dessa linha)".format(effectiveCorte, corteYPx))
        Log.d(TAG_DEBUG, "deteccoes brutas do modelo (ja filtradas por classe/conf, sem filtro de largura/terco): ${deteccoes.size}")
        dumpDeteccoes(deteccoes)
        Log.d(TAG_DEBUG, "apos filtro de largura (descarta caixa > 70% do quadro): ${ttc.afterWidthFilter}")
        Log.d(TAG_DEBUG, "apos filtro do terco central (33%-67% da largura): ${ttc.afterCenterFilter}")

        val leader = ttc.leader
        if (leader == null) {
            Log.d(TAG_DEBUG, "sem lider: ${ttc.semLiderMotivo}")
        } else {
            Log.d(TAG_DEBUG, "lider: classe=${nomeClasse(leader.classId)} largura=%.0fpx centerX=%.0f".format(leader.width, leader.centerX))
            Log.d(TAG_DEBUG, "largura suavizada=%s dw=%s px/s".format(
                ttc.larguraSuave?.let { "%.1fpx".format(it) } ?: "--",
                ttc.dwPxS?.let { "%.1f".format(it) } ?: "--"
            ))
            if (ttc.deriva != null) {
                Log.d(TAG_DEBUG, "deriva lateral=%.4f (limite=${TtcEngine.DERIVA_MAX})".format(ttc.deriva))
            }
            if (ttc.ttcS != null) {
                Log.d(TAG_DEBUG, "TTC calculado = %.2fs (alerta=${ttc.alertaDisparado}, baixoSeguidos=${ttc.baixoSeguidos})".format(ttc.ttcS))
            } else {
                Log.d(TAG_DEBUG, "TTC NAO calculado: ${ttc.semTtcMotivo ?: "motivo desconhecido"}")
            }
        }
    }

    private fun dumpDeteccoes(deteccoes: List<Detection>) {
        if (deteccoes.isEmpty()) {
            Log.d(TAG_DEBUG, "  (nenhuma deteccao de veiculo neste frame)")
            return
        }
        deteccoes.forEachIndexed { i, d ->
            Log.d(
                TAG_DEBUG,
                "  [$i] classe=${nomeClasse(d.classId)} conf=%.2f caixa=(%.0f,%.0f,%.0f,%.0f) largura=%.0f centerX=%.0f"
                    .format(d.conf, d.x1, d.y1, d.x2, d.y2, d.width, d.centerX)
            )
        }
    }

    private fun nomeClasse(classId: Int): String = when (classId) {
        2 -> "carro"
        3 -> "moto"
        5 -> "onibus"
        7 -> "caminhao"
        else -> "classe$classId"
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

    private data class DecodedFrame(val bitmap: Bitmap, val rotationDegrees: Int)

    /** RGBA_8888 (CameraX 1.3+) copia direto para Bitmap.ARGB_8888: mesma ordem de bytes por pixel. */
    private fun imageProxyToBitmap(image: ImageProxy): DecodedFrame {
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
        if (rotation == 0) return DecodedFrame(cropped, 0)
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        if (rotated !== cropped) cropped.recycle()
        return DecodedFrame(rotated, rotation)
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
        _previewFrame.value = null
    }
}
