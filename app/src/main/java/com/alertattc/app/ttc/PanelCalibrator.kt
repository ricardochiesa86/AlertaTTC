package com.alertattc.app.ttc

import com.alertattc.app.detector.Detection
import com.alertattc.app.util.median

/**
 * Descobre sozinho a linha do painel do proprio carro: caixa larga (>60%
 * do quadro), ancorada na metade de baixo, recorrente em mais de 30% dos
 * frames analisados. A deteccao do padrao de painel em si (o que conta
 * como "caixa de painel") e porte direto de `calibrar_corte` do script
 * Python e NAO mudou.
 *
 * O que muda quando nao acha painel: corte = 1.0 (quadro inteiro), nao
 * mais um padrao fixo de 0.62. O corte existe so pra remover o painel do
 * proprio carro, que o YOLO classifica erroneamente como veiculo com
 * confianca alta — sem painel visivel (celular a pe, suporte mais alto,
 * capo baixo, bancada), nao ha o que remover, e cortar so descartaria a
 * area de baixo do quadro, exatamente onde um veiculo perto aparece.
 *
 * Ao vivo nao existe "rebobinar o video" como no script offline: o
 * servico alimenta este calibrador com deteccoes SEM corte por uma janela
 * de tempo no arranque (ver CollisionAlertService.CALIBRATION_MS) e so
 * depois passa a recortar os frames pela linha encontrada (ou nao
 * recorta nada, se nao achou painel).
 */
class PanelCalibrator(
    private val frameW: Int,
    private val frameH: Int,
    private val maxFrames: Int = 300
) {
    companion object {
        /** Sem painel encontrado = sem corte. Tambem o valor provisorio antes da calibracao terminar. */
        const val SEM_PAINEL_CORTE = 1.0f
    }

    private val panelYs = mutableListOf<Float>()
    private val framesWithPanel = mutableSetOf<Int>()
    private var frameCount = 0

    var done = false
        private set

    /** null enquanto calibrando; true/false depois de finish(). */
    var painelEncontrado: Boolean? = null
        private set

    var result: Float = SEM_PAINEL_CORTE
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
            painelEncontrado = false
            result = SEM_PAINEL_CORTE
            return
        }
        painelEncontrado = true
        val medianY = median(panelYs)
        result = ((medianY - 0.04f * frameH) / frameH).toFloat()
    }
}
