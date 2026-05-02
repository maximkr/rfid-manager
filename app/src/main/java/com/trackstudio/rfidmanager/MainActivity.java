package com.trackstudio.rfidmanager;

import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;

import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.barcode.BarcodeUtility;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.BarcodeEntity;
import com.rscja.deviceapi.interfaces.ConnectionStatus;
import com.rscja.deviceapi.interfaces.IUHF;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "uhf_settings";
    private static final String PREF_FREQ_MODE = "freq_mode";
    private static final String PREF_POWER = "power";
    private static final int DEFAULT_FREQ_MODE = 2; // Europe 865-868 MHz
    private static final int DEFAULT_POWER = 10;

    private EditText editCode;
    private TextView tvLog;
    private TextView tvStatus;
    private TextView tvPowerValue;
    private ImageView settingsChevron;
    private View settingsBody;
    private AutoCompleteTextView spinnerFreqMode;
    private Slider sliderPower;
    private LinearLayout historyContainer;
    private final LinkedList<String> logEntries = new LinkedList<>();

    private BarcodeDecoder barcodeDecoder;
    private RFIDWithUHFUART mReader;
    private SharedPreferences prefs;
    private boolean settingsExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        editCode = findViewById(R.id.editCode);
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        tvPowerValue = findViewById(R.id.tvPowerValue);
        settingsChevron = findViewById(R.id.settingsChevron);
        settingsBody = findViewById(R.id.settingsBody);
        spinnerFreqMode = findViewById(R.id.spinnerFreqMode);
        sliderPower = findViewById(R.id.sliderPower);
        historyContainer = findViewById(R.id.historyContainer);

        initSound();
        setupKeyboard();
        setupSettingsToggle();
        setupFrequencyDropdown();
        setupPowerSlider();

        findViewById(R.id.btnWriteRFID).setOnClickListener(v -> performWriteRFID());
        findViewById(R.id.btnReconnect).setOnClickListener(v -> reconnectUhf());

        try {
            barcodeDecoder = BarcodeFactory.getInstance().getBarcodeDecoder();
            mReader = RFIDWithUHFUART.getInstance();

            editCode.setText(R.string.example_code);

            // init barcode scanner
            installBarcodeDecoderCallback();

            // init uhf module
            mReader.init(this);
            if (mReader.getConnectStatus() == ConnectionStatus.CONNECTED) {
                appendLog("Connected");
                applySavedSettings();
                updateSettingsFromReader();
            } else {
                appendLog("Cannot connect Chainway UHF module");
            }

        } catch (Exception ex) {
            appendLog("Init error: " + ex);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseSoundPool();
        if (mReader != null) {
            mReader.free();
        }
        if (barcodeDecoder != null) {
            barcodeDecoder.close();
        }
    }

    // --- Saved settings ---

    private void applySavedSettings() {
        int freqMode = prefs.getInt(PREF_FREQ_MODE, DEFAULT_FREQ_MODE);
        int power = prefs.getInt(PREF_POWER, DEFAULT_POWER);

        mReader.setFrequencyMode(freqMode);
        mReader.setPower(power);
        appendLog("Freq: " + freqMode + ", Power: " + power + " dBm");
    }

    private void saveFreqMode(int mode) {
        prefs.edit().putInt(PREF_FREQ_MODE, mode).apply();
    }

    private void savePower(int power) {
        prefs.edit().putInt(PREF_POWER, power).apply();
    }

    // --- Keyboard fix for Chainway devices ---

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        editCode.post(() -> {
            editCode.requestFocus();
            imm.showSoftInput(editCode, 0);
        });
    }

    private void setupKeyboard() {
        editCode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboard();
        });
        editCode.setOnClickListener(v -> showKeyboard());
    }

    // --- Settings panel ---

    private void setupSettingsToggle() {
        findViewById(R.id.settingsHeader).setOnClickListener(v -> {
            settingsExpanded = !settingsExpanded;
            settingsBody.setVisibility(settingsExpanded ? View.VISIBLE : View.GONE);
            settingsChevron.animate().rotation(settingsExpanded ? 180f : 0f).setDuration(200).start();
        });
    }

    private void setupFrequencyDropdown() {
        String[] freqModes = getResources().getStringArray(R.array.frequency_modes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, freqModes);
        spinnerFreqMode.setAdapter(adapter);
        spinnerFreqMode.setOnItemClickListener((parent, view, position, id) -> {
            if (mReader != null) {
                boolean ok = mReader.setFrequencyMode(position);
                appendLog("Set freq mode: " + freqModes[position] + (ok ? " OK" : " FAILED"));
                if (ok) {
                    saveFreqMode(position);
                }
            }
        });
    }

    private void setupPowerSlider() {
        sliderPower.addOnChangeListener((slider, value, fromUser) -> {
            tvPowerValue.setText(getString(R.string.unit_dbm, (int) value));
            if (fromUser && mReader != null) {
                boolean ok = mReader.setPower((int) value);
                appendLog("Set power: " + (int) value + " dBm" + (ok ? "" : " FAILED"));
                if (ok) {
                    savePower((int) value);
                }
            }
        });
    }

    private void updateSettingsFromReader() {
        if (mReader == null) return;

        tvStatus.setText("Connected");
        tvStatus.setTextColor(getColor(R.color.md_theme_primary));

        // sync frequency dropdown
        int freqMode = mReader.getFrequencyMode();
        String[] freqModes = getResources().getStringArray(R.array.frequency_modes);
        if (freqMode >= 0 && freqMode < freqModes.length) {
            spinnerFreqMode.setText(freqModes[freqMode], false);
        } else {
            spinnerFreqMode.setText("Unknown (" + freqMode + ")", false);
        }

        // sync power slider
        int power = mReader.getPower();
        if (power >= 5 && power <= 30) {
            sliderPower.setValue(power);
        }
        tvPowerValue.setText(getString(R.string.unit_dbm, power));
    }

    // --- Reconnect ---

    private void reconnectUhf() {
        View btnReconnect = findViewById(R.id.btnReconnect);
        btnReconnect.setEnabled(false);
        appendLog("Reconnecting UHF module...");

        new Thread(() -> {
            try {
                if (mReader != null) {
                    mReader.free();
                }
                mReader = RFIDWithUHFUART.getInstance();
                mReader.init(MainActivity.this);

                runOnUiThread(() -> {
                    if (mReader.getConnectStatus() == ConnectionStatus.CONNECTED) {
                        appendLog("Reconnected successfully");
                        applySavedSettings();
                        updateSettingsFromReader();
                    } else {
                        appendLog("Reconnect failed");
                        tvStatus.setText("Disconnected");
                        tvStatus.setTextColor(getColor(R.color.md_theme_error));
                    }
                    btnReconnect.setEnabled(true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    appendLog("Reconnect error: " + ex);
                    btnReconnect.setEnabled(true);
                });
            }
        }).start();
    }

    // --- RFID write ---

    private void performWriteRFID() {
        String code = editCode.getText().toString().trim();
        if (code.isEmpty()) {
            appendLog("Code is empty");
            playSound(2);
            return;
        }

        // Validate hex format
        if (!code.matches("^[0-9a-fA-F]+$")) {
            appendLog("Error: \"" + code + "\" is not a valid hex string");
            appendLog("RFID tags only support hex (0-9, A-F)");
            addHistoryTag(code, false);
            playSound(2);
            return;
        }

        new Thread(() -> {
            // default epc size
            int epcSize = 8;
            // remember power
            int power = mReader.getPower();
            mReader.setPower(10); // low range

            try {
                // Guess epc size
                // try to read 8 first
                String currentData = mReader.readData("00000000", IUHF.Bank_EPC, 2, 8);
                if (currentData == null) {
                    // if error - try to read 6
                    currentData = mReader.readData("00000000", IUHF.Bank_EPC, 2, 6);
                    // if ok - fix epcSize
                    if (currentData != null) {
                        epcSize = 6;
                        appendLog("EPC size = 6 words");
                    }
                }

                if (currentData != null) {
                    appendLog("Current RFID tag data (before write): " + currentData);
                } else {
                    String errMessage = ErrorCodeManager.getMessage(mReader.getErrCode());
                    appendLog("Read error: " + errMessage);
                }

                // Check if code fits in detected EPC size
                if (code.length() > 4 * epcSize) {
                    appendLog("Error: Code too long (" + code.length() + " hex chars)");
                    appendLog("Maximum allowed for this tag: " + (4 * epcSize));
                    addHistoryTag(code, false);
                    playSound(2);
                    return;
                }

                // need to write full data
                String format = "%-" + 4 * epcSize + "s"; // %-32s for 8 words, %-24s for 6 words
                String dataToWrite = String.format(format, code).replace(' ', '0');
                boolean result = mReader.writeData("00000000", IUHF.Bank_EPC, 2, epcSize, dataToWrite);
                if (!result) {
                    appendLog("Write error: " + ErrorCodeManager.getMessage(mReader.getErrCode()));
                    addHistoryTag(code, false);
                    playSound(2);
                } else {
                    // Read to re-check
                    String data = mReader.readData("00000000", IUHF.Bank_EPC, 2, epcSize);

                    // Compare
                    if (data != null && data.toLowerCase().startsWith(code.toLowerCase())) {
                        appendLog("SUCCESS " + code.toLowerCase());
                        addHistoryTag(code, true);
                        playSound(1);
                    } else {
                        appendLog("We wrote " + dataToWrite + " but read " + data + " instead");
                        addHistoryTag(code, false);
                        playSound(2);
                    }
                }
            } finally {
                // restore power
                mReader.setPower(power);
            }
        }).start();
    }

    // --- Logging ---

    public void appendLog(String message) {
        runOnUiThread(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logEntries.addFirst(timestamp + " - " + message);
            if (logEntries.size() > 30) {
                logEntries.removeLast();
            }

            StringBuilder sb = new StringBuilder();
            for (String entry : logEntries) {
                sb.append(entry).append("\n");
            }
            tvLog.setText(sb.toString());

            ScrollView scrollView = findViewById(R.id.log_scroll_view);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_UP));
        });
    }

    private void addHistoryTag(String code, boolean success) {
        runOnUiThread(() -> {
            View tagView = getLayoutInflater().inflate(R.layout.history_tag, historyContainer, false);
            TextView tv = tagView.findViewById(R.id.tagText);
            MaterialCardView card = (MaterialCardView) tagView;

            tv.setText(code);
            int colorRes = success ? R.color.md_theme_primary : R.color.md_theme_error;
            card.setCardBackgroundColor(ContextCompat.getColor(this, colorRes));

            // Add to the beginning (left side)
            historyContainer.addView(tagView, 0);

            // Auto-scroll to start (left)
            HorizontalScrollView scroll = findViewById(R.id.historyScroll);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_LEFT));
        });
    }

    // --- Barcode scanner ---

    private void installBarcodeDecoderCallback() {
        if (barcodeDecoder.open(this)) {
            BarcodeUtility.getInstance().enablePlayFailureSound(this, true);
            barcodeDecoder.setDecodeCallback((BarcodeEntity barcodeEntity) -> {
                runOnUiThread(() -> {
                    if (barcodeEntity.getResultCode() == BarcodeDecoder.DECODE_SUCCESS) {
                        editCode.setText(barcodeEntity.getBarcodeData());
                        appendLog("Barcode: " + barcodeEntity.getBarcodeData());
                        performWriteRFID();
                    } else {
                        editCode.setText("");
                        appendLog("Error: " + barcodeEntity.getErrCode());
                    }
                });
            });
        } else {
            appendLog("Cannot connect Chainway barcode scanner");
        }
    }

    // --- Sound ---

    private final HashMap<Integer, Integer> soundMap = new HashMap<>();
    private SoundPool soundPool;
    private float volumnRatio;
    private AudioManager am;

    private void initSound() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build();
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.serror, 1));
        am = (AudioManager) this.getSystemService(AUDIO_SERVICE);
    }

    private void releaseSoundPool() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    /**
     * Play notification sound
     *
     * @param id 1 = success, 2 = error
     */
    public void playSound(int id) {
        Integer soundId = soundMap.get(id);
        if (soundId == null || soundPool == null) {
            return;
        }
        float audioMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float audioCurrentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumnRatio = audioCurrentVolume / audioMaxVolume;
        try {
            soundPool.play(soundId, volumnRatio, volumnRatio, 1, 0, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
