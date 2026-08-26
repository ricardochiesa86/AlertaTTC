package com.alertattc.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alertattc.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Unica tela ativa: TTC ao vivo, overlay de alerta, fps e latencia de
 * inferencia. Nao ha onboarding aqui de proposito — essa interface
 * completa vem depois, a partir de um prototipo de design separado. O
 * preview de camera (diagnostico) e a tela de ajustes existem para dar
 * visibilidade da cadeia de deteccao, nao como produto final.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertaTTC-Metrics"
    }

    private lateinit var binding: ActivityMainBinding

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            CollisionAlertService.start(this)
        } else {
            binding.tvStatus.text = "permissao de camera negada — sem ela o app nao funciona"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStop.setOnClickListener {
            CollisionAlertService.stop(this)
            finish()
        }

        binding.btnTogglePreview.setOnClickListener {
            CollisionAlertService.setPreviewEnabled(!CollisionAlertService.previewEnabled.value)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRecalibrate.setOnClickListener {
            CollisionAlertService.requestRecalibration()
        }

        observeState()
        observePreview()
        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            CollisionAlertService.start(this)
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CollisionAlertService.state.collect { state -> render(state) }
            }
        }
    }

    private fun observePreview() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CollisionAlertService.previewEnabled.collect { enabled ->
                        binding.btnTogglePreview.text = getString(
                            if (enabled) R.string.btn_hide_camera else R.string.btn_show_camera
                        )
                        binding.previewContainer.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
                        if (!enabled) binding.detectionOverlay.update(null)
                    }
                }
                launch {
                    CollisionAlertService.previewFrame.collect { frame ->
                        binding.detectionOverlay.update(frame)
                    }
                }
            }
        }
    }

    private fun render(state: UiState) {
        binding.tvTtc.text = if (state.ttcS != null) "%.1f s".format(state.ttcS) else getString(R.string.ttc_placeholder)
        binding.tvFps.text = "%.1f".format(state.fps)
        binding.tvLatency.text = "${state.latencyMs} ms"

        // state.status ja descreve o corte em uso com contexto (painel encontrado/nao/manual),
        // entao nao repete o numero cru aqui pra nao duplicar a informacao.
        val motor = if (state.usingGpu) "GPU" else "CPU"
        binding.tvStatus.text = "$motor · ${state.status}"

        val alertVisible = if (state.alerta) android.view.View.VISIBLE else android.view.View.INVISIBLE
        binding.tvAlert.visibility = alertVisible
        binding.overlayBorder.visibility = alertVisible

        // Requisito: instrumentacao vive no log, nao so na tela, pra dar pra medir depois com adb logcat.
        Log.i(TAG, "fps=%.2f latencyMs=%d ttc=%s alerta=%s gpu=%s status=%s".format(
            state.fps, state.latencyMs, state.ttcS?.let { "%.2f".format(it) } ?: "--",
            state.alerta, state.usingGpu, state.status
        ))
    }
}
