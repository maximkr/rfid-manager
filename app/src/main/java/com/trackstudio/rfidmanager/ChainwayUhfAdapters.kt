package com.trackstudio.rfidmanager

import android.content.Context
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.scanner.IScanner
import com.rscja.scanner.utility.ScannerUtility

class ChainwayUhfReaderAdapter(
    val reader: RFIDWithUHFUART,
    private val context: Context,
    private val initStrategy: ChainwayUhfInitStrategy
) : UhfReader {
    override val connectStatus: String
        get() = reader.connectStatus?.name ?: "UNKNOWN"

    override val isPowerOn: Boolean
        get() = reader.isPowerOn

    override val errCode: Int
        get() = reader.errCode

    override val initPathDescription: String
        get() = initStrategy.description

    override fun init(): Boolean {
        return when (initStrategy) {
            ChainwayUhfInitStrategy.SAMPLE_INIT -> reader.init()
            ChainwayUhfInitStrategy.CONTEXT_INIT -> reader.init(context)
            ChainwayUhfInitStrategy.SYSTEM_POWER_CONTEXT_INIT -> {
                reader.setPowerOnBySystem(context)
                reader.init(context)
            }
        }
    }

    override fun free(): Boolean = reader.free()
}

enum class ChainwayUhfInitStrategy(val description: String) {
    SAMPLE_INIT("RFIDWithUHFUART.getInstance()+init()"),
    CONTEXT_INIT("RFIDWithUHFUART.getInstance()+init(context)"),
    SYSTEM_POWER_CONTEXT_INIT("RFIDWithUHFUART.getInstance()+setPowerOnBySystem+init(context)")
}

class ChainwayUhfReaderFactory(
    private val context: Context
) : UhfReaderFactory {
    private var nextStrategyIndex = 0
    private val strategies = ChainwayUhfInitStrategy.entries

    override fun create(): UhfReader {
        val strategy = strategies[nextStrategyIndex.coerceAtMost(strategies.lastIndex)]
        nextStrategyIndex += 1
        return ChainwayUhfReaderAdapter(RFIDWithUHFUART.getInstance(), context, strategy)
    }
}

class ChainwayScannerCleaner(
    private val context: Context,
    private val scannerUtility: ScannerUtility = ScannerUtility.getScannerInerface()
) : UhfScannerCleaner {
    override fun isUhfWorking(): Boolean = scannerUtility.isUhfWorking(context)

    override fun enableUhf(): String {
        scannerUtility.enableFunction(context, IScanner.FUNCTION_UHF)
        return "ok"
    }

    override fun enableBarcode(): String {
        scannerUtility.enableFunction(context, IScanner.FUNCTION_1D)
        scannerUtility.enableFunction(context, IScanner.FUNCTION_2D_H)
        scannerUtility.enableFunction(context, IScanner.FUNCTION_2D)
        return "ok:1d+2dh+2d"
    }

    override fun stopUhf() {
        scannerUtility.stopScan(context, IScanner.FUNCTION_UHF)
    }

    override fun disableUhf(): String {
        // Avoid persistently disabling the Keyboard Emulator UHF function; direct SDK init only needs scan stopped.
        return "skipped"
    }
}
