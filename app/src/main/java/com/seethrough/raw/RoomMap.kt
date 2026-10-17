package com.seethrough.raw

/**
 * Diagram room for a model label. The floorplan numbers and the training labels differ.
 *
 * UI room = model room
 * 1 = 1
 * 3 = 2
 * 4 = 3
 * 5 = 4
 *
 * Diagram room 2 is not in the model. Model room 5 is not diagram room 2, so it is not drawn.
 */
object RoomMap {
    private val modelToUi = mapOf(
        1 to 1,
        2 to 3,
        3 to 4,
        4 to 5,
    )

    fun uiRoom(modelRoom: Int): Int? = modelToUi[modelRoom]
}
