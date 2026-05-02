package com.trackstudio.rfidmanager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "uhf_settings"
        private const val PREF_FREQ_MODE = "freq_mode"
        private const val PREF_POWER = "power"
        private const val DEFAULT_FREQ_MODE = 2 // Europe 865-868 MHz
        private const val DEFAULT_POWER = 10
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SharedViewModel by viewModels()
    
    private var barcodeDecoder: BarcodeDecoder? = null
    private var mReader: RFIDWithUHFUART? = null
    private lateinit var prefs: SharedPreferences
    private val logEntries = LinkedList<String>()
    private val historyEntries = mutableListOf<Pair<String, Boolean>>()

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

            mReader?.init(this)
            val isConnected = mReader?.connectStatus == ConnectionStatus.CONNECTED
            viewModel.setConnectionStatus(isConnected)
            
            if (isConnected) {
                appendLog("Connected")
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
        val freqMode = prefs.getInt(PREF_FREQ_MODE, DEFAULT_FREQ_MODE)
        val power = prefs.getInt(PREF_POWER, DEFAULT_POWER)
        mReader?.let {
            it.setFrequencyMode(freqMode)
            it.setPower(power)
        }
    }

    fun saveFreqMode(mode: Int) {
        prefs.edit().putInt(PREF_FREQ_MODE, mode).apply()
        mReader?.setFrequencyMode(mode)
    }

    fun savePower(power: Int) {
        prefs.edit().putInt(PREF_POWER, power).apply()
        mReader?.setPower(power)
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
            reader.setPower(10)

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

    // --- Fragment Sync ---

    fun updateWriteFragmentUI(fragment: WriteFragment) {
        fragment.appendLog("", "", logEntries)
        historyEntries.forEach { fragment.addHistoryTag(it.first, it.second) }
    }

    fun updateSettingsFragmentUI(fragment: SettingsFragment) {
        val freqMode = prefs.getInt(PREF_FREQ_MODE, DEFAULT_FREQ_MODE)
        val power = prefs.getInt(PREF_POWER, DEFAULT_POWER)
        fragment.updateUI(freqMode, power)
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
            val fragment = navHost.childFragmentManager.fragments.firstOrNull() as? WriteFragment
            fragment?.appendLog(timestamp, message, logEntries)
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
            if (decoder.open(this)) {
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

    fun playSound(id: Int) {
        val soundId = soundMap[id] ?: return
        val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        soundPool?.play(soundId, volume, volume, 1, 0, 1f)
    }
}
