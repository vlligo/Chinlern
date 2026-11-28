package com.example.chinlern.data

import android.content.SharedPreferences
import org.json.JSONObject

/** Pack payloads retain the existing backup/sync wire format during the storage transition. */
interface LibraryRepository {
    fun read(): Map<String, String>
    fun replace(packs: Map<String, String>)
}

class PreferencesLibraryRepository(private val preferences: SharedPreferences) : LibraryRepository {
    fun readEncoded(): String = preferences.getString("packs", "{}")!!
    override fun read(): Map<String, String> = decode(readEncoded())
    override fun replace(packs: Map<String, String>) {
        check(stage(preferences.edit(), packs).commit()) { "Could not save library" }
    }
    /** Participate in StudyStore's existing atomic library/session/settings write. */
    fun stage(editor: SharedPreferences.Editor, packs: Map<String, String>): SharedPreferences.Editor =
        stageEncoded(editor, encode(packs))

    /** Reuse the facade’s serialized snapshot rather than encoding the entire library twice. */
    fun stageEncoded(editor: SharedPreferences.Editor, snapshot: String): SharedPreferences.Editor =
        editor.putString("packs", snapshot)

    companion object {
        fun decode(text: String): Map<String, String> {
            val root = JSONObject(text)
            return root.keys().asSequence().associateWith { root.getJSONObject(it).toString() }
        }
        fun encode(packs: Map<String, String>): String = JSONObject().apply {
            packs.forEach { (id, value) -> put(id, JSONObject(value)) }
        }.toString()
    }
}
