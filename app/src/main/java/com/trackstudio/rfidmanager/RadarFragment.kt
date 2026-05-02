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
import kotlin.math.abs

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
    
    private val allPowers = intArrayOf(30, 24, 19, 15, 12, 10, 8, 7, 6, 5)
    private var windowIndex = 0
    private var cycleIndex = 0
    private var currentDynamicPower = 30
    private val phaseDurationMs = 180L

    @Volatile
    private var targetEpc = ""

    private class TagCycleData(var lastSeen: Long) {
        val rssiMap = mutableMapOf<Int, Double>()
        var lastEvaluatedScore: Float = 0f
        
        fun recordRssi(power: Int, rssi: Double) {
            rssiMap[power] = rssi
        }
        
        fun evaluate() {
            if (rssiMap.isNotEmpty()) {
                val lowestPowerEntry = rssiMap.minByOrNull { it.key }
                if (lowestPowerEntry != null) {
                    val lowestPower = lowestPowerEntry.key
                    val lowestRssi = lowestPowerEntry.value
                    
                    // Приводим сигнал к "Эквивалентной мощности" (как если бы мы читали на 30 дБм)
                    val actualRssi = -abs(lowestRssi) // Гарантируем, что сырой RSSI всегда отрицательный
                    val powerDrop = 30 - lowestPower
                    
                    // Физика отражения: падение мощности передатчика на 1 дБм дает
                    // падение мощности отраженного сигнала тоже на 1 дБм (в линейном режиме)
                    val equivalentRssi = actualRssi + powerDrop
                    
                    // Исключаем неадекватно положительные значения из-за сильного сигнала в упор (макс -10 dBm)
                    lastEvaluatedScore = equivalentRssi.toFloat().coerceAtMost(-10f)
                }
            } else {
                // Если метка пропала, плавно уводим сигнал вниз (в сторону -90 dBm)
                if (lastEvaluatedScore == 0f) lastEvaluatedScore = -90f
                lastEvaluatedScore = (lastEvaluatedScore - 5f).coerceAtLeast(-90f)
            }
        }
        
        fun reset() {
            rssiMap.clear()
        }
    }

    private val tagMap = LinkedHashMap<String, TagCycleData>()

    private fun isTarget(epc: String): Boolean {
        if (targetEpc.isEmpty()) return false
        return epc.contains(targetEpc, ignoreCase = true)
    }

    private val soundTicker = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            
            if (accumulatedScore > -88f && !isPanicRecoveryMode) {
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
                // НЕ рисуем график, если мы в состоянии "паники"
                if (!isPanicRecoveryMode) {
                    val maxP = allPowers[windowIndex]
                    val minP = allPowers[windowIndex + 2]
                    
                    // Синхронизируем медленную среднюю для звука с тем, как она считается в графике
                    if (accumulatedScore == -90f && emaSlowScore == -90f) {
                        emaSlowScore = accumulatedScore
                    } else {
                        emaSlowScore = (0.02f * accumulatedScore) + (0.98f * emaSlowScore)
                    }
                    
                    binding.radarGraph.addValue(accumulatedScore, maxP, minP)
                    
                    if (accumulatedScore > -89f) {
                        binding.tvOverlayScore.visibility = View.VISIBLE
                        binding.tvOverlayScore.text = String.format(Locale.US, "%.1f dBm", accumulatedScore)
                        binding.tvOverlayScore.setTextColor(Color.parseColor("#00E676")) // Всегда зеленый
                    } else {
                        binding.tvOverlayScore.visibility = View.GONE
                    }
                }
                
                // Smoothly interpolate current dBm to target dBm
                accumulatedScore += (targetAccumulatedScore - accumulatedScore) * 0.6f
                
                handler.postDelayed(this, 50) // Fast graph updates for smoothness
            }
        }
    }
    
    // Флаг того, что метка пропала и мы делаем экстренный цикл на диапазоне [30, 24, 19]
    private var isPanicRecoveryMode = false

    private val cycleTicker = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            
            cycleIndex++
            if (cycleIndex >= 3) { // 3 powers in a sliding window
                // End of cycle! Evaluate all tags.
                tagMap.values.forEach { it.evaluate() }
                
                val targetTag = tagMap.entries.find { isTarget(it.key) }?.value
                
                if (targetTag == null || targetTag.rssiMap.isEmpty()) {
                    if (!isPanicRecoveryMode && windowIndex > 0) {
                        // Метка пропала. Замораживаем график и уходим в "Panic Mode".
                        isPanicRecoveryMode = true
                        windowIndex = 0 // Сразу прыгаем на [30, 24, 19]
                        cycleIndex = 0
                        
                        // Делаем вид, что ничего не произошло (оставляем старый targetAccumulatedScore).
                        // Запускаем экстренный опрос. График пока стоит на паузе.
                        currentDynamicPower = allPowers[0]
                        mainActivity?.setDynamicRadarPower(currentDynamicPower)
                        handler.postDelayed(this, phaseDurationMs)
                        return
                    }
                }
                
                // Если мы дошли сюда, значит либо метка найдена, либо мы уже прогнали Panic Mode и её там тоже нет.
                isPanicRecoveryMode = false
                targetAccumulatedScore = targetTag?.lastEvaluatedScore ?: -90f
                
                // Adjust sliding window based on target tag presence
                val oldWindowIndex = windowIndex
                if (targetTag != null && targetTag.rssiMap.isNotEmpty()) {
                    val highPower = allPowers[windowIndex]
                    val midPower = allPowers[windowIndex + 1]
                    val lowPower = allPowers[windowIndex + 2]
                    
                    val seenHigh = targetTag.rssiMap.containsKey(highPower)
                    val seenMid = targetTag.rssiMap.containsKey(midPower)
                    val seenLow = targetTag.rssiMap.containsKey(lowPower)
                    
                    if (seenHigh && seenMid && seenLow) {
                        // Видно на всех трех -> сдвигаем окно вниз (к меньшим мощностям)
                        windowIndex = (windowIndex + 1).coerceAtMost(allPowers.size - 3)
                    } else if (!seenMid) {
                        // Не видно на средней (или вообще пропала) -> сдвигаем окно вверх (к бОльшим мощностям)
                        windowIndex = (windowIndex - 1).coerceAtLeast(0)
                    }
                    // Иначе (видно на высокой и средней, но не на низкой) -> остаемся на текущем уровне
                } else {
                    // Метка вообще не прочиталась в этом цикле -> возвращаемся к бОльшим мощностям
                    windowIndex = (windowIndex - 1).coerceAtLeast(0)
                }

                if (windowIndex > oldWindowIndex) {
                    mainActivity?.appendLog("Auto-Power: Down to [${allPowers[windowIndex]}, ${allPowers[windowIndex+1]}, ${allPowers[windowIndex+2]}]")
                } else if (windowIndex < oldWindowIndex) {
                    mainActivity?.appendLog("Auto-Power: Up to [${allPowers[windowIndex]}, ${allPowers[windowIndex+1]}, ${allPowers[windowIndex+2]}]")
                }
                
                tagMap.values.forEach { it.reset() }
                cycleIndex = 0
            }
            
            currentDynamicPower = allPowers[windowIndex + cycleIndex]
            mainActivity?.setDynamicRadarPower(currentDynamicPower)
            
            handler.postDelayed(this, phaseDurationMs)
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!isRadarRunning) return
            val now = System.currentTimeMillis()
            val toRemove = tagMap.filter { now - it.value.lastSeen > 2000 }.keys
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { tagMap.remove(it) }
            }
            handler.postDelayed(this, 1000)
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
        binding.radarGraph.clear()
        binding.tvOverlayScore.visibility = View.GONE
        accumulatedScore = -90f
        targetAccumulatedScore = -90f
        emaSlowScore = -90f
        windowIndex = 0
        cycleIndex = 0

        currentDynamicPower = allPowers[windowIndex]
        mainActivity?.setDynamicRadarPower(currentDynamicPower)

        handler.post(soundTicker)
        handler.post(graphTicker)
        handler.post(cleanupRunnable)
        handler.postDelayed(cycleTicker, phaseDurationMs) // Start cycle ticker

        mainActivity?.startRadar(epc) { rawRssi, _, detectedEpc ->
            val now = System.currentTimeMillis()
            val info = tagMap.getOrPut(detectedEpc) { TagCycleData(now) }
            info.lastSeen = now
            info.recordRssi(currentDynamicPower, rawRssi)
        }
        mainActivity?.appendLog("Searching for tags starting with *$epc*")
    }

    private fun stopRadar() {
        isRadarRunning = false
        handler.removeCallbacks(soundTicker)
        handler.removeCallbacks(graphTicker)
        handler.removeCallbacks(cleanupRunnable)
        handler.removeCallbacks(cycleTicker)
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
