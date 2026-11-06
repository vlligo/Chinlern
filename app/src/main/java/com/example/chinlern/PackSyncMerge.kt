package com.example.chinlern

import org.json.JSONArray
import org.json.JSONObject

/** Three-way merge at word/skill granularity. The uploading edit wins a scalar tie. */
internal object PackSyncMerge {
    private fun same(a: Any?, b: Any?) = SyncSnapshot.canonical(a) == SyncSnapshot.canonical(b)
    private fun keys(json: JSONObject?) = json?.keys()?.asSequence()?.toSet().orEmpty()
    private fun strings(array: JSONArray?) = array?.let { (0 until it.length()).map(it::getString).toSet() }.orEmpty()

    private fun value(base: Any?, local: Any?, remote: Any?): Any? = when {
        same(local, remote) -> local
        same(local, base) -> remote
        same(remote, base) -> local
        local is JSONObject && remote is JSONObject -> fields(base as? JSONObject, local, remote)
        local is JSONArray && remote is JSONArray -> {
            val b = strings(base as? JSONArray); val l = strings(local); val r = strings(remote)
            JSONArray((l + r).filter { value(it in b, it in l, it in r) == true })
        }
        // An edit survives a concurrent deletion. Otherwise prefer the uploading value.
        else -> local ?: remote
    }

    private fun fields(base: JSONObject?, local: JSONObject, remote: JSONObject): JSONObject = JSONObject().apply {
        (keys(base) + keys(local) + keys(remote)).sorted().forEach { key ->
            value(base?.opt(key), local.opt(key), remote.opt(key))?.let { put(key, it) }
        }
    }

    private fun words(pack: JSONObject?): Map<String, JSONObject> {
        val array = pack?.let { JSONObject(it.getString("words")).getJSONArray("words") } ?: return emptyMap()
        return (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("id") }
    }

    private fun wordValue(base: JSONObject?, local: JSONObject?, remote: JSONObject?): JSONObject? {
        val merged = value(base, local, remote) as? JSONObject ?: return null
        if (local == null || remote == null || same(local, remote) || same(local, base) || same(remote, base)) return merged
        // Fields such as pinyin/numbered, example translations, and accepted/distractor
        // forms have joint constraints. Never publish a combination the editor rejects.
        return try {
            StudyStore.decodeWords(JSONArray(listOf(merged)).toString())
            merged
        } catch (_: IllegalArgumentException) {
            local
        }
    }

    private fun relative(progress: JSONObject?, step: Int): JSONObject? = progress?.let {
        JSONObject(it.toString()).put("nextAt", (it.optInt("nextAt").toLong() - step).coerceAtLeast(0))
    }

    private fun progress(base: JSONObject?, local: JSONObject?, remote: JSONObject?): JSONObject? {
        if (same(local, remote) || same(local, base) || same(remote, base))
            return value(base, local, remote) as? JSONObject
        if (local == null || remote == null) return local ?: remote
        // The memory model and its due date are one atomic scheduler snapshot.
        fun withoutSrs(json: JSONObject?) = json?.let { JSONObject(it.toString()).apply { remove("srs") } }
        val merged = fields(withoutSrs(base), withoutSrs(local)!!, withoutSrs(remote)!!)
        // Snapshots have no unique answer IDs. Maxima are retry-safe; adding counters would
        // count shared/offline answers again after a retry or an edit during upload.
        for (key in listOf("attempts", "errors")) merged.put(key, maxOf(local.optInt(key), remote.optInt(key)))
        val mastered = local.optBoolean("mastered") && remote.optBoolean("mastered")
        merged.put("mastered", mastered)
        merged.put("streak", if (mastered) minOf(local.optInt("streak"), remote.optInt("streak")) else 0)
        merged.put("interval", if (mastered) minOf(local.optInt("interval"), remote.optInt("interval")) else 0)
        merged.put("due", if (mastered) minOf(local.optLong("due"), remote.optLong("due")) else 0L)
        merged.put("nextAt", maxOf(local.optInt("nextAt"), remote.optInt("nextAt")))
        val lc = local.optJSONObject("confusions"); val rc = remote.optJSONObject("confusions")
        merged.put("confusions", JSONObject().apply {
            (keys(lc) + keys(rc)).sorted().forEach { put(it, maxOf(lc?.optInt(it) ?: 0, rc?.optInt(it) ?: 0)) }
        })
        val schedulerSnapshots = listOf(local, remote).filter { it.has("srs") }
        if (schedulerSnapshots.size == 2) {
            val chosen = schedulerSnapshots.maxWith(compareBy<JSONObject> { it.getJSONObject("srs").getLong("updatedAt") }
                .thenBy { it.getJSONObject("srs").getString("revision") })
            for (key in listOf("srs", "mastered", "streak", "interval", "due")) merged.put(key, chosen.get(key))
        } else if (schedulerSnapshots.size == 1) {
            // Old clients have no timestamps: use conservative legacy merging, then migrate on review.
            merged.remove("srs")
        }
        return merged
    }

    fun merge(baseText: String?, localText: String, remoteText: String): String {
        val base = baseText?.let(::JSONObject); val local = JSONObject(localText); val remote = JSONObject(remoteText)
        val bw = words(base); val lw = words(local); val rw = words(remote)
        val mergedWords = (lw.keys + rw.keys + bw.keys).mapNotNull { id -> wordValue(bw[id], lw[id], rw[id]) }
            // Concurrent additions can give the same written form different IDs. Keep the
            // uploading word once, so the merged pack remains valid and editable.
            .distinctBy { it.getString("hanzi") }
        val ids = mergedWords.map { it.getString("id") }.toSet()
        val selection = JSONObject()
        val bs = strings(base?.optJSONArray("selected")); val ls = strings(local.getJSONArray("selected")); val rs = strings(remote.getJSONArray("selected"))
        ids.forEach { selection.put(it, value(it in bs, it in ls, it in rs)) }
        val step = maxOf(local.getInt("step"), remote.getInt("step"))
        val bp = base?.optJSONObject("progress"); val lp = local.getJSONObject("progress"); val rp = remote.getJSONObject("progress")
        val mergedProgress = JSONObject()
        (keys(bp) + keys(lp) + keys(rp)).sorted().filter { it.substringBefore(':') in ids }.forEach { key ->
            progress(relative(bp?.optJSONObject(key), base?.optInt("step") ?: 0),
                relative(lp.optJSONObject(key), local.getInt("step")), relative(rp.optJSONObject(key), remote.getInt("step")))?.let {
                it.put("nextAt", (step.toLong() + it.optLong("nextAt")).coerceAtMost(Int.MAX_VALUE.toLong()))
                mergedProgress.put(key, it)
            }
        }
        return SyncSnapshot.pack(JSONObject().put("name", value(base?.opt("name"), local.get("name"), remote.get("name")))
            .put("words", JSONObject().put("version", 1).put("words", JSONArray(mergedWords)).toString())
            .put("selected", JSONArray(ids.filter { selection.getBoolean(it) }.sorted()))
            .put("progress", mergedProgress).put("step", step)
            .apply {
                if (listOfNotNull(base, local, remote).any { it.has("folder") })
                    put("folder", value(base?.optString("folder", "") ?: "", local.optString("folder", ""), remote.optString("folder", "")))
                if (listOfNotNull(base, local, remote).any { it.has("hidden") })
                    put("hidden", value(base?.optBoolean("hidden", false) ?: false, local.optBoolean("hidden", false), remote.optBoolean("hidden", false)))
            })
    }
}
