package com.trackstudio.rfidmanager

import kotlinx.coroutines.delay

interface UhfReader {
    val connectStatus: String
    val isPowerOn: Boolean
    val errCode: Int
    val initPathDescription: String
        get() = "unknown"

    fun init(): Boolean
    fun free(): Boolean
}

interface UhfReaderFactory {
    fun create(): UhfReader
}

interface UhfScannerCleaner {
    fun isUhfWorking(): Boolean
    fun enableUhf(): String
    fun stopUhf()
    fun disableUhf(): String
}

enum class UhfInitAttemptResult {
    SUCCEEDED,
    FAILED
}

data class UhfInitAttemptLog(
    val attempt: Int,
    val result: UhfInitAttemptResult,
    val initResult: Boolean,
    val connectStatus: String,
    val isPowerOn: Boolean,
    val errCode: Int,
    val isUhfWorking: Boolean?,
    val nextRetryDelayMs: Long,
    val cleanupExceptions: List<String> = emptyList(),
    val scannerEnableResult: String = "not-run",
    val scannerStopResult: String = "not-run",
    val scannerDisableResult: String = "not-run",
    val previousReaderFreeResult: String? = null,
    val initPath: String = "unknown"
)

data class UhfInitResult(
    val connected: Boolean,
    val reader: UhfReader?,
    val attempts: List<UhfInitAttemptLog>
)

class UhfConnectionController(
    private val scannerCleaner: UhfScannerCleaner,
    private val readerFactory: UhfReaderFactory,
    private val retryDelaysMs: List<Long> = listOf(300L, 700L, 1_500L),
    private val cleanupSettleDelayMs: Long = 300L,
    private val delayHandler: suspend (Long) -> Unit = { delay(it) },
    private val logHandler: (UhfInitAttemptLog) -> Unit = {}
) {
    suspend fun initialize(previousReader: UhfReader? = null): UhfInitResult {
        val cleanup = cleanupScannerAndPreviousReader(previousReader)
        if (cleanupSettleDelayMs > 0L) {
            delayHandler(cleanupSettleDelayMs)
        }
        val attempts = mutableListOf<UhfInitAttemptLog>()
        val maxAttempts = retryDelaysMs.size.coerceAtLeast(1)

        for (attempt in 1..maxAttempts) {
            val reader = readerFactory.create()
            val initResult = runCatching { reader.init() }.getOrElse {
                cleanup.exceptions.add("reader.init:${it.message ?: it::class.java.simpleName}")
                false
            }
            val connected = initResult && reader.connectStatus == "CONNECTED"
            val nextDelay = if (connected || attempt == maxAttempts) 0L else retryDelaysMs[attempt - 1]
            val attemptLog = UhfInitAttemptLog(
                attempt = attempt,
                result = if (connected) UhfInitAttemptResult.SUCCEEDED else UhfInitAttemptResult.FAILED,
                initResult = initResult,
                connectStatus = reader.connectStatus,
                isPowerOn = reader.isPowerOn,
                errCode = reader.errCode,
                isUhfWorking = cleanup.isUhfWorking,
                nextRetryDelayMs = nextDelay,
                cleanupExceptions = cleanup.exceptions.toList(),
                scannerEnableResult = cleanup.scannerEnableResult,
                scannerStopResult = cleanup.scannerStopResult,
                scannerDisableResult = cleanup.scannerDisableResult,
                previousReaderFreeResult = cleanup.previousReaderFreeResult,
                initPath = reader.initPathDescription
            )
            attempts.add(attemptLog)
            logHandler(attemptLog)

            if (connected) {
                return UhfInitResult(true, reader, attempts)
            }

            runCatching { reader.free() }.onFailure {
                cleanup.exceptions.add("reader.free:${it.message ?: it::class.java.simpleName}")
            }
            if (nextDelay > 0L) {
                delayHandler(nextDelay)
            }
        }

        return UhfInitResult(false, null, attempts)
    }

    private fun cleanupScannerAndPreviousReader(previousReader: UhfReader?): CleanupState {
        val exceptions = mutableListOf<String>()
        val isUhfWorking = runCatching { scannerCleaner.isUhfWorking() }.getOrElse {
            exceptions.add("scanner.isUhfWorking:${it.message ?: it::class.java.simpleName}")
            null
        }
        val scannerEnableResult = runCatching { scannerCleaner.enableUhf() }.fold(
            onSuccess = { it },
            onFailure = {
                exceptions.add("scanner.enableUhf:${it.message ?: it::class.java.simpleName}")
                "exception"
            }
        )
        val scannerStopResult = runCatching { scannerCleaner.stopUhf() }.fold(
            onSuccess = { "ok" },
            onFailure = {
                exceptions.add("scanner.stopUhf:${it.message ?: it::class.java.simpleName}")
                "exception"
            }
        )
        val scannerDisableResult = runCatching { scannerCleaner.disableUhf() }.fold(
            onSuccess = { it },
            onFailure = {
                exceptions.add("scanner.disableUhf:${it.message ?: it::class.java.simpleName}")
                "exception"
            }
        )
        var previousReaderFreeResult: String? = null
        previousReader?.let { reader ->
            previousReaderFreeResult = runCatching { reader.free() }.fold(
                onSuccess = { "ok" },
                onFailure = {
                    exceptions.add("old.free:${it.message ?: it::class.java.simpleName}")
                    "exception"
                }
            )
        }
        return CleanupState(isUhfWorking, exceptions, scannerEnableResult, scannerStopResult, scannerDisableResult, previousReaderFreeResult)
    }

    private data class CleanupState(
        val isUhfWorking: Boolean?,
        val exceptions: MutableList<String>,
        val scannerEnableResult: String,
        val scannerStopResult: String,
        val scannerDisableResult: String,
        val previousReaderFreeResult: String?
    )
}
