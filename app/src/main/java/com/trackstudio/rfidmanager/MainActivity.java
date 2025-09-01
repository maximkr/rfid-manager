package com.trackstudio.rfidmanager;

import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;


import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.barcode.BarcodeUtility;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.BarcodeEntity;
import com.rscja.deviceapi.interfaces.ConnectionStatus;
import com.rscja.deviceapi.interfaces.IUHF;

public class MainActivity extends AppCompatActivity {

    private EditText editCode;
    private Button btnWriteRFID;
    private TextView tvLog;

    BarcodeDecoder barcodeDecoder = BarcodeFactory.getInstance().getBarcodeDecoder();
    RFIDWithUHFUART mReader;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editCode = findViewById(R.id.editCode);
        btnWriteRFID = findViewById(R.id.btnWriteRFID);
        tvLog = findViewById(R.id.tvLog);
        initSound();
        btnWriteRFID.setOnClickListener(v -> performWriteRFID());

        try {
            mReader = RFIDWithUHFUART.getInstance();
            if (mReader.getConnectStatus() != ConnectionStatus.CONNECTED) {
                // init barcode scanner
                installBarcodeDecoderCallback();

                // init uhf module
                mReader.init(this);
                mReader.setFrequencyMode(0x37); // Russia
                appendLog("Connect status: " + mReader.getConnectStatus());
                appendLog("Freq mode: " + mReader.getFrequencyMode());
                appendLog("Power: " + mReader.getPower());
                editCode.setText("e0000001");
            } else {
                tvLog.append("Cannot connect Chainway UHF module");
            }

        } catch (Exception ex) {
            tvLog.append(ex.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseSoundPool();
        if (mReader != null) {
            mReader.free();
        }
        barcodeDecoder.close();
    }

    private void performWriteRFID() {
        // remember power
        int power = mReader.getPower();
        mReader.setPower(10); //low range

        // write data
        String code = editCode.getText().toString();
        if (code.isEmpty()) {
            appendLog("Code is empty");
            playSound(2);
        } else {
            String currentData = mReader.readData("00000000", IUHF.Bank_EPC, 2, 8);
            if (currentData != null) {
                appendLog("Current RFID tag data (before write): " + currentData);
            }

            // need to write full data
            String dataToWrite = String.format("%-32s", code).replace(' ', '0');
            boolean result = mReader.writeData("00000000",IUHF.Bank_EPC,2, 8, dataToWrite); // write more data to clear it
            if (!result) {
                appendLog("Write error: " + ErrorCodeManager.getMessage(mReader.getErrCode()));
                playSound(2);
            } else  {
                // lock
//                ArrayList<Integer> lockBank = new ArrayList<>();
//                lockBank.add(IUHF.LockBank_EPC);
//                String lockCode = mReader.generateLockCode(lockBank, IUHF.LockMode_PLOCK);

//                boolean result1 = mReader.lockMem("00000000", lockCode);
                boolean result1 = true; // skip lock

                if (!result1) {
                    appendLog("Lock Error: " + ErrorCodeManager.getMessage(mReader.getErrCode()));
                    playSound(2);
                } else {

                    // Read to re-check
                    String data = mReader.readData("00000000", IUHF.Bank_EPC, 2, 8);

                    // Compare
                    if (data != null && data.toLowerCase().startsWith(code.toLowerCase())) {
                        appendLog("SUCCESS " + code.toLowerCase());
                        playSound(1);
                    } else {
                        appendLog("We wrote " + dataToWrite + " but read " + data + " instead");
                        playSound(2);
                    }
                }
            }// restore power
            mReader.setPower(power);
        }

    }

    public void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        tvLog.append(timestamp + " - " + message + "\n");
        ScrollView scrollView = findViewById(R.id.log_scroll_view);
        tvLog.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void installBarcodeDecoderCallback() {
        barcodeDecoder.open(this);
        if (barcodeDecoder.open(this)) {
            //BarcodeUtility.getInstance().enablePlaySuccessSound(this, true);
            BarcodeUtility.getInstance().enablePlayFailureSound(this, true);
            barcodeDecoder.setDecodeCallback((BarcodeEntity barcodeEntity) -> {
                if (barcodeEntity.getResultCode() == BarcodeDecoder.DECODE_SUCCESS) {
                    editCode.setText(barcodeEntity.getBarcodeData());
                    appendLog("Barcode: " + barcodeEntity.getBarcodeData());
                    performWriteRFID();
                } else {
                    editCode.setText("");
                    appendLog("Error: " + barcodeEntity.getErrCode());
                }
            });
        } else {
            appendLog("Cannot connect Chainway barcode scanner");
        }

    }

    HashMap<Integer, Integer> soundMap = new HashMap<Integer, Integer>();
    private SoundPool soundPool;
    private float volumnRatio;
    private AudioManager am;

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.serror, 1));
        am = (AudioManager) this.getSystemService(AUDIO_SERVICE);
    }

    private void releaseSoundPool() {
        if(soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    /**
     * 播放提示音
     *
     * @param id 成功1，失败2
     */
    public void playSound(int id) {
        float audioMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); // 返回当前AudioManager对象的最大音量值
        float audioCurrentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);// 返回当前AudioManager对象的音量值
        volumnRatio = audioCurrentVolume / audioMaxVolume;
        try {
            soundPool.play(soundMap.get(id), volumnRatio, // 左声道音量
                    volumnRatio, // 右声道音量
                    1, // 优先级，0为最低
                    0, // 循环次数，0不循环，-1永远循环
                    1 // 回放速度 ，该值在0.5-2.0之间，1为正常速度
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}