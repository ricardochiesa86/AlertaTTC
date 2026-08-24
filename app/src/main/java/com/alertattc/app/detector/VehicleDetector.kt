package com.alertattc.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 * Wrapper do yolo11s.tflite via LiteRT (org.tensorflow.lite.*). Tenta o
 * delegate de GPU (Mali, via OpenGL/OpenCL) e cai para CPU se a GPU nao
 * estiver disponivel ou a criacao do delegate falhar. NNAPI nao e usado:
 * foi descontinuado a partir do Android 15 e os drivers Exynos sao
 * inconsistentes com ele.
 *
 * So filtra as classes COCO de veiculo (car=2, motorcycle=3, bus=5,
 * truck=7). A localizacao de caixa/classe no tensor de saida e detectada
 * em runtime (ver [channelsFirst]) porque exports diferentes do
 * ultralytics produzem [1, 84, 8400] ou [1, 8400, 84].
 */
class VehicleDetector(
    private val context: Context,
    private val assetPath: String = "yolo11s.tflite",
    private val inputSize: Int = 640,
    private val confMin: Float = 0.25f,
    private val vehicleClasses: Set<Int> = setOf(2, 3, 5, 7),
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "VehicleDetector"
    }

    private lateinit var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    var usingGpu: Boolean = false
        private set

    private val letterbox = Letterbox(inputSize)

    private lateinit var outputShape: IntArray
    private lateinit var outputBuffer: Array<Array<FloatArray>>
    private var channelsFirst = true
    private var numAnchors = 0
    private var numChannels = 0

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixelBuf = IntArray(inputSize * inputSize)

    /** Deve ser chamado uma vez, fora da thread principal, antes do primeiro detect(). */
    fun init() {
        val modelBuffer = loadModelFile()
        val (built, usedGpu) = buildInterpreter(modelBuffer)
        interpreter = built
        usingGpu = usedGpu

        outputShape = interpreter.getOutputTensor(0).shape() // [1, C, N] ou [1, N, C]
        require(outputShape.size == 3) { "Saida inesperada do modelo: ${outputShape.joinToString()}" }
        channelsFirst = outputShape[1] < outputShape[2]
        numChannels = if (channelsFirst) outputShape[1] else outputShape[2]
        numAnchors = if (channelsFirst) outputShape[2] else outputShape[1]
        outputBuffer = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }

        Log.i(TAG, "modelo carregado: gpu=$usingGpu shape=${outputShape.joinToString()} " +
                "channelsFirst=$channelsFirst classes=${numChannels - 4}")
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

        val detections = postprocess(params)
        return DetectorResult(detections, usingGpu, inferenceMs)
    }

    private fun fillInput(bmp: Bitmap) {
        inputBuffer.rewind()
        bmp.getPixels(pixelBuf, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixelBuf) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()
    }

    private fun valueAt(anchor: Int, channel: Int): Float =
        if (channelsFirst) outputBuffer[0][channel][anchor] else outputBuffer[0][anchor][channel]

    private fun postprocess(params: Letterbox.Params): List<Detection> {
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
            if (bestClass < 0 || bestConf < confMin) continue

            val cx = valueAt(i, 0)
            val cy = valueAt(i, 1)
            val bw = valueAt(i, 2)
            val bh = valueAt(i, 3)

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
