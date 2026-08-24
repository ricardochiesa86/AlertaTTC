package com.alertattc.app.ttc

import com.alertattc.app.detector.Detection
import com.alertattc.app.util.FixedWindowDeque
import com.alertattc.app.util.median
import com.alertattc.app.util.slope
import kotlin.math.abs
import kotlin.math.roundToInt

data class TtcResult(
    val larguraPx: Float?,
    val larguraSuave: Double?,
    val dwPxS: Double?,
    val deriva: Double?,
    val ttcS: Double?,
    val baixoSeguidos: Int,
    val alertaDisparado: Boolean,
    val leader: Detection?
)

/**
 * Porta fiel de alerta_ttc.py. Cada portao existe para SILENCIAR o
 * alerta, nunca para dispara-lo — nao mexa nos valores sem reler o
 * comentario correspondente no script original.
 *
 * Unico ajuste real em relacao ao script: la as janelas de suavizacao (9
 * frames) e de derivada (15 frames) sao contadas em frames porque o video
 * roda a 30 fps fixo. Aqui o fps ao vivo varia, entao as janelas sao
 * definidas em SEGUNDOS (JANELA_SUAV_S, JANELA_DERIV_S) e convertidas
 * para frames a cada atualizacao de fps medido (ver updateWindowSizes). A
 * formula do TTC em si nao muda: ja opera em px/segundo dos dois lados.
 */
class TtcEngine {

    companion object {
        const val LARG_MIN_PX = 50f
        const val TTC_ALERTA = 2.0
        const val CONFIRMA_FRAMES = 5     // contagem de frames processados, nao de tempo — igual ao script
        const val REARME_S = 3.0
        const val DERIVA_MAX = 0.04
        const val MIN_AMOSTRAS_REGRESSAO = 8 // minimo estrutural p/ regressao, nao escala com fps

        const val JANELA_SUAV_S = 0.3
        const val JANELA_DERIV_S = 0.5
    }

    private val bruto = FixedWindowDeque<Float>(9)
    private val serie = FixedWindowDeque<Pair<Double, Double>>(15)      // (t, largura_suave)
    private val serieCx = FixedWindowDeque<Pair<Double, Double>>(15)    // (t, centro_x)

    private var baixoSeguidos = 0
    private var ultimoAlertaT = -1e9

    /** Chame sempre que o fps medido mudar de forma perceptivel (ex.: a cada segundo). */
    fun updateWindowSizes(fpsMedido: Double) {
        if (fpsMedido <= 0.0) return
        val suavFrames = (fpsMedido * JANELA_SUAV_S).roundToInt().coerceAtLeast(MIN_AMOSTRAS_REGRESSAO)
        val derivFrames = (fpsMedido * JANELA_DERIV_S).roundToInt().coerceAtLeast(MIN_AMOSTRAS_REGRESSAO)
        bruto.maxSize = suavFrames
        serie.maxSize = derivFrames
        serieCx.maxSize = derivFrames
    }

    /** Perdeu o alvo: zera as series. Nunca extrapola TTC de um veiculo que sumiu. */
    fun reset() {
        bruto.clear()
        serie.clear()
        serieCx.clear()
        baixoSeguidos = 0
    }

    /** Lider = maior caixa no terco central; descarta caixa mais larga que 0.7*frameW (e o proprio carro / erro). */
    private fun selecionarLider(deteccoes: List<Detection>, frameW: Int): Detection? {
        val candidatos = deteccoes.filter { it.width <= 0.7f * frameW }
            .filter { it.centerX in (0.33f * frameW)..(0.67f * frameW) }
        return candidatos.maxByOrNull { it.width }
    }

    /**
     * @param t tempo monotonico em segundos desde o inicio da sessao (nao frame_n/fps fixo)
     * @param deteccoes deteccoes de veiculo do frame atual, ja recortado pela linha do painel
     * @param frameW largura do frame usado para deteccao (mesma referencia usada no recorte central)
     */
    fun process(t: Double, deteccoes: List<Detection>, frameW: Int): TtcResult {
        val leader = selecionarLider(deteccoes, frameW)

        var larg: Float? = null
        var suave: Double? = null
        var dw: Double? = null
        var ttc: Double? = null
        var deriva: Double? = null

        if (leader != null) {
            larg = leader.width
            bruto.add(larg)
            suave = median(bruto.toList())
            serie.add(t to suave)
            serieCx.add(t to leader.centerX.toDouble())

            // portao 1: veiculo perto o bastante para o sinal prestar
            if (suave >= LARG_MIN_PX.toDouble() && serie.size >= MIN_AMOSTRAS_REGRESSAO) {
                dw = slope(serie.toList())
                // portao 2: so aproximacao (caixa crescendo de verdade)
                if (dw != null && dw > 1.0) {
                    ttc = suave / dw
                    // portao 5: deriva lateral — objeto escorregando pro lado, voce vai passar dele
                    val dcx = slope(serieCx.toList())
                    if (dcx != null) {
                        deriva = abs(dcx) / frameW
                        if (deriva > DERIVA_MAX) {
                            ttc = null
                        }
                    }
                }
            }
        } else {
            reset()
        }

        // portao 6: confirmacao temporal, contra pico isolado
        baixoSeguidos = if (ttc != null && ttc < TTC_ALERTA) baixoSeguidos + 1 else 0

        // portao 7: rearme, contra alerta repetido em rajada
        var alerta = false
        if (baixoSeguidos >= CONFIRMA_FRAMES && (t - ultimoAlertaT) > REARME_S) {
            alerta = true
            ultimoAlertaT = t
        }

        return TtcResult(larg, suave, dw, deriva, ttc, baixoSeguidos, alerta, leader)
    }
}
