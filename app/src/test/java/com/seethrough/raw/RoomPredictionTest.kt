package com.seethrough.raw

import com.seethrough.raw.ui.FloorPerson
import com.seethrough.raw.ui.placePeople
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomPredictionTest {
    @Test
    fun aSplitTreeFollowsTheThresholdAndFeatureOrder() {
        val forest = RoomForest.read(SPLIT)
        assertEquals(1, forest.predict(doubleArrayOf(10.0, 0.0)))
        assertEquals(2, forest.predict(doubleArrayOf(11.0, 0.0)))
        assertEquals(listOf("aa", "bb"), forest.features)
        assertArrayEquals(
            doubleArrayOf(4.0, 8.0),
            forest.vector(mapOf("aa" to 4, "zz" to 100, "bb" to 8)),
            0.0,
        )
        assertArrayEquals(doubleArrayOf(3.0, 0.0), forest.vector(mapOf("aa" to 3)), 0.0)
    }

    @Test
    fun anEvenVoteKeepsTheEarlierRoom() {
        val forest = RoomForest.read(TIE)
        assertEquals(1, forest.predict(doubleArrayOf(0.0)))
        assertEquals(2, RoomForest.read(WEIGHTED).predict(doubleArrayOf(0.0)))
    }

    @Test
    fun theBundledModelMatchesTheTrainingChecks() {
        val model = RoomForest.read(repoFile("ml/models/room_forest.json").readText())
        val checks = Json.parseToJsonElement(repoFile("ml/models/room_forest.checks.json").readText()).jsonObject
        val cases = checks.getValue("cases").jsonArray
        assertTrue(cases.size >= 4)
        for (case in cases) {
            val obj = case.jsonObject
            val vector = obj.getValue("vector").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            assertEquals(obj.getValue("room").jsonPrimitive.int, model.predict(vector))
        }
    }

    @Test
    fun diagramRoomsFollowTheModelLabels() {
        assertEquals(1, RoomMap.uiRoom(1))
        assertEquals(3, RoomMap.uiRoom(2))
        assertEquals(4, RoomMap.uiRoom(3))
        assertEquals(5, RoomMap.uiRoom(4))
        assertNull(RoomMap.uiRoom(5))
        assertFalse((1..5).map(RoomMap::uiRoom).contains(2))
    }

    @Test
    fun theScreenChangesOnlyWhenTheDiagramRoomChanges() {
        val locator = RoomLocator(RoomForest.read(SPLIT))
        assertTrue(locator.onScan(mapOf("aa" to 4)))
        assertEquals(1, locator.uiRoom)
        assertFalse(locator.onScan(mapOf("aa" to 4)))
        assertFalse(locator.onScan(mapOf("aa" to 9, "zz" to 50)))
        assertEquals(1, locator.uiRoom)
        assertTrue(locator.onScan(mapOf("aa" to 40)))
        assertEquals(3, locator.uiRoom)
        assertTrue(locator.onScan(emptyMap()))
        assertNull(locator.uiRoom)
    }

    @Test
    fun modelRoomFiveIsNotDiagramRoomTwo() {
        val locator = RoomLocator(RoomForest.read(UNMAPPED))
        assertTrue(locator.onScan(mapOf("aa" to 1)))
        assertEquals(5, locator.uiRoom)
        assertTrue(locator.onScan(mapOf("aa" to 40)))
        assertNull(locator.uiRoom)
        assertNotEquals(2, locator.uiRoom)
    }

    @Test
    fun theStrongerReadingWinsAndRedactedAddressesAreDropped() {
        val strengths = strengthsByBssid(
            listOf(
                network("02:00:00:00:00:00", 900),
                network("AA", 10),
                network("aa", 40),
            ),
        )
        assertEquals(mapOf("aa" to 40), strengths)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVectorOfTheWrongLengthIsRejected() {
        RoomForest.read(SPLIT).predict(doubleArrayOf(1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownModelFormatIsRejected() {
        RoomForest.read(SPLIT.replace(RoomForest.FORMAT, "other"))
    }

    @Test
    fun peopleSharingARoomGetDistinctLocationsWithinIt() {
        val people = placePeople(listOf(FloorPerson("Alex", 2), FloorPerson("Maya", 2)))
        assertNotEquals(people[0].position, people[1].position)
        for (person in people) {
            val position = person.position!!
            assertTrue(position.x > 350f / 600f && position.x < 478f / 600f)
            assertTrue(position.y > 50f / 540f && position.y < 143f / 540f)
        }
    }

    @Test
    fun roomChangesMoveTheMarkerAndUnknownRoomsStayOffTheMap() {
        val before = placePeople(listOf(FloorPerson("Alex", 1))).single()
        val after = placePeople(listOf(FloorPerson("Alex", 5))).single()
        assertNotEquals(before.position, after.position)
        assertEquals(253f / 600f, before.position!!.x, 0.0001f)
        assertEquals(160.5f / 540f, before.position!!.y, 0.0001f)
        assertNull(placePeople(listOf(FloorPerson("Alex", 99))).single().position)
        assertNull(placePeople(listOf(FloorPerson("You", null))).single().position)
        assertTrue(placePeople(emptyList()).isEmpty())
    }

    private fun network(bssid: String, signal: Int) = WifiNetwork(
        name = bssid,
        bssid = bssid,
        signal = signal,
        rssi = -50,
        frequencyMhz = 2412,
        connected = false,
    )

    private fun repoFile(relative: String): File {
        val start = System.getProperty("user.dir") ?: error("No working directory")
        var dir: File? = File(start)
        repeat(6) {
            val current = dir ?: return@repeat
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
        error("Could not find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val SPLIT = """
            {
              "format": "seethrough-room-forest-v1",
              "features": ["AA", "bb"],
              "classes": [1, 2],
              "trees": [{
                "feature": [0, -2, -2],
                "threshold": [10.5, -2, -2],
                "left": [1, -1, -1],
                "right": [2, -1, -1],
                "counts": [[0, 0], [3, 0], [0, 4]]
              }]
            }
        """.trimIndent()

        val TIE = """
            {
              "format": "seethrough-room-forest-v1",
              "features": ["aa"],
              "classes": [1, 2],
              "trees": [
                {"feature": [-2], "threshold": [-2], "left": [-1], "right": [-1], "counts": [[1, 0]]},
                {"feature": [-2], "threshold": [-2], "left": [-1], "right": [-1], "counts": [[0, 1]]}
              ]
            }
        """.trimIndent()

        val WEIGHTED = """
            {
              "format": "seethrough-room-forest-v1",
              "features": ["aa"],
              "classes": [1, 2],
              "trees": [
                {"feature": [-2], "threshold": [-2], "left": [-1], "right": [-1], "counts": [[1, 3]]}
              ]
            }
        """.trimIndent()

        val UNMAPPED = """
            {
              "format": "seethrough-room-forest-v1",
              "features": ["aa"],
              "classes": [4, 5],
              "trees": [{
                "feature": [0, -2, -2],
                "threshold": [10.5, -2, -2],
                "left": [1, -1, -1],
                "right": [2, -1, -1],
                "counts": [[0, 0], [2, 0], [0, 2]]
              }]
            }
        """.trimIndent()
    }
}
