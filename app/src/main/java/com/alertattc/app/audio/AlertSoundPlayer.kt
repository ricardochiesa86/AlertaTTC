package com.alertattc.app.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Som, nunca freio. Dois padroes bem distintos:
 *  - colisao: 3 bips curtos e rapidos (algo esta prestes a acontecer)
 *  - indisponivel: 1 tom longo e grave (o sistema parou de enxergar —
 *    camera interrompida, deteccao caiu, ou throttle termico severo). Um
 *    app de seguranca que fica em silencio quando falha e pior que
 *    nenhum app.
 *
 * STREAM_ALARM de proposito: e um alerta de seguranca, precisa furar
 * midia/navegacao tocando no aparelho.
 */
class AlertSoundPlayer(private val scope: CoroutineScope) {

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
    private var job: Job? = null

    fun playCollisionAlert() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            repeat(3) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                delay(260)
            }
        }
    }

    fun playUnavailable() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 600)
        }
    }

    fun release() {
        job?.cancel()
        tone.release()
    }
}
