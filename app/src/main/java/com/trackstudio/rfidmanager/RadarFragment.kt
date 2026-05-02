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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.trackstudio.rfidmanager.databinding.FragmentRadarBinding
import kotlin.math.abs

class RadarFragment : Fragment() {

    private var _binding: FragmentRadarBinding? = null
    private val binding get() = _binding!!
    private val mainActivity get() = activity as? MainActivity
    private val viewModel: SharedViewModel by activityViewModels()

    private var isRadarRunning = false
    private var lastHardwareUpdate = 0L
    private var lastRssi = 0
    private var smoothedRssi = 0f
    private val targetHistory = ArrayDeque<Double>(50) // Store ~5-10 seconds of raw RSSI
    private val handler = Handler(Looper.getMainLooper())
    private var lastListUpdate = 0L

    @Volatile
    private var targetEpc = ""

    private data class TagInfo(val recentRssi: ArrayDeque<Double>, var lastSeen: Long, val epc: String) {
        val avgRssi: Double get() = if (recentRssi.isEmpty()) 0.0 else recentRssi.average()
    }

    private val tagMap = LinkedHashMap<String, TagInfo>()

    private fun isTarget(epc: String): Boolean {
        if (targetEpc.isEmpty()) return false
        return epc.contains(targetEpc, ignoreCase = true)
    }

    private val soundTicker = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            // Auto-decay if no hardware updates for target
            if (System.currentTimeMillis() - lastHardwareUpdate > 1000) {
                lastRssi = 0
            }
            
            val signal = smoothedRssi.toInt()
            if (signal > 0) {
                // Calculate local sensitivity
                val maxInHistory = if (targetHistory.isEmpty()) 0.0 else targetHistory.max()
                val minInHistory = if (targetHistory.isEmpty()) 0.0 else targetHistory.min()
                val range = maxInHistory - minInHistory
                
                // Pitch Factor: 0.0 to 1.0 based on how close current is to local max
                val currentRaw = if (targetHistory.isEmpty()) 0.0 else targetHistory.last()
                val factor = if (range > 0.5) {
                    ((currentRaw - minInHistory) / range).coerceIn(0.0, 1.0).toFloat()
                } else {
                    0.5f
                }

                // 1.0 (base) to 2.0 (high pitch)
                val pitch = 1.0f + factor
                
                mainActivity?.playSound(1, pitch)
                
                // Delay also depends on absolute signal strength
                val delay = 1000.0 - (signal * 9.6)
                handler.postDelayed(this, delay.toLong().coerceIn(40, 1000))
            } else {
                handler.postDelayed(this, 200)
            }
        }
    }

    private val graphTicker = object : Runnable {
        override fun run() {
            if (isRadarRunning) {
                binding.radarGraph.addValue(smoothedRssi.toInt())
                // Decay if target not seen
                if (System.currentTimeMillis() - lastHardwareUpdate > 500) {
                    smoothedRssi *= 0.5f // Faster decay
                }
                if (smoothedRssi < 1f) smoothedRssi = 0f
                handler.postDelayed(this, 100) // Faster graph
            }
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            val now = System.currentTimeMillis()
            val toRemove = tagMap.filter { now - it.value.lastSeen > 3000 }.keys
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { tagMap.remove(it) }
                rebuildTagsList()
            }
            handler.postDelayed(this, 500)
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
            binding.toggleRadarPower.isEnabled = connected
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

        binding.btnPowerHigh.setOnClickListener { mainActivity?.saveRadarPower(30) }
        binding.btnPowerMedium.setOnClickListener { mainActivity?.saveRadarPower(25) }
        binding.btnPowerLow.setOnClickListener { mainActivity?.saveRadarPower(20) }

        mainActivity?.let { activity ->
            val savedPower = activity.getRadarPower()
            when (savedPower) {
                30 -> binding.toggleRadarPower.check(R.id.btnPowerHigh)
                25 -> binding.toggleRadarPower.check(R.id.btnPowerMedium)
                20 -> binding.toggleRadarPower.check(R.id.btnPowerLow)
                else -> binding.toggleRadarPower.check(R.id.btnPowerHigh)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRadarRunning) stopRadar()
    }

    private fun startRadar() {
        val epc = binding.editTargetEpc.text.toString().trim()
        if (epc.isEmpty()) {
            mainActivity?.appendLog("Please enter a target EPC")
            return
        }

        isRadarRunning = true
        targetEpc = epc.uppercase()
        binding.btnToggleRadar.setText(R.string.radar_stop)
        binding.editTargetEpc.isEnabled = false
        binding.btnPaste.isEnabled = false
        tagMap.clear()
        targetHistory.clear()
        binding.radarGraph.clear()
        rebuildTagsList()
        lastRssi = 0
        smoothedRssi = 0f

        mainActivity?.let { activity ->
            val power = when (binding.toggleRadarPower.checkedButtonId) {
                R.id.btnPowerHigh -> 30
                R.id.btnPowerMedium -> 25
                R.id.btnPowerLow -> 20
                else -> 30
            }
            activity.saveRadarPower(power)
        }

        handler.post(soundTicker)
        handler.post(graphTicker)
        handler.post(cleanupRunnable)

        mainActivity?.startRadar(epc) { rawRssi, displayRssi, detectedEpc ->
            val now = System.currentTimeMillis()
            val target = isTarget(detectedEpc)
            
            if (target) {
                lastRssi = displayRssi
                val alpha = 0.95f // Much faster reaction
                smoothedRssi = (alpha * displayRssi) + (1 - alpha) * smoothedRssi
                lastHardwareUpdate = now
                targetHistory.addLast(rawRssi)
                if (targetHistory.size > 50) targetHistory.removeFirst()
            }
            
            val existing = tagMap[detectedEpc]
            if (existing != null) {
                existing.recentRssi.addLast(rawRssi)
                if (existing.recentRssi.size > 5) existing.recentRssi.removeFirst()
                existing.lastSeen = now
            } else {
                val dq = ArrayDeque<Double>(6)
                dq.addLast(rawRssi)
                tagMap[detectedEpc] = TagInfo(dq, now, detectedEpc)
            }

            // Throttle UI updates to 10 FPS
            if (now - lastListUpdate > 100) {
                rebuildTagsList()
                lastListUpdate = now
            }
        }
        mainActivity?.appendLog("Searching for tags starting with *$epc*")
    }

    private fun stopRadar() {
        isRadarRunning = false
        handler.removeCallbacks(soundTicker)
        handler.removeCallbacks(graphTicker)
        handler.removeCallbacks(cleanupRunnable)
        binding.btnToggleRadar.setText(R.string.radar_start)
        binding.editTargetEpc.isEnabled = true
        binding.btnPaste.isEnabled = true
        mainActivity?.stopRadar()
        mainActivity?.appendLog("Radar stopped")
    }

    private fun rebuildTagsList() {
        val container = binding.tagsContainer
        container.removeAllViews()

        val sorted = tagMap.entries
            .sortedByDescending { it.value.avgRssi }
            .take(15)

        val context = requireContext()
        val normalColor = ContextCompat.getColor(context, R.color.md_theme_onSurface)
        val targetColor = ContextCompat.getColor(context, R.color.md_theme_error)
        val dimColor = ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)

        sorted.forEachIndexed { index, (epc, info) ->
            val target = isTarget(epc)
            val textColor = if (target) targetColor else normalColor
            
            val displayEpc = if (epc.length > 18) epc.take(18) + "\u2026" else epc

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 6, 8, 6)
            }

            val rankView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(30.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "${index + 1}"
                setTextColor(textColor)
                textSize = 12f
                gravity = Gravity.CENTER
            }

            val epcView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = displayEpc
                setTextColor(textColor)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                if (target) setTypeface(typeface, Typeface.BOLD)
            }

            // High-resolution intensity bar
            val barLayout = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 12.dp, 1f).apply {
                    marginStart = 8.dp
                    marginEnd = 8.dp
                }
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(ContextCompat.getColor(context, R.color.outline))
            }
            
            val intensity = ((80.0 - abs(info.avgRssi)) * 100 / 50).toInt().coerceIn(0, 100)
            val barProgress = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, intensity.toFloat() / 100f)
                setBackgroundColor(if (target) targetColor else ContextCompat.getColor(context, R.color.md_theme_primary))
            }
            val barEmpty = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - intensity).toFloat() / 100f)
            }
            barLayout.addView(barProgress)
            barLayout.addView(barEmpty)

            val rssiView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(70.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                // Always show as negative dBm for consistency
                val displayVal = if (info.avgRssi > 0) -info.avgRssi else info.avgRssi
                text = String.format(Locale.US, "%.1f", displayVal)
                setTextColor(if (target) targetColor else dimColor)
                textSize = 12f
                gravity = Gravity.END
                if (target) setTypeface(typeface, Typeface.BOLD)
            }

            row.addView(rankView)
            row.addView(epcView)
            row.addView(rssiView)
            container.addView(row)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRadarRunning) stopRadar()
        _binding = null
    }
}
