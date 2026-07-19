package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClientConfigTest {

    @Test
    fun nullTextYieldsDefaults() {
        assertEquals(ClientConfig.DEFAULTS, ClientConfig.parse(null))
    }

    @Test
    fun garbageYieldsDefaults() {
        assertEquals(ClientConfig.DEFAULTS, ClientConfig.parse("not json at all"))
    }

    @Test
    fun emptyObjectYieldsDefaults() {
        assertEquals(ClientConfig.DEFAULTS, ClientConfig.parse("{}"))
    }

    @Test
    fun bothKeysOverride() {
        val values = ClientConfig.parse(
            """{"connect_window_seconds": 30, "connect_retry_interval_seconds": 5}"""
        )
        assertEquals(30_000L, values.connectWindowMs)
        assertEquals(5_000L, values.retryIntervalMs)
    }

    @Test
    fun partialOverrideKeepsOtherDefault() {
        val values = ClientConfig.parse("""{"connect_window_seconds": 42}""")
        assertEquals(42_000L, values.connectWindowMs)
        assertEquals(ClientConfig.DEFAULT_RETRY_INTERVAL_MS, values.retryIntervalMs)
    }

    @Test
    fun fractionalSecondsAreSupported() {
        val values = ClientConfig.parse("""{"connect_retry_interval_seconds": 0.5}""")
        assertEquals(500L, values.retryIntervalMs)
    }

    @Test
    fun nonPositiveValuesFallBackToDefaults() {
        val values = ClientConfig.parse(
            """{"connect_window_seconds": 0, "connect_retry_interval_seconds": -3}"""
        )
        assertEquals(ClientConfig.DEFAULTS, values)
    }

    @Test
    fun nonNumericValuesFallBackToDefaults() {
        val values = ClientConfig.parse(
            """{"connect_window_seconds": "ten", "connect_retry_interval_seconds": null}"""
        )
        assertEquals(ClientConfig.DEFAULTS, values)
    }

    @Test
    fun invalidKeyFallsBackWithoutAffectingValidKey() {
        val values = ClientConfig.parse(
            """{"connect_window_seconds": "bogus", "connect_retry_interval_seconds": 4}"""
        )
        assertEquals(ClientConfig.DEFAULT_CONNECT_WINDOW_MS, values.connectWindowMs)
        assertEquals(4_000L, values.retryIntervalMs)
    }

    @Test
    fun loadMissingFileYieldsDefaults() {
        val dir = Files.createTempDirectory("clientconfig").toFile()
        assertEquals(ClientConfig.DEFAULTS, ClientConfig.load(File(dir, "config.json")))
    }

    @Test
    fun loadReadsFileContent() {
        val file = File.createTempFile("clientconfig", ".json")
        file.deleteOnExit()
        file.writeText("""{"connect_window_seconds": 15}""")
        assertEquals(15_000L, ClientConfig.load(file).connectWindowMs)
    }
}
