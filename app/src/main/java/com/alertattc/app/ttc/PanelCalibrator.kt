package com.alertattc.app.ttc

import com.alertattc.app.detector.Detection
import com.alertattc.app.util.median

/**
 * Descobre sozinho a linha do painel do proprio carro: caixa larga (>60%
 * do quadro), ancorada na metade de baixo, recorrente em mais de 30% dos
 * frames analisados. Porta direta de `calibrar_corte` do script Python.
 *
 * Ao vivo nao existe "rebobinar o video" como no script offline: o
 * servico alimenta este calibrador com deteccoes SEM corte por uma janela
 * de tempo no arranque (ver CollisionAlertService.CALIBRATION_MS) e so
 * depois passa a recortar os frames pela linha encontrada.
 */
class PanelCalibrator(
    private val frameW: Int,
    private val frameH: Int,
    private val maxFrames: Int = 300,
    private val defaultCutoff: Float = 0.62f
) {
    private val panelYs = mutableListOf<Float>()
    private val framesWithPanel = mutableSetOf<Int>()
    private var frameCount = 0

    var done = false
        private set
    var result: Float = defaultCutoff
        private set

    /** Alimenta com as deteccoes cruas de um frame inteiro, sem recorte de painel nem de terco central. */
    fun feed(detections: List<Detection>) {
        if (done) return
        frameCount++
        for (d in detections) {
            val w = d.x2 - d.x1
            val cy = (d.y1 + d.y2) / 2f
            if (w > 0.6f * frameW && cy > 0.5f * frameH) {
                panelYs.add(d.y1)
                framesWithPanel.add(frameCount)
            }
        }
        if (frameCount >= maxFrames) finish()
    }

    fun finish() {
        if (done) return
        done = true
        if (frameCount == 0 || framesWithPanel.size.toFloat() / frameCount < 0.30f) {
            result = defaultCutoff
            return
        }
        val medianY = median(panelYs)
        result = ((medianY - 0.04f * frameH) / frameH).toFloat()
    }
}
