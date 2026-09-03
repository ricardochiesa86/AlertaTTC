package com.alertattc.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.alertattc.app.PreviewFrame
import com.alertattc.app.R
import kotlin.math.min

/**
 * Desenha o bitmap analisado (nao o preview de camera "de verdade" da
 * CameraX — o mesmo frame que o VehicleDetector recebeu) com um overlay
 * de diagnostico por cima: caixas por classe, lider destacado, linha do
 * corte do painel e linhas do terco central. Existe para responder
 * visualmente "o que a cadeia de deteccao esta vendo", nao para ser um
 * preview de camera bonito.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: PreviewFrame? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        color = ContextCompat.getColor(context, R.color.class_leader)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overlay_label_bg)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }
    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val thirdLineColor = ContextCompat.getColor(context, R.color.overlay_third_line)
    private val corteLineColor = ContextCompat.getColor(context, R.color.overlay_corte_line)

    /** Chamado pela Activity a cada atualizacao do StateFlow de preview. Passe null para limpar. */
    fun update(newFrame: PreviewFrame?) {
        frame = newFrame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        if (f.frameW <= 0 || f.frameH <= 0 || width <= 0 || height <= 0) return

        val scale = min(width.toFloat() / f.frameW, height.toFloat() / f.frameH)
        val offX = (width - f.frameW * scale) / 2f
        val offY = (height - f.frameH * scale) / 2f

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        if (!f.bitmap.isRecycled) canvas.drawBitmap(f.bitmap, 0f, 0f, null)
        canvas.restore()

        fun tx(x: Float) = offX + x * scale
        fun ty(y: Float) = offY + y * scale
        val left = offX
        val right = offX + f.frameW * scale
        val top = offY
        val bottom = offY + f.frameH * scale

        // linhas do terco central (33% / 67%) — faixa que o filtro de lider aceita
        linePaint.color = thirdLineColor
        val x33 = tx(f.frameW * 0.33f)
        val x67 = tx(f.frameW * 0.67f)
        canvas.drawLine(x33, top, x33, bottom, linePaint)
        canvas.drawLine(x67, top, x67, bottom, linePaint)

        // linha do corte do painel
        linePaint.color = corteLineColor
        val yCorte = ty(f.corteYPx.toFloat())
        canvas.drawLine(left, yCorte, right, yCorte, linePaint)

        // alvos rastreados: id estavel, para casar o que se ve com o log
        for (alvo in f.targets) {
            val d = alvo.detection
            val isLeader = alvo.id == f.leaderId
            val color = when {
                isLeader -> leaderPaint.color
                alvo.isStatic -> Color.GRAY
                else -> colorForClass(d.classId)
            }
            val paint = if (isLeader) leaderPaint else boxPaint.apply { this.color = color }
            canvas.drawRect(tx(d.x1), ty(d.y1), tx(d.x2), ty(d.y2), paint)

            val id = if (alvo.id >= 0) "#${alvo.id} " else ""
            val label = when {
                isLeader -> "${id}LIDER ${nameForClass(d.classId)} ${(d.conf * 100).toInt()}%"
                alvo.isStatic -> "${id}FIXO ${nameForClass(d.classId)}"
                else -> "$id${nameForClass(d.classId)} ${(d.conf * 100).toInt()}%"
            }
            drawLabel(canvas, label, tx(d.x1), ty(d.y1), color)
        }

        // largura do lider, em texto, no rodape da area de preview
        f.leaderWidthPx?.let { w ->
            drawInfoLine(canvas, "largura do lider: ${w.toInt()}px", left + 8f, bottom - 12f)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        val baseline = if (y - 34f < 0) y + 34f else y - 6f
        val w = textPaint.measureText(text)
        canvas.drawRect(x, baseline - 28f, x + w + 10f, baseline + 8f, labelBgPaint)
        textPaint.color = color
        canvas.drawText(text, x + 5f, baseline, textPaint)
    }

    private fun drawInfoLine(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, infoTextPaint)
    }

    private fun colorForClass(classId: Int): Int = when (classId) {
        2 -> ContextCompat.getColor(context, R.color.class_car)
        3 -> ContextCompat.getColor(context, R.color.class_moto)
        5 -> ContextCompat.getColor(context, R.color.class_bus)
        7 -> ContextCompat.getColor(context, R.color.class_truck)
        else -> Color.LTGRAY
    }

    private fun nameForClass(classId: Int): String = when (classId) {
        2 -> "carro"
        3 -> "moto"
        5 -> "onibus"
        7 -> "caminhao"
        else -> "?"
    }
}
