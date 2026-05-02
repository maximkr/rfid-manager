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
import com.rscja.deviceapi.interfaces.ConnectionStatus
import com.rscja.deviceapi.interfaces.IUHF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

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
        // Убрали отключение курка в режиме радара. 
        // Курок больше не перехватывается для понижения мощности.
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
            mReader = RFIDWithUHFUART.getInstance()

            installBarcodeDecoderCallback()

            // init() without context is for all modules, consistent with vendor demo
            mReader?.init()
            val isConnected = mReader?.connectStatus == ConnectionStatus.CONNECTED
            viewModel.setConnectionStatus(isConnected)
            
            if (isConnected) {
                val deviceFreq = mReader?.frequencyMode ?: -1
                appendLog("Connected. Hardware region: 0x${Integer.toHexString(deviceFreq)}")
                
                if (!prefs.contains(PREF_FREQ_MODE) && deviceFreq != -1) {
                    prefs.edit().putInt(PREF_FREQ_MODE, deviceFreq).apply()
                }
                
                applySavedSettings()
            } else {
                appendLog("Cannot connect Chainway UHF module")
            }
        } catch (ex: Exception) {
            appendLog("Init error: $ex")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSoundPool()
        mReader?.free()
        barcodeDecoder?.close()
    }

    private fun applySavedSettings() {
        if (prefs.contains(PREF_FREQ_MODE)) {
            val freqMode = prefs.getInt(PREF_FREQ_MODE, -1)
            if (freqMode != -1) {
                mReader?.setFrequencyMode(freqMode)
            }
        }
    }

    fun saveFreqMode(modeId: Int) {
        prefs.edit().putInt(PREF_FREQ_MODE, modeId).apply()
        mReader?.setFrequencyMode(modeId)
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

    fun setDynamicRadarPower(power: Int) {
        mReader?.let {
            if (isInventoryRunning) {
                it.stopInventory()
                it.setPower(power)
                it.startInventoryTag()
            } else {
                it.setPower(power)
            }
        }
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
                mReader?.free()
                mReader = RFIDWithUHFUART.getInstance()
                val reader = mReader ?: return@launch
                reader.init(this@MainActivity)

                val connected = reader.connectStatus == ConnectionStatus.CONNECTED
                viewModel.setConnectionStatus(connected)
                
                withContext(Dispatchers.Main) {
                    if (connected) {
                        appendLog("Reconnected successfully")
                        applySavedSettings()
                        syncSettingsToFragment()
                    } else {
                        appendLog("Reconnect failed")
                    }
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("Reconnect error: $ex")
                }
            }
        }
    }

    fun performWriteRFID(code: String) {
        lifecycleScope.launch(Dispatchers.IO) {
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

                if (code.length > 4 * epcSize) {
                    appendLog("Error: Code too long")
                    addHistoryTag(code, false)
                    playSound(2)
                    return@launch
                }

                val format = "%-${4 * epcSize}s"
                val dataToWrite = String.format(format, code).replace(' ', '0')
                val result = reader.writeData("00000000", IUHF.Bank_EPC, 2, epcSize, dataToWrite)

                withContext(Dispatchers.Main) {
                    if (!result) {
                        appendLog("Write error: ${ErrorCodeManager.getMessage(reader.errCode)}")
                        addHistoryTag(code, false)
                        playSound(2)
                    } else {
                        val verifyData = reader.readData("00000000", IUHF.Bank_EPC, 2, epcSize)
                        if (verifyData != null && verifyData.startsWith(code, ignoreCase = true)) {
                            appendLog("SUCCESS ${code.lowercase()}")
                            addHistoryTag(code, true)
                            playSound(1)
                        } else {
                            appendLog("Verification failed")
                            addHistoryTag(code, false)
                            playSound(2)
                        }
                    }
                }
            } finally {
                reader.setPower(originalPower)
            }
        }
    }

    // --- Radar ---
    private var isInventoryRunning = false

    fun startRadar(targetMask: String, onValueReceived: (Double, Int, String) -> Unit) {
        val reader = mReader ?: return
        val targetRadarPower = getRadarPower()
        reader.setPower(targetRadarPower)
        
        reader.stopInventory()
        Thread.sleep(100)
        reader.setFilter(IUHF.Bank_EPC, 0, 0, "")
        reader.setEPCMode()
        
        if (reader.startInventoryTag()) {
            isInventoryRunning = true
            val mask = targetMask.uppercase()
            appendLog("Radar: Scan started for *$mask*")

            lifecycleScope.launch(Dispatchers.IO) {
                while (isInventoryRunning) {
                    val info = reader.readTagFromBuffer()
                    if (info != null) {
                        val epc = info.epc ?: ""
                        if (epc.isEmpty()) continue

                        val rssiStr = info.rssi ?: "0"
                        var rssiRaw = try {
                            rssiStr.replace(Regex("[^0-9.-]"), "").toDouble()
                        } catch (e: Exception) { 0.0 }
                        
                        // Auto-detect centi-dBm (like -5123 instead of -51.23)
                        if (abs(rssiRaw) > 200.0) {
                            rssiRaw /= 100.0
                        }
                        
                        val cleanRssi = abs(rssiRaw)
                        // User range: -30 (best) to -80 (worst)
                        val displayRssi = when {
                            cleanRssi != 0.0 && cleanRssi <= 30.0 -> 100
                            cleanRssi >= 80.0 || cleanRssi == 0.0 -> 0
                            else -> ((80.0 - cleanRssi) * 100 / 50).toInt().coerceIn(0, 100)
                        }
                        
                        android.util.Log.d("RFID_SCAN", "Detected: $epc RSSI: $rssiRaw")

                        withContext(Dispatchers.Main) {
                            onValueReceived(rssiRaw, displayRssi, epc)
                        }
                    }
else {
                        Thread.sleep(10)
                    }
                }
            }
        } else {
            appendLog("Radar Error: Failed to start Scan mode")
        }
    }

    fun stopRadar() {
        isInventoryRunning = false
        originalRadarPower = null
        mReader?.stopInventory()
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
