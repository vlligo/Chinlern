package com.example.chinlern.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.chinlern.StudyStore
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/** Staged storage implementation. Not yet the app's default: session cutover is a separate step. */
class SqliteLibraryRepository(context: Context, profileId: String?) : LibraryRepository, Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext,
        "library_${UUID.nameUUIDFromBytes((profileId?.let { "account:$it" } ?: "guest").toByteArray(Charsets.UTF_8))}.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE packs (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE vocabulary (id TEXT PRIMARY KEY NOT NULL, pack_id TEXT NOT NULL REFERENCES packs(id) ON DELETE CASCADE, legacy_word_id TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(pack_id, legacy_word_id))")
            db.execSQL("CREATE TABLE migration (id INTEGER PRIMARY KEY CHECK(id=1), source TEXT NOT NULL)")
        }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No destructive upgrade is permitted: $oldVersion -> $newVersion")
        }
    }
    override fun read(): Map<String, String> = helper.readableDatabase.query(
        "packs", arrayOf("id", "payload"), null, null, null, null, "id"
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) } }

    /** Atomic, restart-safe import; the exact source snapshot is retained before cutover.
     * Once imported, reopening never overwrites newer database data with stale preferences.
     */
    fun migrate(source: LibraryRepository): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (db.rawQuery("SELECT 1 FROM migration WHERE id=1", null).use { it.moveToFirst() }) return false
            check(db.rawQuery("SELECT 1 FROM packs LIMIT 1", null).use { !it.moveToFirst() }) { "Refusing to overwrite an existing library" }
            val packs = source.read()
            validate(packs)
            db.insertOrThrow("migration", null, ContentValues().apply {
                put("id", 1); put("source", PreferencesLibraryRepository.encode(packs))
            })
            write(db, packs)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }
    fun migrationBackup(): String? = helper.readableDatabase.rawQuery("SELECT source FROM migration WHERE id=1", null)
        .use { if (it.moveToFirst()) it.getString(0) else null }

    override fun replace(packs: Map<String, String>) {
        validate(packs)
        val db = helper.writableDatabase
        db.beginTransaction()
        try { write(db, packs); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun referenceIds(): Set<String> = helper.readableDatabase.rawQuery("SELECT id FROM vocabulary", null)
        .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun validate(packs: Map<String, String>) {
        if (packs.isNotEmpty()) StudyStore.validateSyncPacks(packs)
    }
    private fun write(db: SQLiteDatabase, packs: Map<String, String>) {
        db.delete("packs", null, null)
        packs.forEach { (packId, payload) ->
            db.insertOrThrow("packs", null, ContentValues().apply { put("id", packId); put("payload", payload) })
            val words = StudyStore.decodeWords(JSONObject(payload).getString("words"))
            words.forEach { word ->
                db.insertOrThrow("vocabulary", null, ContentValues().apply {
                    put("id", VocabularyReference(packId, word.id).stableId)
                    put("pack_id", packId); put("legacy_word_id", word.id)
                    put("payload", StudyStore.encodeWords(listOf(word)))
                })
            }
        }
    }
    override fun close() { helper.close() }
}
