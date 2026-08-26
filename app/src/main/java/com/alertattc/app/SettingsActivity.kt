package com.alertattc.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alertattc.app.audio.AlertPrefs
import com.alertattc.app.audio.AlertSoundPlayer
import com.alertattc.app.databinding.ActivitySettingsBinding
import com.alertattc.app.ttc.CalibrationPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tela de ajustes: usada com o carro parado, entao pode ser uma
 * interface normal de configuracao (Material 3, alvos de toque
 * grandes). Cada tipo de alerta e cada variante de som tem um botao de
 * amostra ali mesmo — a ideia e deixar o usuario ouvir e comparar antes
 * de sair dirigindo com o app rodando.
 *
 * O player de amostra aqui e independente do player do
 * CollisionAlertService (cada um cria seu proprio ToneGenerator sob
 * demanda) — nao ha estado compartilhado pra sincronizar, e o pior caso
 * de tocar os dois ao mesmo tempo e inofensivo.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var soundPlayer: AlertSoundPlayer
    private var selectedVariant = 0

    /** So uma amostra toca por vez — tocar outra cancela a anterior, senao dois toques rapidos se sobrepoem. */
    private var sampleJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        soundPlayer = AlertSoundPlayer(applicationContext, lifecycleScope)
        selectedVariant = AlertPrefs.getVariant(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSampleCollision.setOnClickListener {
            playVariant(selectedVariant, currentVolume())
        }
        binding.btnSampleUnavailable.setOnClickListener {
            playSample { soundPlayer.runUnavailablePattern() }
        }
        binding.btnSampleStarted.setOnClickListener {
            playSample { soundPlayer.runStartedPattern() }
        }

        buildVariantRows()

        binding.sliderVolume.value = AlertPrefs.getVolume(this).toFloat()
        binding.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) AlertPrefs.setVolume(this, value.toInt())
        }
        binding.btnSampleVolume.setOnClickListener {
            playVariant(selectedVariant, currentVolume())
        }

        binding.switchDisableCrop.isChecked = CalibrationPrefs.isCropDisabled(this)
        binding.switchDisableCrop.setOnCheckedChangeListener { _, checked ->
            CalibrationPrefs.setCropDisabled(this, checked)
        }
    }

    private fun currentVolume(): Int = binding.sliderVolume.value.toInt()

    /** Toca a variante especifica (nao necessariamente a selecionada) — usada pelo botao de cada linha, pra comparar antes de escolher. */
    private fun playVariant(variant: Int, volume: Int) {
        playSample { soundPlayer.runCollisionPattern(volume, variant) }
    }

    private fun playSample(block: suspend () -> Unit) {
        sampleJob?.cancel()
        sampleJob = lifecycleScope.launch { block() }
    }

    private fun buildVariantRows() {
        binding.radioGroupVariant.removeAllViews()

        AlertSoundPlayer.VARIANT_NAMES.forEachIndexed { index, name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val radio = MaterialRadioButton(this).apply {
                text = name
                id = View.generateViewId()
                isChecked = index == selectedVariant
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                minHeight = dp(56)
                textSize = 16f
                setTextColor(getColorCompat(R.color.text_primary))
                setOnClickListener {
                    selectedVariant = index
                    AlertPrefs.setVariant(this@SettingsActivity, index)
                    syncRadioSelection()
                }
            }

            val playButton = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "▶"
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(48)).apply {
                    marginStart = dp(8)
                }
                setOnClickListener { playVariant(index, currentVolume()) }
            }

            row.addView(radio)
            row.addView(playButton)
            binding.radioGroupVariant.addView(row)
        }
    }

    /** As caixas de selecao vivem em LinearLayouts separados dentro do RadioGroup, entao o exclusive-check nativo do RadioGroup nao se aplica — feito na mao aqui. */
    private fun syncRadioSelection() {
        for (i in 0 until binding.radioGroupVariant.childCount) {
            val row = binding.radioGroupVariant.getChildAt(i) as? LinearLayout ?: continue
            val radio = row.getChildAt(0) as? MaterialRadioButton ?: continue
            radio.isChecked = (i == selectedVariant)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun getColorCompat(resId: Int) = androidx.core.content.ContextCompat.getColor(this, resId)

    override fun onDestroy() {
        super.onDestroy()
        soundPlayer.release()
    }
}
