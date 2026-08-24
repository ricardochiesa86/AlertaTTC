package com.alertattc.app.detector

/** Caixa em coordenadas de pixel do frame de entrada (nao do modelo). */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val classId: Int,
    val conf: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
}

data class DetectorResult(
    val detections: List<Detection>,
    val usedGpu: Boolean,
    val inferenceMs: Long
)
