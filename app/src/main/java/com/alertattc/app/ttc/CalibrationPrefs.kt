package com.alertattc.app.ttc

import android.content.Context

/**
 * Controle manual de diagnostico: forcar quadro inteiro (corte = 1.0)
 * independente do que a calibracao automatica concluiu. Existe pra
 * isolar, em teste, se o corte do painel esta atrapalhando a deteccao.
 *
 * Padrao: desligado (calibracao automatica no comando). Lido a cada
 * frame pelo CollisionAlertService — SharedPreferences.getBoolean e uma
 * leitura em memoria depois do primeiro acesso, custo desprezivel.
 */
object CalibrationPrefs {
    private const val PREFS = "calibration_prefs"
    private const val KEY_DISABLE_CROP = "disable_panel_crop"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isCropDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_CROP, false)

    fun setCropDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_CROP, disabled).apply()
    }
}
