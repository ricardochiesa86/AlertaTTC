package com.alertattc.app.detector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * Letterbox padrao do YOLO: redimensiona mantendo proporcao e preenche o
 * resto com cinza 114 (mesma convencao usada pelo export do ultralytics).
 */
class Letterbox(private val targetSize: Int = 640) {

    data class Params(val scale: Float, val padX: Float, val padY: Float, val srcW: Int, val srcH: Int)

    fun apply(src: Bitmap): Pair<Bitmap, Params> {
        val srcW = src.width
        val srcH = src.height
        val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== src) resized.recycle()
        return out to Params(scale, padX, padY, srcW, srcH)
    }

    /** Converte um ponto do espaco do modelo (targetSize x targetSize) de volta ao frame original. */
    fun unletterbox(x: Float, y: Float, params: Params): Pair<Float, Float> {
        val ux = (x - params.padX) / params.scale
        val uy = (y - params.padY) / params.scale
        return ux to uy
    }
}
