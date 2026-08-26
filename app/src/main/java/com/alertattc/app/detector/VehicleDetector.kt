package com.alertattc.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Wrapper do modelo em assets/model.tflite via LiteRT
 * (org.tensorflow.lite.*). O nome do asset e neutro de proposito: o
 * workflow de build baixa o .tflite de uma release e o grava com esse
 * nome, entao trocar a versao do YOLO nao exige mexer no codigo. Tenta o
 * delegate de GPU (Mali, via OpenGL/OpenCL) e cai para CPU se a GPU nao
 * estiver disponivel ou a criacao do delegate falhar. NNAPI nao e usado:
 * foi descontinuado a partir do Android 15 e os drivers Exynos sao
 * inconsistentes com ele.
 *
 * So filtra as classes COCO de veiculo (car=2, motorcycle=3, bus=5,
 * truck=7).
 *
 * TRES coisas variam entre exports do ultralytics e NAO podem ser
 * assumidas — todas sao lidas do proprio modelo em runtime:
 *
 *  1. Layout da entrada: NCHW [1,3,640,640] ou NHWC [1,640,640,3]. Os
 *     dois tem exatamente o mesmo numero de bytes, entao escrever no
 *     layout errado NAO gera erro do TFLite: ele so le lixo e devolve
 *     zero deteccoes em todo frame. Ver [inputChannelsFirst].
 *  2. Orientacao da saida: [1,84,8400] ou [1,8400,84]. Ver [channelsFirst].
 *  3. Escala das coordenadas: o export TFLite normalmente emite xywh
 *     normalizado em 0..1, mas ha exports em pixels (0..640). Detectado
 *     na primeira inferencia. Ver [coordsNormalized].
 *
 * Os tres falham do mesmo jeito visto de fora — zero deteccoes — entao o
 * init() loga o que encontrou, pra diagnostico ser um logcat e nao uma
 * caca.
 */
class VehicleDetector(
    private val context: Context,
    private val assetPath: String = "model.tflite",
    private val fallbackInputSize: Int = 640,
    private val confMin: Float = 0.25f,
    private val vehicleClasses: Set<Int> = setOf(2, 3, 5, 7),
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "VehicleDetector"
        /** Acima disso as coordenadas do modelo sao pixels, nao fracao 0..1. */
        private const val LIMIAR_NORMALIZADO = 1.5f
    }

    private lateinit var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    var usingGpu: Boolean = false
        private set

    private lateinit var letterbox: Letterbox
    private var inputSize = 0
    private var inputChannelsFirst = false

    private lateinit var outputShape: IntArray
    private lateinit var outputBuffer: Array<Array<FloatArray>>
    private var channelsFirst = true
    private var numAnchors = 0
    private var numChannels = 0

    /** null ate a primeira inferencia; depois fixo pelo resto da sessao. */
    private var coordsNormalized: Boolean? = null

    /** Preenchido a cada postprocess; vai no DetectorResult para diagnostico. */
    private var maxVehicleConf = 0f

    private lateinit var inputBuffer: ByteBuffer
    private lateinit var pixelBuf: IntArray

    /** Deve ser chamado uma vez, fora da thread principal, antes do primeiro detect(). */
    fun init() {
        val modelBuffer = loadModelFile()
        val (built, usedGpu) = buildInterpreter(modelBuffer)
        interpreter = built
        usingGpu = usedGpu

        // --- entrada: descobre tamanho e layout em vez de assumir
        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape() // [1,3,S,S] (NCHW) ou [1,S,S,3] (NHWC)
        require(inShape.size == 4) { "Entrada inesperada do modelo: ${inShape.joinToString()}" }
        inputChannelsFirst = inShape[1] == 3 && inShape[3] != 3
        inputSize = if (inputChannelsFirst) inShape[2] else inShape[1]
        if (inputSize <= 0) inputSize = fallbackInputSize

        if (inTensor.dataType() != DataType.FLOAT32) {
            // Modelo quantizado precisaria de escrita em uint8 e de
            // dequantizacao na saida — nao suportado aqui. Melhor falhar
            // alto do que rodar devolvendo zero deteccoes pra sempre.
            throw IllegalStateException(
                "Modelo com entrada ${inTensor.dataType()}, esperado FLOAT32. " +
                "Exporte sem int8 (ver tools/export_model.py)."
            )
        }

        letterbox = Letterbox(inputSize)
        pixelBuf = IntArray(inputSize * inputSize)
        inputBuffer = ByteBuffer
            .allocateDirect(1 * inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())

        // --- saida
        outputShape = interpreter.getOutputTensor(0).shape() // [1, C, N] ou [1, N, C]
        require(outputShape.size == 3) { "Saida inesperada do modelo: ${outputShape.joinToString()}" }
        channelsFirst = outputShape[1] < outputShape[2]
        numChannels = if (channelsFirst) outputShape[1] else outputShape[2]
        numAnchors = if (channelsFirst) outputShape[2] else outputShape[1]
        outputBuffer = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

        Log.i(TAG, "modelo carregado: gpu=$usingGpu")
        Log.i(TAG, "  entrada ${inShape.joinToString("x")} " +
                "layout=${if (inputChannelsFirst) "NCHW" else "NHWC"} size=$inputSize dtype=${inTensor.dataType()}")
        Log.i(TAG, "  saida ${outputShape.joinToString("x")} " +
                "channelsFirst=$channelsFirst anchors=$numAnchors classes=${numChannels - 4}")
    }

    private fun loadModelFile(): MappedByteBuffer {
        val afd = context.assets.openFd(assetPath)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun buildInterpreter(modelBuffer: MappedByteBuffer): Pair<Interpreter, Boolean> {
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                val delegateOptions = compatList.bestOptionsForThisDevice
                val delegate = GpuDelegate(delegateOptions)
                val options = Interpreter.Options().addDelegate(delegate)
                val interpreter = Interpreter(modelBuffer, options)
                gpuDelegate = delegate
                return interpreter to true
            } catch (e: Exception) {
                Log.w(TAG, "delegate de GPU falhou, caindo para CPU", e)
                gpuDelegate?.close()
                gpuDelegate = null
            }
        } else {
            Log.i(TAG, "GPU nao suportada neste aparelho para este modelo, usando CPU")
        }
        val cpuOptions = Interpreter.Options().setNumThreads(4)
        return Interpreter(modelBuffer, cpuOptions) to false
    }

    /** Roda deteccao sobre um bitmap ja recortado (ou nao) do frame da camera. */
    fun detect(bitmap: Bitmap): DetectorResult {
        val (letterboxed, params) = letterbox.apply(bitmap)
        fillInput(letterboxed)
        if (letterboxed !== bitmap) letterboxed.recycle()

        val start = System.nanoTime()
        interpreter.run(inputBuffer, outputBuffer)
        val inferenceMs = (System.nanoTime() - start) / 1_000_000

        if (coordsNormalized == null) detectCoordScale()

        val detections = postprocess(params)
        return DetectorResult(detections, usingGpu, inferenceMs, maxVehicleConf)
    }

    /**
     * O canal 2 e a largura da caixa. Se a maior largura sobre TODOS os
     * anchors couber em 0..1.5, a saida esta normalizada; se chegar perto
     * de [inputSize], esta em pixels. Roda uma vez so, e independe de
     * haver deteccao confiante no frame.
     */
    private fun detectCoordScale() {
        var maxW = 0f
        for (i in 0 until numAnchors) {
            val w = valueAt(i, 2)
            if (w > maxW) maxW = w
        }
        val normalized = maxW <= LIMIAR_NORMALIZADO
        coordsNormalized = normalized
        Log.i(TAG, "escala das coordenadas: ${if (normalized) "normalizada 0..1" else "pixels"} " +
                "(maior largura bruta=%.3f)".format(maxW))
    }

    /**
     * Escreve o bitmap no ByteBuffer de entrada, em RGB normalizado 0..1,
     * no layout que o modelo declarou.
     *
     * NHWC intercala por pixel (R,G,B,R,G,B...). NCHW escreve o plano R
     * inteiro, depois o G, depois o B. Os dois ocupam os mesmos bytes —
     * por isso errar aqui nao gera excecao, so zera as deteccoes.
     */
    private fun fillInput(bmp: Bitmap) {
        inputBuffer.rewind()
        bmp.getPixels(pixelBuf, 0, inputSize, 0, 0, inputSize, inputSize)

        if (inputChannelsFirst) {
            // NCHW: [1, 3, H, W] — um plano por canal
            for (p in pixelBuf) inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
            for (p in pixelBuf) inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
            for (p in pixelBuf) inputBuffer.putFloat((p and 0xFF) / 255f)          // B
        } else {
            // NHWC: [1, H, W, 3] — canais intercalados por pixel
            for (p in pixelBuf) {
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
                inputBuffer.putFloat((p and 0xFF) / 255f)          // B
            }
        }
        inputBuffer.rewind()
    }

    private fun valueAt(anchor: Int, channel: Int): Float =
        if (channelsFirst) outputBuffer[0][channel][anchor] else outputBuffer[0][anchor][channel]

    private fun postprocess(params: Letterbox.Params): List<Detection> {
        // Coordenadas normalizadas viram pixels do espaco do modelo antes
        // do unletterbox, que trabalha em pixels.
        val escala = if (coordsNormalized == true) inputSize.toFloat() else 1f

        maxVehicleConf = 0f
        val raw = ArrayList<Detection>()
        for (i in 0 until numAnchors) {
            var bestClass = -1
            var bestConf = 0f
            for (cls in vehicleClasses) {
                val c = valueAt(i, 4 + cls)
                if (c > bestConf) {
                    bestConf = c
                    bestClass = cls
                }
            }
            if (bestConf > maxVehicleConf) maxVehicleConf = bestConf
            if (bestClass < 0 || bestConf < confMin) continue

            val cx = valueAt(i, 0) * escala
            val cy = valueAt(i, 1) * escala
            val bw = valueAt(i, 2) * escala
            val bh = valueAt(i, 3) * escala

            val (ux1, uy1) = letterbox.unletterbox(cx - bw / 2f, cy - bh / 2f, params)
            val (ux2, uy2) = letterbox.unletterbox(cx + bw / 2f, cy + bh / 2f, params)

            raw.add(
                Detection(
                    x1 = ux1.coerceIn(0f, params.srcW.toFloat()),
                    y1 = uy1.coerceIn(0f, params.srcH.toFloat()),
                    x2 = ux2.coerceIn(0f, params.srcW.toFloat()),
                    y2 = uy2.coerceIn(0f, params.srcH.toFloat()),
                    classId = bestClass,
                    conf = bestConf
                )
            )
        }
        return nms(raw)
    }

    private fun nms(boxes: List<Detection>): List<Detection> {
        val sorted = boxes.sortedByDescending { it.conf }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(it, best) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
