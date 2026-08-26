package com.alertattc.app.audio

import android.content.Context

/**
 * Preferencias de som lidas pela tela de ajustes e pelo AlertSoundPlayer
 * a cada disparo (nao em cache — o usuario pode mudar o volume/variante
 * com o servico ja rodando, e o proximo alerta precisa refletir isso).
 */
object AlertPrefs {
    private const val PREFS = "alert_prefs"
    private const val KEY_VOLUME = "collision_volume"
    private const val KEY_VARIANT = "collision_variant"

    const val DEFAULT_VOLUME = 100
    const val DEFAULT_VARIANT = 0
    const val VARIANT_COUNT = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getVolume(context: Context): Int =
        prefs(context).getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(10, 100)

    fun setVolume(context: Context, volume: Int) {
        prefs(context).edit().putInt(KEY_VOLUME, volume.coerceIn(10, 100)).apply()
    }

    fun getVariant(context: Context): Int =
        prefs(context).getInt(KEY_VARIANT, DEFAULT_VARIANT).coerceIn(0, VARIANT_COUNT - 1)

    fun setVariant(context: Context, variant: Int) {
        prefs(context).edit().putInt(KEY_VARIANT, variant.coerceIn(0, VARIANT_COUNT - 1)).apply()
    }
}
