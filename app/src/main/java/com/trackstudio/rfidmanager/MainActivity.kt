package com.trackstudio.rfidmanager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.trackstudio.rfidmanager.databinding.ActivityMainBinding
import com.rscja.barcode.BarcodeDecoder
import com.rscja.barcode.BarcodeFactory
import com.rscja.barcode.BarcodeUtility
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.interfaces.IUHF
import com.rscja.deviceapi.interfaces.IUHFLocationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "uhf_settings"
        private const val PREF_FREQ_MODE = "freq_mode_id" // Use ID instead of index
        private const val PREF_WRITE_POWER = "write_power"
        private const val PREF_RADAR_POWER = "radar_power"
        private const val DEFAULT_WRITE_POWER = 10
        private const val DEFAULT_RADAR_POWER = 30
        
        // Frequency Mode IDs from Vendor SDK
        const val MODE_CHINA_840_845 = 0x01
        const val MODE_CHINA_920_925 = 0x02
        const val MODE_EUROPE_865_868 = 0x04
        const val MODE_USA_902_928 = 0x08
        const val MODE_KOREA = 0x16
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SharedViewModel by viewModels()
    
    private var barcodeDecoder: BarcodeDecoder? = null
    private var mReader: RFIDWithUHFUART? = null
    private lateinit var prefs: SharedPreferences
    private val logEntries = LinkedList<String>()
    private val historyEntries = mutableListOf<Pair<String, Boolean>>()
    private var originalRadarPower: Int? = null
    private val uhfMutex = Mutex()

    // Sound
    private val soundMap = HashMap<Int, Int>()
    private var soundPool: SoundPool? = null
    private lateinit var am: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation?.setupWithNavController(navController)

        initSound()
        setupStatusObserver()
        initHardware()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Side trigger is not intercepted; SDK/location mode owns UHF work.
        // Let system/vendor key handling continue normally.
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    private fun setupStatusObserver() {
        viewModel.statusText.observe(this) { status ->
            binding.tvGlobalStatus?.text = status
        }
        viewModel.isConnected.observe(this) { connected ->
            val color = if (connected) R.color.md_theme_primary else R.color.md_theme_error
            binding.ivStatusDot?.setColorFilter(ContextCompat.getColor(this, color))
        }
    }

    private fun initHardware() {
        try {
            barcodeDecoder = BarcodeFactory.getInstance().barcodeDecoder
            installBarcodeDecoderCallback()
            appendLog("Initializing UHF module...")
            lifecycleScope.launch(Dispatchers.IO) {
                completeUhfInitialization("startup")
            }
        } catch (ex: Exception) {
            appendLog("Init error: $ex")
        }
    }

    override fun onDestroy() {
        CoroutineScope(Dispatchers.IO).launch {
            uhfMutex.withLock {
                mReader?.let { reader ->
                    runCatching {
                        if (isLocationRunning.get()) {
                            reader.stopLocation()
                            isLocationRunning.set(false)
                        }
                        reader.stopInventory()
                        reader.free()
                    }.onFailure { appendLog("UHF destroy cleanup error: $it") }
                    mReader = null
                }
            }
        }
        releaseSoundPool()
        barcodeDecoder?.close()
        super.onDestroy()
    }

    private suspend fun applySavedSettings() {
        uhfMutex.withLock {
            if (prefs.contains(PREF_FREQ_MODE)) {
                val freqMode = prefs.getInt(PREF_FREQ_MODE, -1)
                if (freqMode != -1) {
                    mReader?.setFrequencyMode(freqMode)
                }
            }
        }
    }

    fun saveFreqMode(modeId: Int) {
        prefs.edit().putInt(PREF_FREQ_MODE, modeId).apply()
        lifecycleScope.launch(Dispatchers.IO) {
            uhfMutex.withLock {
                mReader?.setFrequencyMode(modeId)
            }
        }
    }

    fun getWritePower() = prefs.getInt(PREF_WRITE_POWER, DEFAULT_WRITE_POWER)
    fun saveWritePower(power: Int) {
        prefs.edit().putInt(PREF_WRITE_POWER, power).apply()
    }

    fun getRadarPower() = prefs.getInt(PREF_RADAR_POWER, DEFAULT_RADAR_POWER)
    fun saveRadarPower(power: Int) {
        prefs.edit().putInt(PREF_RADAR_POWER, power).apply()
        setDynamicRadarPower(power)
    }

    fun openBarcode() {
        if (barcodeDecoder?.open(this) == true) {
            BarcodeUtility.getInstance().enablePlayFailureSound(this, true)
            appendLog("Barcode scanner enabled")
        }
    }

    fun closeBarcode() {
        barcodeDecoder?.close()
        appendLog("Barcode scanner disabled (Radar mode)")
    }

    fun reconnectUhf() {
        appendLog("Reconnecting UHF module...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                completeUhfInitialization("reconnect")
            } catch (ex: Exception) {
                appendLog("Reconnect error: $ex")
            }
        }
    }

    private suspend fun completeUhfInitialization(reason: String) {
        val result = uhfMutex.withLock { initializeUhfLocked(reason) }
        viewModel.setConnectionStatus(result.connected)

        if (result.connected) {
            val deviceFreq = uhfMutex.withLock { mReader?.frequencyMode ?: -1 }
            appendLog("UHF $reason final success: initPath=${result.attempts.lastOrNull()?.initPath}, connectStatus=${result.attempts.lastOrNull()?.connectStatus}, isPowerOn=${result.attempts.lastOrNull()?.isPowerOn}, errCode=${result.attempts.lastOrNull()?.errCode}")
            appendLog("Connected. Hardware region: 0x${Integer.toHexString(deviceFreq)}")
            if (!prefs.contains(PREF_FREQ_MODE) && deviceFreq != -1) {
                prefs.edit().putInt(PREF_FREQ_MODE, deviceFreq).apply()
            }
            applySavedSettings()
            withContext(Dispatchers.Main) { syncSettingsToFragment() }
        } else {
            val lastAttempt = result.attempts.lastOrNull()
            appendLog("UHF $reason final failure: attempts=${result.attempts.size}, initPath=${lastAttempt?.initPath}, connectStatus=${lastAttempt?.connectStatus}, isPowerOn=${lastAttempt?.isPowerOn}, errCode=${lastAttempt?.errCode}")
        }
    }

    private suspend fun initializeUhfLocked(reason: String): UhfInitResult {
        val previousReader = mReader?.let { ChainwayUhfReaderAdapter(it, this@MainActivity, ChainwayUhfInitStrategy.SYSTEM_POWER_CONTEXT_INIT) }
        val controller = UhfConnectionController(
            scannerCleaner = ChainwayScannerCleaner(this@MainActivity),
            readerFactory = ChainwayUhfReaderFactory(this@MainActivity),
            logHandler = { appendUhfAttemptLog(reason, it) }
        )
        val result = controller.initialize(previousReader)
        mReader = (result.reader as? ChainwayUhfReaderAdapter)?.reader
        return result
    }

    private fun appendUhfAttemptLog(reason: String, attemptLog: UhfInitAttemptLog) {
        appendLog(
                "UHF $reason attempt ${attemptLog.attempt}: " +
                "isUhfWorking=${attemptLog.isUhfWorking}, " +
                "scannerEnable=${attemptLog.scannerEnableResult}, " +
                "scannerStop=${attemptLog.scannerStopResult}, " +
                "scannerDisable=${attemptLog.scannerDisableResult}, " +
                "oldFree=${attemptLog.previousReaderFreeResult ?: "not-needed"}, " +
                "initPath=${attemptLog.initPath}, " +
                "initResult=${attemptLog.initResult}, " +
                "connectStatus=${attemptLog.connectStatus}, " +
                "isPowerOn=${attemptLog.isPowerOn}, " +
                "errCode=${attemptLog.errCode}, " +
                "result=${attemptLog.result}, " +
                "nextRetryDelayMs=${attemptLog.nextRetryDelayMs}"
        )
        if (attemptLog.cleanupExceptions.isNotEmpty()) {
            appendLog("UHF $reason attempt ${attemptLog.attempt} non-fatal cleanup exceptions: ${attemptLog.cleanupExceptions.joinToString()}")
        }
    }

    fun performWriteRFID(code: String) {
        val initialTarget = try {
            EpcTargetNormalizer.normalize(code)
        } catch (ex: IllegalArgumentException) {
            appendLog("Write rejected: ${ex.message}")
            addHistoryTag(code, false)
            playSound(2)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = uhfMutex.withLock {
                val reader = mReader ?: return@launch
                var epcSize = 8
                val originalPower = reader.power
                val targetWritePower = getWritePower()
                reader.setPower(targetWritePower)

                try {
                    var currentData = reader.readData("00000000", IUHF.Bank_EPC, 2, 8)
                    if (currentData == null) {
                        currentData = reader.readData("00000000", IUHF.Bank_EPC, 2, 6)
                        if (currentData != null) epcSize = 6
                    }

                    val target = try {
                        EpcTargetNormalizer.normalize(initialTarget.label, epcSize)
                    } catch (ex: IllegalArgumentException) {
                        return@withLock WriteOutcome("Write rejected: ${ex.message}", initialTarget.label, false)
                    }

                    val result = reader.writeData("00000000", IUHF.Bank_EPC, 2, epcSize, target.paddedHex)
                    if (!result) {
                        WriteOutcome("Write error: ${ErrorCodeManager.getMessage(reader.errCode)}", target.label, false)
                    } else {
                        val verifyData = reader.readData("00000000", IUHF.Bank_EPC, 2, epcSize)
                        if (verifyData != null && verifyData.startsWith(target.label, ignoreCase = true)) {
                            WriteOutcome("SUCCESS ${target.label.lowercase()}", target.label, true)
                        } else {
                            WriteOutcome("Verification failed", target.label, false)
                        }
                    }
                } finally {
                    reader.setPower(originalPower)
                }
            }

            withContext(Dispatchers.Main) {
                appendLog(outcome.message)
                addHistoryTag(outcome.label, outcome.success)
                playSound(if (outcome.success) 1 else 2)
            }
        }
    }

    private data class WriteOutcome(
        val message: String,
        val label: String,
        val success: Boolean
    )

    // --- Radar ---
    private val isLocationRunning = AtomicBoolean(false)

    /** Updates idle locate power without interrupting an active Chainway location session. */
    fun setDynamicRadarPower(power: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            uhfMutex.withLock {
                val reader = mReader ?: return@withLock
                if (!isLocationRunning.get()) {
                    reader.setPower(power)
                }
            }
        }
    }

    fun startRadar(
        targetMask: String,
        onStartResult: (Boolean) -> Unit,
        onLocationValue: (Int, Boolean) -> Unit
    ) {
        val target = try {
            EpcTargetNormalizer.normalize(targetMask)
        } catch (ex: IllegalArgumentException) {
            appendLog("Radar rejected: ${ex.message}")
            onStartResult(false)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val started = uhfMutex.withLock {
                val reader = mReader ?: return@withLock false
                val targetRadarPower = getRadarPower()
                originalRadarPower = reader.power
                reader.setPower(targetRadarPower)

                val locationStarted = reader.startLocation(
                    this@MainActivity,
                    target.label,
                    IUHF.Bank_EPC,
                    32,
                    object : IUHFLocationCallback {
                        override fun getLocationValue(value: Int, found: Boolean) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                onLocationValue(value.coerceIn(0, 100), found)
                            }
                        }
                    }
                )

                if (locationStarted) {
                    isLocationRunning.set(true)
                } else {
                    val savedPower = originalRadarPower
                    originalRadarPower = null
                    savedPower?.let { reader.setPower(it) }
                }

                locationStarted
            }

            if (started) {
                withContext(Dispatchers.Main) {
                    appendLog("Radar: Locate started for *${target.label}*")
                    onStartResult(true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    appendLog("Radar Error: Failed to start Locate mode")
                    onStartResult(false)
                }
            }
        }
    }

    fun stopRadar() {
        lifecycleScope.launch(Dispatchers.IO) {
            uhfMutex.withLock {
                val reader = mReader ?: return@withLock
                isLocationRunning.set(false)
                reader.stopLocation()
                originalRadarPower?.let { reader.setPower(it) }
                originalRadarPower = null
            }
        }
    }

    // --- Fragment Sync ---

    fun updateWriteFragmentUI(fragment: WriteFragment) {
        historyEntries.forEach { fragment.addHistoryTag(it.first, it.second) }
    }

    fun updateLogFragmentUI(fragment: LogFragment) {
        fragment.setLogs(logEntries)
    }

    fun updateSettingsFragmentUI(fragment: SettingsFragment) {
        val freqMode = prefs.getInt(PREF_FREQ_MODE, -1)
        fragment.updateUI(freqMode)
    }

    private fun syncSettingsToFragment() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val fragment = navHost.childFragmentManager.fragments.firstOrNull() as? SettingsFragment
        fragment?.let { updateSettingsFragmentUI(it) }
    }

    fun appendLog(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logEntries.addFirst("$timestamp - $message")
            if (logEntries.size > 30) logEntries.removeAt(logEntries.size - 1)
            
            val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val fragment = navHost.childFragmentManager.fragments.firstOrNull() as? LogFragment
            fragment?.setLogs(logEntries)
        }
    }

    private fun addHistoryTag(code: String, success: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            historyEntries.add(0, code to success)
            if (historyEntries.size > 20) historyEntries.removeAt(historyEntries.lastIndex)
            
            val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val fragment = navHost.childFragmentManager.fragments.firstOrNull() as? WriteFragment
            fragment?.addHistoryTag(code, success)
        }
    }

    private fun installBarcodeDecoderCallback() {
        barcodeDecoder?.let { decoder ->
            // Don't open here, fragments will manage lifecycle
            decoder.setDecodeCallback { barcodeEntity ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (barcodeEntity.resultCode == BarcodeDecoder.DECODE_SUCCESS) {
                        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                        val fragment = navHost.childFragmentManager.fragments.firstOrNull() as? WriteFragment
                        fragment?.setBarcodeData(barcodeEntity.barcodeData)
                        performWriteRFID(barcodeEntity.barcodeData)
                    }
                }
            }
        }
    }

    // --- Utils ---
    private fun initSound() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        soundPool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(audioAttributes).build().apply {
            soundMap[1] = load(this@MainActivity, R.raw.barcodebeep, 1)
            soundMap[2] = load(this@MainActivity, R.raw.serror, 1)
        }
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun releaseSoundPool() {
        soundPool?.release()
        soundPool = null
    }

    fun playSound(id: Int, rate: Float = 1f) {
        val soundId = soundMap[id] ?: return
        val pool = soundPool ?: return
        val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            pool.play(soundId, volume, volume, 1, 0, rate.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
