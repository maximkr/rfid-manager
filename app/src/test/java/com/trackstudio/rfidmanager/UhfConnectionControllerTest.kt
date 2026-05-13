package com.trackstudio.rfidmanager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UhfConnectionControllerTest {

    @Test
    fun scannerCleanupRunsBeforeReaderInit() = runBlocking {
        val events = mutableListOf<String>()
        val scanner = FakeScannerCleaner(events, isWorking = true)
        val reader = FakeReader(events, initResults = mutableListOf(true))
        val controller = controller(
            scanner = scanner,
            readers = listOf(reader),
            delayHandler = { events.add("delay:$it") }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertSame(reader, result.reader)
        assertEquals(
            listOf("scanner.isUhfWorking", "scanner.enableUhf", "scanner.enableBarcode", "scanner.stopUhf", "scanner.disableUhf", "delay:300", "factory.create", "reader.init"),
            events
        )
    }

    @Test
    fun oldReaderIsFreedBeforeNewReaderIsCreated() = runBlocking {
        val events = mutableListOf<String>()
        val oldReader = FakeReader(events, name = "old", initResults = mutableListOf())
        val newReader = FakeReader(events, name = "new", initResults = mutableListOf(true))
        val controller = controller(
            scanner = FakeScannerCleaner(events),
            readers = listOf(newReader),
            delayHandler = { events.add("delay:$it") }
        )

        val result = controller.initialize(previousReader = oldReader)

        assertTrue(result.connected)
        assertSame(newReader, result.reader)
        assertEquals(
            listOf("scanner.isUhfWorking", "scanner.enableUhf", "scanner.enableBarcode", "scanner.stopUhf", "scanner.disableUhf", "old.free", "delay:300", "factory.create", "new.init"),
            events
        )
    }

    @Test
    fun retriesUntilInitSucceedsAfterBackoff() = runBlocking {
        val events = mutableListOf<String>()
        val firstReader = FakeReader(events, name = "first", initResults = mutableListOf(false), statusValue = "DISCONNECTED", powerOnValue = true, errCodeValue = -1)
        val secondReader = FakeReader(events, name = "second", initResults = mutableListOf(true), statusValue = "CONNECTED", powerOnValue = true, errCodeValue = 0)
        val delays = mutableListOf<Long>()
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events, isWorking = true),
            readers = listOf(firstReader, secondReader),
            retryDelaysMs = listOf(25L, 50L),
            delayHandler = { delays.add(it) },
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertSame(secondReader, result.reader)
        assertEquals(listOf(300L, 25L), delays)
        assertEquals(listOf("first.free"), events.filter { it == "first.free" })
        assertEquals(2, logs.size)
        assertEquals(1, logs[0].attempt)
        assertFalse(logs[0].initResult)
        assertEquals("DISCONNECTED", logs[0].connectStatus)
        assertTrue(logs[0].isPowerOn)
        assertEquals(-1, logs[0].errCode)
        assertEquals(true, logs[0].isUhfWorking)
        assertEquals("fake-init:first", logs[0].initPath)
        assertEquals(25L, logs[0].nextRetryDelayMs)
        assertEquals(2, logs[1].attempt)
        assertTrue(logs[1].initResult)
        assertEquals("CONNECTED", logs[1].connectStatus)
        assertEquals(0L, logs[1].nextRetryDelayMs)
    }

    @Test
    fun returnsFailureAfterAllRetries() = runBlocking {
        val events = mutableListOf<String>()
        val readers = listOf(
            FakeReader(events, name = "first", initResults = mutableListOf(false), errCodeValue = -1),
            FakeReader(events, name = "second", initResults = mutableListOf(false), errCodeValue = -2),
            FakeReader(events, name = "third", initResults = mutableListOf(false), errCodeValue = -3)
        )
        val delays = mutableListOf<Long>()
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events, isWorking = false),
            readers = readers,
            retryDelaysMs = listOf(10L, 20L, 30L),
            delayHandler = { delays.add(it) },
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize()

        assertFalse(result.connected)
        assertEquals(null, result.reader)
        assertEquals(listOf(300L, 10L, 20L), delays)
        assertEquals(3, logs.size)
        assertEquals(listOf(1, 2, 3), logs.map { it.attempt })
        assertEquals(listOf(10L, 20L, 0L), logs.map { it.nextRetryDelayMs })
        assertTrue(logs.all { !it.initResult && it.result == UhfInitAttemptResult.FAILED })
    }

    @Test
    fun scannerDisableCanBeLoggedAsSkipped() = runBlocking {
        val events = mutableListOf<String>()
        val reader = FakeReader(events, initResults = mutableListOf(true))
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events, disableResult = "skipped"),
            readers = listOf(reader),
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertEquals("ok", logs.single().scannerEnableResult)
        assertEquals("ok", logs.single().scannerBarcodeEnableResult)
        assertEquals("skipped", logs.single().scannerDisableResult)
        assertEquals("fake-init:reader", logs.single().initPath)
    }

    @Test
    fun barcodeFunctionIsEnabledBeforeReaderInit() = runBlocking {
        val events = mutableListOf<String>()
        val reader = FakeReader(events, initResults = mutableListOf(true))
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events, barcodeEnableResult = "ok:1d+2dh+2d"),
            readers = listOf(reader),
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertEquals("ok:1d+2dh+2d", logs.single().scannerBarcodeEnableResult)
        assertTrue(events.indexOf("scanner.enableBarcode") < events.indexOf("factory.create"))
    }

    @Test
    fun logsEachInitStrategyUntilOneConnects() = runBlocking {
        val events = mutableListOf<String>()
        val sampleReader = FakeReader(events, name = "sample-init", initResults = mutableListOf(false), statusValue = "DISCONNECTED", errCodeValue = -1)
        val contextReader = FakeReader(events, name = "context-init", initResults = mutableListOf(false), statusValue = "DISCONNECTED", errCodeValue = -2)
        val systemReader = FakeReader(events, name = "system-power-context-init", initResults = mutableListOf(true), statusValue = "CONNECTED", errCodeValue = 0)
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events),
            readers = listOf(sampleReader, contextReader, systemReader),
            retryDelaysMs = listOf(5L, 5L, 5L),
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertSame(systemReader, result.reader)
        assertEquals(
            listOf("fake-init:sample-init", "fake-init:context-init", "fake-init:system-power-context-init"),
            logs.map { it.initPath }
        )
        assertEquals(listOf(-1, -2, 0), logs.map { it.errCode })
        assertEquals(listOf(UhfInitAttemptResult.FAILED, UhfInitAttemptResult.FAILED, UhfInitAttemptResult.SUCCEEDED), logs.map { it.result })
    }

    @Test
    fun cleanupSettleDelayRunsAfterCleanupAndBeforeFirstReaderCreation() = runBlocking {
        val events = mutableListOf<String>()
        val reader = FakeReader(events, initResults = mutableListOf(true))
        val controller = controller(
            scanner = FakeScannerCleaner(events),
            readers = listOf(reader),
            cleanupSettleDelayMs = 123L,
            delayHandler = { events.add("delay:$it") }
        )

        val result = controller.initialize()

        assertTrue(result.connected)
        assertEquals(
            listOf("scanner.isUhfWorking", "scanner.enableUhf", "scanner.enableBarcode", "scanner.stopUhf", "scanner.disableUhf", "delay:123", "factory.create", "reader.init"),
            events
        )
    }

    @Test
    fun scannerAndFreeExceptionsAreLoggedAndDoNotStopInit() = runBlocking {
        val events = mutableListOf<String>()
        val oldReader = FakeReader(events, name = "old", initResults = mutableListOf(), freeException = IllegalStateException("free boom"))
        val newReader = FakeReader(events, name = "new", initResults = mutableListOf(true))
        val logs = mutableListOf<UhfInitAttemptLog>()
        val controller = controller(
            scanner = FakeScannerCleaner(events, isWorkingException = IllegalStateException("state boom"), enableException = IllegalStateException("enable boom"), barcodeEnableException = IllegalStateException("barcode boom"), stopException = IllegalStateException("stop boom"), disableException = IllegalStateException("disable boom")),
            readers = listOf(newReader),
            logHandler = { logs.add(it) }
        )

        val result = controller.initialize(previousReader = oldReader)

        assertTrue(result.connected)
        assertSame(newReader, result.reader)
        assertEquals(1, logs.size)
        assertEquals(listOf("scanner.isUhfWorking:state boom", "scanner.enableUhf:enable boom", "scanner.enableBarcode:barcode boom", "scanner.stopUhf:stop boom", "scanner.disableUhf:disable boom", "old.free:free boom"), logs[0].cleanupExceptions)
    }

    private fun controller(
        scanner: UhfScannerCleaner,
        readers: List<FakeReader>,
        retryDelaysMs: List<Long> = listOf(10L, 20L, 40L),
        cleanupSettleDelayMs: Long = 300L,
        delayHandler: suspend (Long) -> Unit = {},
        logHandler: (UhfInitAttemptLog) -> Unit = {}
    ): UhfConnectionController {
        val iterator = readers.iterator()
        return UhfConnectionController(
            scannerCleaner = scanner,
            readerFactory = object : UhfReaderFactory {
                override fun create(): UhfReader {
                    check(iterator.hasNext()) { "No fake reader available" }
                    return iterator.next().also { it.events.add("factory.create") }
                }
            },
            retryDelaysMs = retryDelaysMs,
            cleanupSettleDelayMs = cleanupSettleDelayMs,
            delayHandler = delayHandler,
            logHandler = logHandler
        )
    }

    private class FakeScannerCleaner(
        private val events: MutableList<String>,
        private val isWorking: Boolean = false,
        private val isWorkingException: RuntimeException? = null,
        private val enableException: RuntimeException? = null,
        private val enableResult: String = "ok",
        private val barcodeEnableException: RuntimeException? = null,
        private val barcodeEnableResult: String = "ok",
        private val stopException: RuntimeException? = null,
        private val disableException: RuntimeException? = null,
        private val disableResult: String = "ok"
    ) : UhfScannerCleaner {
        override fun isUhfWorking(): Boolean {
            events.add("scanner.isUhfWorking")
            isWorkingException?.let { throw it }
            return isWorking
        }

        override fun enableUhf(): String {
            events.add("scanner.enableUhf")
            enableException?.let { throw it }
            return enableResult
        }

        override fun enableBarcode(): String {
            events.add("scanner.enableBarcode")
            barcodeEnableException?.let { throw it }
            return barcodeEnableResult
        }

        override fun stopUhf() {
            events.add("scanner.stopUhf")
            stopException?.let { throw it }
        }

        override fun disableUhf(): String {
            events.add("scanner.disableUhf")
            disableException?.let { throw it }
            return disableResult
        }
    }

    private class FakeReader(
        val events: MutableList<String>,
        private val name: String = "reader",
        private val initResults: MutableList<Boolean>,
        private val statusValue: String = "CONNECTED",
        private val powerOnValue: Boolean = true,
        private val errCodeValue: Int = 0,
        private val freeException: RuntimeException? = null
    ) : UhfReader {
        override val connectStatus: String get() = statusValue
        override val isPowerOn: Boolean get() = powerOnValue
        override val errCode: Int get() = errCodeValue
        override val initPathDescription: String get() = "fake-init:$name"

        override fun init(): Boolean {
            events.add("$name.init")
            return initResults.removeAt(0)
        }

        override fun free(): Boolean {
            events.add("$name.free")
            freeException?.let { throw it }
            return true
        }
    }
}
