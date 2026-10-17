package com.seethrough.raw

/**
 * Turns each new Wi-Fi vector into the diagram room to draw.
 * The same vector is not scored again. The caller should redraw only when [onScan] returns true.
 * A scan that includes none of the model's access points is not a location, so the room is unknown.
 */
class RoomLocator(private val forest: RoomForest) {
    var uiRoom: Int? = null
        private set

    private var previousVector: DoubleArray? = null

    fun onScan(strengths: Map<String, Int>): Boolean {
        val vector = forest.vector(strengths)
        val previous = previousVector
        if (previous != null && previous.contentEquals(vector)) return false
        previousVector = vector
        val next = if (vector.any { it != 0.0 }) RoomMap.uiRoom(forest.predict(vector)) else null
        if (next == uiRoom) return false
        uiRoom = next
        return true
    }
}
