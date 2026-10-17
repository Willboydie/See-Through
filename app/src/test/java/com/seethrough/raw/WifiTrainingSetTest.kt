package com.seethrough.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiTrainingSetTest {
    @Test
    fun aNewAccessPointAppendsARowAndUploadPadsWithZeros() {
        val set = WifiTrainingSet(now = { "t" })
        set.observe(listOf(ap("AA", 400, "A"), ap("bb", 200, "B")))
        set.label(1)
        set.observe(listOf(ap("aa", 410, "A"), ap("cc", 800, "C")))
        set.label(2)

        val snapshot = set.snapshot()
        assertEquals(listOf("aa", "bb", "cc"), snapshot.accessPoints.map { it.bssid })
        val vectors = TrainingProtocol.vectors(snapshot)
        assertEquals(3, vectors[0].size)
        assertEquals(vectors[0].size, vectors[1].size)
        assertArrayEquals(intArrayOf(400, 200, 0), vectors[0])
        assertArrayEquals(intArrayOf(410, 0, 800), vectors[1])
        assertEquals(listOf(1, 1, 0, 0, 0), set.readout.value.roomCounts)
    }

    @Test
    fun duplicateReadingsKeepTheStrongerSignalAndIgnoreThePlaceholder() {
        val set = WifiTrainingSet(now = { "t" })
        set.observe(
            listOf(
                ap("02:00:00:00:00:00", 900, "redacted"),
                ap("", 900, "blank"),
                ap("aa", 10, "weak"),
                ap("AA", 40, "strong"),
            ),
        )
        set.label(4)

        val snapshot = set.snapshot()
        assertEquals(1, snapshot.accessPoints.size)
        assertEquals("strong", snapshot.accessPoints.single().name)
        assertArrayEquals(intArrayOf(40), TrainingProtocol.vectors(snapshot).single())
        assertEquals(4, snapshot.samples.single().room)
    }

    @Test
    fun anAccessPointMissingFromTheLatestScanIsZero() {
        val set = WifiTrainingSet(now = { "t" })
        set.observe(listOf(ap("aa", 100), ap("bb", 200)))
        set.observe(listOf(ap("aa", 150)))
        set.label(3)

        assertNull(set.readout.value.rows[1].signal)
        assertArrayEquals(intArrayOf(150, 0), TrainingProtocol.vectors(set.snapshot()).single())
    }

    @Test
    fun theProtocolFileRoundTripsAndKeepsEqualLengths() {
        val set = WifiTrainingSet(now = { "2026-10-17T14:00:00+01:00" })
        set.observe(listOf(ap("aa", 10, "Quoted \"name\"")))
        set.label(5)
        set.observe(listOf(ap("bb", 20, "Second")))
        set.label(5)

        val json = set.protocolJson()
        assertTrue(json.contains("\"protocol\": \"${TrainingProtocol.ID}\""))
        assertTrue(json.contains("padded"))
        assertTrue(json.contains("vectors"))

        val restored = TrainingProtocol.decode(json)
        val vectors = TrainingProtocol.vectors(restored)
        assertArrayEquals(intArrayOf(10, 0), vectors[0])
        assertArrayEquals(intArrayOf(0, 20), vectors[1])
        assertEquals("Quoted \"name\"", restored.accessPoints[0].name)
        assertEquals("2026-10-17T14:00:00+01:00", restored.samples[0].capturedAt)

        val again = WifiTrainingSet(now = { "later" })
        again.restore(restored)
        again.observe(listOf(ap("cc", 30, "Third")))
        again.label(1)
        val grown = TrainingProtocol.vectors(again.snapshot())
        assertArrayEquals(intArrayOf(10, 0, 0), grown[0])
        assertArrayEquals(intArrayOf(0, 20, 0), grown[1])
        assertArrayEquals(intArrayOf(0, 0, 30), grown[2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsARoomOutsideOneToFive() {
        WifiTrainingSet(now = { "t" }).label(6)
    }

    private fun ap(bssid: String, signal: Int, name: String = bssid) = WifiNetwork(
        name = name,
        bssid = bssid,
        signal = signal,
        rssi = -50,
        frequencyMhz = 2412,
        connected = false,
    )
}
