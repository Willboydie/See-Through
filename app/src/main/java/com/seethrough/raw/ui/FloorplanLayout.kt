package com.seethrough.raw.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Marker areas in pixels on the 600 by 540 floorplan drawing. */
object FloorplanMap {
    const val WIDTH = 600f
    const val HEIGHT = 540f

    val rooms: Map<Int, RoomArea> = mapOf(
        1 to RoomArea(183f, 88f, 140f, 145f),
        2 to RoomArea(365f, 65f, 92f, 32f),
        3 to RoomArea(366f, 157f, 64f, 24f),
        4 to RoomArea(366f, 248f, 64f, 22f),
        5 to RoomArea(284f, 403f, 136f, 30f),
    )
}

data class RoomArea(val x: Float, val y: Float, val width: Float, val height: Float)

data class FloorPerson(val name: String, val roomNumber: Int?)

data class MarkerPosition(val x: Float, val y: Float)

data class PlacedPerson(
    val name: String,
    val roomNumber: Int?,
    val position: MarkerPosition?,
)

/** People who share a room get separate points inside that room's area. */
fun placePeople(people: List<FloorPerson>): List<PlacedPerson> {
    val counts = HashMap<Int, Int>()
    for (person in people) {
        val room = person.roomNumber ?: continue
        if (room in FloorplanMap.rooms) {
            counts[room] = (counts[room] ?: 0) + 1
        }
    }
    val slots = HashMap<Int, Int>()
    return people.map { person ->
        val roomNumber = person.roomNumber
        val room = roomNumber?.let { FloorplanMap.rooms[it] }
        if (roomNumber == null || room == null) {
            return@map PlacedPerson(person.name, roomNumber, null)
        }
        val index = slots[roomNumber] ?: 0
        slots[roomNumber] = index + 1
        val count = counts.getValue(roomNumber)
        val spread = count * room.width / max(room.height, 40f)
        val columns = min(count, max(1, floor(sqrt(spread.toDouble()) + 0.5).toInt()))
        val rows = ceil(count.toDouble() / columns).toInt()
        PlacedPerson(
            name = person.name,
            roomNumber = roomNumber,
            position = MarkerPosition(
                x = (room.x + ((index % columns) + 0.5f) * room.width / columns) / FloorplanMap.WIDTH,
                y = (room.y + (index / columns + 0.5f) * room.height / rows) / FloorplanMap.HEIGHT,
            ),
        )
    }
}
