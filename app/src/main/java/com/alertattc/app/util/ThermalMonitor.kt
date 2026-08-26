package com.alertattc.app.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Observa o throttle termico do aparelho (API 29+). Em aparelhos como o
 * Exynos 990 do S20 FE, MODERATE ja e sinal de que o sistema comecou a
 * reduzir clock — relevante porque e exatamente a condicao que este
 * esqueleto precisa provar que NAO acontece em poucos minutos de uso.
 */
class ThermalMonitor(context: Context, private val onStatusChanged: (throttling: Boolean, statusCode: Int) -> Unit) {
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Tipado como Any? de proposito, nao como
     * PowerManager.OnThermalStatusChangedListener: esse tipo so existe em
     * API 29+, e esta classe e instanciada em todo aparelho (minSdk 26).
     * Um campo com tipo ausente tocado fora de um guard de versao (o
     * `listener = null` do stop()) e candidato a VerifyError em Android
     * 8.0-9.0 e a erro NewApi no lint. Com Any?, toda referencia ao tipo
     * novo fica confinada dentro dos blocos guardados abaixo.
     */
    private var listener: Any? = null

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                onStatusChanged(status >= PowerManager.THERMAL_STATUS_MODERATE, status)
            }
            listener = l
            powerManager.addThermalStatusListener(l)
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (listener as? PowerManager.OnThermalStatusChangedListener)?.let {
                powerManager.removeThermalStatusListener(it)
            }
        }
        listener = null
    }
}
