package com.seethrough.raw

import androidx.compose.runtime.mutableStateOf
import java.time.OffsetDateTime

/**
 * One row per access point, in discovery order. A sample stores the strength of each
 * access point visible at that moment. Upload pads every vector with zeros out to
 * the full row list.
 */
class WifiTrainingSet(
    private val now: () -> String = { OffsetDateTime.now().toString() },
) {
    val readout = mutableStateOf(TrainingReadout())
    var onCommit: () -> Unit = {}

    private val rows = ArrayList<AccessPointRecord>()
    private val rowOf = HashMap<String, Int>()
    private val samples = ArrayList<TrainingSample>()
    private val live = HashMap<String, Int>()
    private var lastRoom: Int? = null
    private var message = ""
    private var storedFile = ""

    fun restore(snapshot: TrainingSnapshot) {
        rows.clear()
        rowOf.clear()
        samples.clear()
        live.clear()
        snapshot.accessPoints.forEachIndexed { index, accessPoint ->
            rows.add(accessPoint)
            rowOf[accessPoint.bssid] = index
        }
        samples.addAll(snapshot.samples)
        publish()
    }

    fun observe(networks: List<WifiNetwork>) {
        val strongest = LinkedHashMap<String, WifiNetwork>()
        for (network in networks) {
            val bssid = canonicalBssid(network.bssid) ?: continue
            val previous = strongest[bssid]
            if (previous == null || network.signal > previous.signal) {
                strongest[bssid] = network
            }
        }
        var commit = false
        for ((bssid, network) in strongest) {
            val existing = rowOf[bssid]
            if (existing == null) {
                rowOf[bssid] = rows.size
                rows.add(AccessPointRecord(bssid, network.name))
                commit = true
            } else if (rows[existing].name != network.name) {
                rows[existing] = rows[existing].copy(name = network.name)
                commit = true
            }
        }
        live.clear()
        for ((bssid, network) in strongest) {
            live[bssid] = network.signal.coerceIn(1, 1000)
        }
        publish()
        if (commit) onCommit()
    }

    fun label(room: Int) {
        require(room in 1..5) { "Room must be from 1 to 5" }
        samples.add(TrainingSample(room, now(), LinkedHashMap(live)))
        lastRoom = room
        message = "Room $room saved · ${rows.size} signal values"
        publish()
        onCommit()
    }

    fun markStored(location: String) {
        storedFile = location
        message = if (location.contains('/') || location.contains('\\')) {
            "Stored at $location"
        } else {
            "Stored in Downloads as $location"
        }
        publish()
    }

    fun fail(text: String) {
        message = text
        publish()
    }

    fun snapshot(): TrainingSnapshot = TrainingSnapshot(rows.toList(), samples.toList())

    fun protocolJson(): String = TrainingProtocol.encode(snapshot())

    private fun publish() {
        val counts = IntArray(5)
        for (sample in samples) {
            if (sample.room in 1..5) counts[sample.room - 1]++
        }
        readout.value = TrainingReadout(
            rows = rows.mapIndexed { index, accessPoint ->
                TrainingRow(
                    index = index + 1,
                    name = accessPoint.name,
                    bssid = accessPoint.bssid,
                    signal = live[accessPoint.bssid],
                )
            },
            sampleCount = samples.size,
            roomCounts = counts.toList(),
            lastRoom = lastRoom,
            message = message,
            storedFile = storedFile,
        )
    }

}

object TrainingProtocol {
    const val ID = "seethrough-wifi-train-v1"

    fun vectors(snapshot: TrainingSnapshot): List<IntArray> {
        return snapshot.samples.map { sample ->
            IntArray(snapshot.accessPoints.size) { index ->
                sample.strengthByBssid[snapshot.accessPoints[index].bssid] ?: 0
            }
        }
    }

    fun encode(snapshot: TrainingSnapshot): String {
        val padded = vectors(snapshot)
        return buildString {
            append("{\n")
            append("  \"protocol\": ${jsonString(ID)},\n")
            append("  \"description\": ${jsonString(DESCRIPTION)},\n")
            append("  \"reader\": ${jsonString(READER)},\n")
            append("  \"signal\": {\n")
            append("    \"min\": 1,\n")
            append("    \"max\": 1000,\n")
            append("    \"absent\": 0,\n")
            append("    \"rssi_dbm\": ${jsonString(RSSI)}\n")
            append("  },\n")
            append("  \"access_points\": [\n")
            snapshot.accessPoints.forEachIndexed { index, accessPoint ->
                append("    {\"row\": ${index + 1}, \"bssid\": ${jsonString(accessPoint.bssid)}, \"name\": ${jsonString(accessPoint.name)}}")
                if (index != snapshot.accessPoints.lastIndex) append(",")
                append("\n")
            }
            append("  ],\n")
            append("  \"samples\": [\n")
            snapshot.samples.forEachIndexed { index, sample ->
                append("    {\"room\": ${sample.room}, \"captured_at\": ${jsonString(sample.capturedAt)}, \"vector\": ")
                appendVector(padded[index])
                append("}")
                if (index != snapshot.samples.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
    }

    fun decode(json: String): TrainingSnapshot {
        val root = JsonParser(json).parse().asObject()
        val accessPoints = root.array("access_points").map { item ->
            val accessPoint = item.asObject()
            AccessPointRecord(
                bssid = accessPoint.string("bssid"),
                name = accessPoint.string("name"),
            )
        }
        val samples = root.array("samples").map { item ->
            val sample = item.asObject()
            val strengths = LinkedHashMap<String, Int>()
            sample.array("vector").forEachIndexed { column, value ->
                val strength = value.asInt()
                if (strength != 0 && column < accessPoints.size) {
                    strengths[accessPoints[column].bssid] = strength
                }
            }
            TrainingSample(
                room = sample.int("room"),
                capturedAt = sample.string("captured_at"),
                strengthByBssid = strengths,
            )
        }
        return TrainingSnapshot(accessPoints, samples)
    }

    private fun StringBuilder.appendVector(vector: IntArray) {
        append("[")
        vector.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            append(value)
        }
        append("]")
    }
}

private sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Int) : JsonValue()

    fun asObject(): Obj = this as Obj
    fun asInt(): Int = (this as Num).value

    fun array(name: String): List<JsonValue> = (fields()[name] as Arr).items
    fun string(name: String): String = (fields()[name] as Str).value
    fun int(name: String): Int = (fields()[name] as Num).value

    private fun fields(): Map<String, JsonValue> = (this as Obj).fields
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = readValue()
        skip()
        return value
    }

    private fun readValue(): JsonValue {
        skip()
        return when (peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            else -> JsonValue.Num(readInt())
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skip()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skip()
            val key = readString()
            skip()
            expect(':')
            fields[key] = readValue()
            skip()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> error("Expected a comma or closing brace in the training file")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skip()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue())
            skip()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> error("Expected a comma or closing bracket in the training file")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        return buildString {
            while (true) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return@buildString
                    '\\' -> append(readEscape())
                    else -> append(ch)
                }
            }
        }
    }

    private fun readEscape(): Char {
        return when (val ch = text[index++]) {
            '\\', '"', '/' -> ch
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }
            else -> ch
        }
    }

    private fun readInt(): Int {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index].isDigit()) index++
        return text.substring(start, index).toInt()
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(): Char = text[index]

    private fun expect(ch: Char) {
        if (peek() != ch) error("Expected '$ch' in the training file")
        index++
    }
}

internal fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

private const val DESCRIPTION =
    "seethrough-wifi-train-v1. access_points is the row list in the order access points were discovered. " +
        "samples[n].vector[i] is the signal strength of access_points[i]. Every vector is the same length as access_points. " +
        "0 means that access point was not observed for that sample and was padded on upload. " +
        "A measured strength is an integer from 1 to 1000. room is an integer from 1 to 5. " +
        "captured_at is an ISO-8601 timestamp. Array order is the contract; do not reorder access_points without reordering every vector."

private const val RSSI =
    "Approximately -100 + (value - 1) / 999 * 70 dBm for values from 1 to 1000. 0 is padding, not a measurement."

private const val READER =
    "import json\n" +
        "doc = json.load(open(\"seethrough-wifi-train.json\", encoding=\"utf-8\"))\n" +
        "rooms = [sample[\"room\"] for sample in doc[\"samples\"]]\n" +
        "vectors = [sample[\"vector\"] for sample in doc[\"samples\"]]\n" +
        "names = [ap[\"bssid\"] for ap in doc[\"access_points\"]]\n"
