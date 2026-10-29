package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded current state, not a permanent answer log. Pack strings are canonical JSON. */
object SyncSnapshot {
    const val VERSION = 1
    const val CHUNK_BYTES = 200_000
    const val MAX_CHUNKS = 40
    private const val MAX_EXPANDED_BYTES = 32_000_000

    data class Merge(val packs: Map<String, String>, val conflicts: Int)

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    fun encode(packs: Map<String, String>) = canonical(JSONObject(packs))
    fun decode(text: String): Map<String, String> {
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    fun pack(json: JSONObject): String {
        val copy = JSONObject(json.toString())
        val selected = copy.getJSONArray("selected")
        copy.put("selected", JSONArray((0 until selected.length()).map { selected.getString(it) }.sorted()))
        // Vocabulary is stored as an embedded JSON string by the existing local format.
        copy.put("words", canonical(JSONObject(copy.getString("words"))))
        return canonical(copy)
    }

    /** Three-way merge; concurrent edits to one pack preserve the local version as a copy. */
    fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>): Merge {
        val result = linkedMapOf<String, String>()
        val copies = linkedMapOf<String, String>()
        var conflicts = 0
        (base.keys + local.keys + remote.keys).sorted().forEach { id ->
            val b = base[id]; val l = local[id]; val r = remote[id]
            val chosen = when {
                l == r -> l
                l == b -> r
                r == b -> l
                else -> {
                    conflicts++
                    if (l != null && l != r) {
                        val digest = MessageDigest.getInstance("SHA-256")
                            .digest("$id\n$b\n$l\n$r".toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }.take(32)
                        val copy = JSONObject(l)
                        copy.put("name", copy.getString("name").take(59) + " (копия конфликта)")
                        copies["conflict-$digest"] = pack(copy)
                    }
                    r
                }
            }
            if (chosen != null) result[id] = chosen
        }
        result.putAll(copies)
        // Two devices can each delete a different last-but-one pack. Preserve a recovery copy.
        if (result.isEmpty() && local.isNotEmpty()) {
            val entry = local.entries.sortedBy { it.key }.first()
            result[entry.key] = entry.value
            conflicts++
        }
        return Merge(result, conflicts)
    }

    fun compress(packs: Map<String, String>): List<ByteArray> {
        val raw = encode(packs).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_EXPANDED_BYTES) { "Слишком много данных для синхронизации (максимум 32 МБ)." }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        val bytes = output.toByteArray()
        require(bytes.size <= CHUNK_BYTES * MAX_CHUNKS) { "Слишком много сжатых данных для синхронизации (максимум 8 МБ)." }
        return bytes.indices.step(CHUNK_BYTES).map { start -> bytes.copyOfRange(start, minOf(start + CHUNK_BYTES, bytes.size)) }
    }

    fun decompress(chunks: List<ByteArray>): Map<String, String> {
        require(chunks.size in 1..MAX_CHUNKS && chunks.all { it.size <= CHUNK_BYTES }) { "Некорректный размер облачных данных." }
        val compressed = ByteArrayOutputStream().apply { chunks.forEach { write(it) } }.toByteArray()
        val output = ByteArrayOutputStream()
        GZIPInputStream(compressed.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_EXPANDED_BYTES) { "Облачные данные превышают 32 МБ." }
                output.write(buffer, 0, count)
            }
        }
        return decode(output.toString("UTF-8"))
    }
}
