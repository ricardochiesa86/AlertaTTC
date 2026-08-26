package com.alertattc.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Regra que governa este arquivo: e IMPOSSIVEL confundir o som de
 * colisao com qualquer outro. As tres categorias sao desenhadas para
 * divergir em toda dimensao perceptivel ao mesmo tempo — nao so o tom,
 * mas volume, cadencia e duracao total — porque depender so do timbre de
 * uma constante do ToneGenerator (dificil de prever ouvindo o codigo) e
 * fragil. Um relato de campo confundiu colisao com indisponivel antes
 * dessa reescrita; a correcao aqui e estrutural, nao um ajuste fino.
 *
 *                  volume   cadencia              duracao total
 *  colisao         maximo   staccato, repetido    ~1.0-1.5s
 *  indisponivel    medio    1 tom sustentado       ~0.9s
 *  status/inicio   baixo    1 tom curto e suave     ~0.15s
 *
 * Som, nunca freio — o app so avisa.
 */
class AlertSoundPlayer(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        /** Nome curto de cada variante de som de colisao, para a tela de ajustes. */
        val VARIANT_NAMES = listOf(
            "Bipes agudos rapidos",
            "Alarme insistente",
            "Sequencia acelerada"
        )
    }

    private var job: Job? = null

    fun playCollisionAlert() {
        val volume = AlertPrefs.getVolume(context)
        val variant = AlertPrefs.getVariant(context)
        job?.cancel()
        job = scope.launch(Dispatchers.Default) { runCollisionPattern(volume, variant) }
    }

    fun playUnavailable() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) { runUnavailablePattern() }
    }

    fun playStarted() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) { runStartedPattern() }
    }

    /**
     * Publico para a tela de ajustes tocar amostras com volume/variante
     * escolhidos ali mesmo, sem depender do que esta salvo nas prefs.
     */
    suspend fun runCollisionPattern(volume: Int, variant: Int) {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, volume.coerceIn(10, 100))
        try {
            when (variant) {
                // seis bipes curtos, agudos e muito rapidos — staccato de alta urgencia
                0 -> repeat(6) {
                    tone.startTone(ToneGenerator.TONE_CDMA_PIP, 100)
                    delay(150)
                }
                // quatro toques de alarme mais longos e insistentes
                1 -> repeat(4) {
                    tone.startTone(ToneGenerator.TONE_SUP_ERROR, 200)
                    delay(350)
                }
                // cinco toques em sequencia ainda mais acelerada
                else -> repeat(5) {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120)
                    delay(160)
                }
            }
        } finally {
            tone.release()
        }
    }

    suspend fun runUnavailablePattern() {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 65)
        try {
            // um unico tom grave e sustentado, sem repeticao: "parei de te
            // proteger", nao "aja agora". Volume moderado de proposito —
            // nao deve ser confundido com o alerta no volume maximo.
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 900)
            delay(950)
        } finally {
            tone.release()
        }
    }

    suspend fun runStartedPattern() {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 35)
        try {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            delay(200)
        } finally {
            tone.release()
        }
    }

    fun release() {
        job?.cancel()
    }
}
