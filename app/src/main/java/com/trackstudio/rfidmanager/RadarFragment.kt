package com.trackstudio.rfidmanager

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.trackstudio.rfidmanager.databinding.FragmentRadarBinding

class RadarFragment : Fragment() {

    private var _binding: FragmentRadarBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity
    private val viewModel: SharedViewModel by activityViewModels()

    private var isRadarRunning = false
    private var accumulatedScore = -90f
    private var targetAccumulatedScore = -90f
    private var emaSlowScore = -90f
    private val handler = Handler(Looper.getMainLooper())

    private val soundTicker = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            
            if (accumulatedScore > -88f) {
                // Пикаем OK, если находимся в зеленой зоне (сигнал растет или выше среднего)
                if (accumulatedScore >= emaSlowScore) {
                    mainActivity?.playSound(1, 1.0f) // Barcode beep
                } else {
                    // Пикаем Error, если находимся в красной зоне (сигнал падает ниже среднего)
                    mainActivity?.playSound(2, 1.0f) // Error beep
                }
                
                // Фиксированный темп, больше не "счетчик гейгера"
                handler.postDelayed(this, 300)
            } else {
                handler.postDelayed(this, 200)
            }
        }
    }

    private val graphTicker = object : Runnable {
        override fun run() {
            if (isRadarRunning) {
                val radarPower = mainActivity?.getRadarPower() ?: 30

                if (accumulatedScore == -90f && emaSlowScore == -90f) {
                    emaSlowScore = accumulatedScore
                } else {
                    emaSlowScore = (0.02f * accumulatedScore) + (0.98f * emaSlowScore)
                }

                binding.radarGraph.addValue(accumulatedScore, radarPower, radarPower)

                if (accumulatedScore > -89f) {
                    binding.tvOverlayScore.visibility = View.VISIBLE
                    binding.tvOverlayScore.text = String.format(Locale.US, "%.1f dBm", accumulatedScore)
                    binding.tvOverlayScore.setTextColor(Color.parseColor("#00E676"))
                } else {
                    binding.tvOverlayScore.visibility = View.GONE
                }

                // Smoothly interpolate current dBm to target dBm
                accumulatedScore += (targetAccumulatedScore - accumulatedScore) * 0.6f
                
                handler.postDelayed(this, 50) // Fast graph updates for smoothness
            }
        }
    }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRadarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.btnToggleRadar.isEnabled = connected
        }

        binding.btnToggleRadar.setOnClickListener {
            if (isRadarRunning) stopRadar() else startRadar()
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasteData = item?.text?.toString()?.trim()
            if (!pasteData.isNullOrEmpty()) {
                binding.editTargetEpc.setText(pasteData)
                mainActivity?.appendLog("Pasted: $pasteData")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRadarRunning) stopRadar()
    }

    private fun startRadar() {
        val activity = mainActivity ?: return
        val target = try {
            EpcTargetNormalizer.normalize(binding.editTargetEpc.text.toString())
        } catch (ex: IllegalArgumentException) {
            activity.appendLog("Radar rejected: ${ex.message}")
            return
        }

        binding.btnToggleRadar.isEnabled = false
        binding.editTargetEpc.isEnabled = false
        binding.btnPaste.isEnabled = false
        binding.radarGraph.clear()
        binding.tvOverlayScore.visibility = View.GONE
        accumulatedScore = -90f
        targetAccumulatedScore = -90f
        emaSlowScore = -90f

        activity.startRadar(
            target.label,
            onStartResult = { started ->
                binding.btnToggleRadar.isEnabled = true
                if (started) {
                    isRadarRunning = true
                    binding.btnToggleRadar.setText(R.string.radar_stop)
                    binding.tvOverlayScore.visibility = View.VISIBLE
                    handler.post(soundTicker)
                    handler.post(graphTicker)
                } else {
                    isRadarRunning = false
                    binding.btnToggleRadar.setText(R.string.radar_start)
                    binding.editTargetEpc.isEnabled = true
                    binding.btnPaste.isEnabled = true
                    binding.tvOverlayScore.visibility = View.GONE
                }
            }
        ) { locationValue, found ->
            targetAccumulatedScore = if (found) {
                (-90f + locationValue.coerceIn(0, 100) * 0.8f).coerceIn(-90f, -10f)
            } else {
                (targetAccumulatedScore - 5f).coerceAtLeast(-90f)
            }
        }
        activity.appendLog("Searching for tag ${target.label}")
    }

    private fun stopRadar() {
        isRadarRunning = false
        handler.removeCallbacks(soundTicker)
        handler.removeCallbacks(graphTicker)
        binding.btnToggleRadar.setText(R.string.radar_start)
        binding.editTargetEpc.isEnabled = true
        binding.btnPaste.isEnabled = true
        binding.tvOverlayScore.visibility = View.GONE
        mainActivity?.stopRadar()
        mainActivity?.appendLog("Radar stopped")
    }

    // Удалено: rebuildTagsList()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRadarRunning) stopRadar()
        _binding = null
    }
}
