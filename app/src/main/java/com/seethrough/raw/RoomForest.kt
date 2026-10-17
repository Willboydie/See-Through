package com.seethrough.raw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A random forest saved by ml/room_forest.py. Replace ml/models/room_forest.json and rebuild
 * the app to use a newly trained forest. Feature order in that file is the vector order.
 */
class RoomForest private constructor(
    val features: List<String>,
    val classes: List<Int>,
    private val trees: List<Tree>,
) {
    fun vector(strengths: Map<String, Int>): DoubleArray {
        return DoubleArray(features.size) { index ->
            (strengths[features[index]] ?: 0).toDouble()
        }
    }

    /** The room label from the model, before it is mapped onto the floorplan. */
    fun predict(vector: DoubleArray): Int {
        require(vector.size == features.size) {
            "This vector has ${vector.size} strengths; the model has ${features.size}."
        }
        val scores = DoubleArray(classes.size)
        for (tree in trees) {
            var node = 0
            var hops = 0
            while (tree.left[node] != -1) {
                hops += 1
                if (hops > tree.left.size) error("A tree in the room model never reached a leaf.")
                val next = if (vector[tree.feature[node]] <= tree.threshold[node]) {
                    tree.left[node]
                } else {
                    tree.right[node]
                }
                node = next
            }
            val counts = tree.counts[node]
            var total = 0.0
            for (count in counts) total += count
            if (total <= 0.0) continue
            for (index in counts.indices) {
                scores[index] += counts[index] / total
            }
        }
        var best = 0
        for (index in 1 until scores.size) {
            if (scores[index] > scores[best]) best = index
        }
        return classes[best]
    }

    private class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        val counts: Array<DoubleArray>,
    )

    companion object {
        const val FORMAT = "seethrough-room-forest-v1"
        const val ASSET = "room_forest.json"

        fun read(text: String): RoomForest {
            val root = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (error: Exception) {
                throw IllegalArgumentException("The room model is not valid JSON.", error)
            }
            val format = root.text("format")
            if (format != FORMAT) {
                throw IllegalArgumentException("This room model uses format $format; the app reads $FORMAT.")
            }
            val features = root.array("features").map { element ->
                element.jsonPrimitive.content.trim().lowercase()
            }
            if (features.isEmpty()) throw IllegalArgumentException("The room model has no access points.")
            if (features.distinct().size != features.size) {
                throw IllegalArgumentException("The room model repeats an access point.")
            }
            val classes = root.array("classes").map { it.jsonPrimitive.int }
            if (classes.isEmpty()) throw IllegalArgumentException("The room model has no rooms.")
            val trees = root.array("trees").map { element -> tree(element.jsonObject, classes.size) }
            if (trees.isEmpty()) throw IllegalArgumentException("The room model has no trees.")
            return RoomForest(features, classes, trees)
        }

        private fun tree(node: JsonObject, classCount: Int): Tree {
            val feature = node.array("feature").ints()
            val nodes = feature.size
            if (nodes == 0) throw IllegalArgumentException("A tree in the room model is empty.")
            val threshold = node.array("threshold").doubles()
            val left = node.array("left").ints()
            val right = node.array("right").ints()
            val counts = node.array("counts").map { row -> row.jsonArray.doubles() }
            if (threshold.size != nodes || left.size != nodes || right.size != nodes || counts.size != nodes) {
                throw IllegalArgumentException("A tree in the room model has uneven node arrays.")
            }
            if (counts.any { it.size != classCount }) {
                throw IllegalArgumentException("A tree in the room model has the wrong number of room counts.")
            }
            return Tree(feature, threshold, left, right, counts.toTypedArray())
        }

        private fun JsonObject.text(name: String): String {
            val value = this[name] ?: throw IllegalArgumentException("The room model is missing $name.")
            return value.jsonPrimitive.content
        }

        private fun JsonObject.array(name: String): JsonArray {
            val value = this[name] ?: throw IllegalArgumentException("The room model is missing $name.")
            return value.jsonArray
        }

        private fun JsonArray.ints(): IntArray = IntArray(size) { index -> this[index].jsonPrimitive.int }

        private fun JsonArray.doubles(): DoubleArray = DoubleArray(size) { index -> this[index].jsonPrimitive.double }
    }
}
