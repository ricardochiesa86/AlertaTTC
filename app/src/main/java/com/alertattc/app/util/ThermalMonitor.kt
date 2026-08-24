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
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

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
            listener?.let { powerManager.removeThermalStatusListener(it) }
        }
        listener = null
    }
}
