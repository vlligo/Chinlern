package com.example.chinlern.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** SQLite-backed compatibility boundary: a library and its session commit in one transaction.
 * Legacy preferences are retained untouched as recovery data, never dual-written.
 */
object ProfileStorage {
    fun open(context: Context, name: String): SharedPreferences = Store(context.applicationContext, name)

    // Keep the guest and recently used profiles warm across calls and Activity recreation.
    // Hold the cache lock during a read/write so eviction never closes an in-use database.
    private val connections = object : LruCache<String, SQLiteOpenHelper>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: SQLiteOpenHelper, newValue: SQLiteOpenHelper?) {
            oldValue.close()
        }
    }

    private fun newHelper(context: Context, file: String) = object : SQLiteOpenHelper(context, file, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE recovery (id INTEGER PRIMARY KEY CHECK(id=1), snapshot TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported profile migration: $oldVersion -> $newVersion")
        }
    }

    private class Store(private val context: Context, name: String) : SharedPreferences {
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private val file = "profile_${UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8))}.db"
        private val path = context.getDatabasePath(file).absolutePath

        private fun <T> withConnection(operation: (SQLiteOpenHelper) -> T): T = synchronized(connections) {
            val helper = connections.get(path) ?: newHelper(context, file).also { connections.put(path, it) }
            operation(helper)
        }

        init {
            withConnection { helper ->
                val db = helper.writableDatabase
                fun imported() = db.rawQuery("SELECT 1 FROM recovery WHERE id=1", null).use { it.moveToFirst() }
                if (!imported()) {
                    db.beginTransaction()
                    try {
                        if (!imported()) {
                            val source = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                            val backup = JSONObject()
                            source.forEach { (key, value) ->
                                if (value != null) {
                                    val encoded = encode(value)
                                    backup.put(key, JSONObject(encoded))
                                    put(db, key, encoded)
                                }
                            }
                            db.insertOrThrow("recovery", null, ContentValues().apply {
                                put("id", 1); put("snapshot", backup.toString())
                            })
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
        }
        override fun getAll(): MutableMap<String, *> = withConnection { helper ->
            val db = helper.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val keys = db.rawQuery("SELECT key FROM state", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val values = db.compileStatement(readValueSql).use { statement ->
                    keys.associateWith { key ->
                        statement.bindString(1, key)
                        decode(statement.simpleQueryForString())
                    }.toMutableMap()
                }
                db.setTransactionSuccessful()
                values
            } finally { db.endTransaction() }
        }

        // A profile value can exceed Android's CursorWindow limit. Scalar reads
        // return it directly and leave the existing database format intact.
        private val readValueSql = "SELECT COALESCE((SELECT value FROM state WHERE key=?), '')"

        private fun value(key: String): Any? = withConnection { helper ->
            helper.readableDatabase.compileStatement(readValueSql).use { statement ->
                statement.bindString(1, key)
                statement.simpleQueryForString().takeIf { it.isNotEmpty() }?.let(::decode)
            }
        }

        override fun contains(key: String): Boolean = withConnection { helper ->
            helper.readableDatabase.compileStatement("SELECT EXISTS(SELECT 1 FROM state WHERE key=?)").use {
                it.bindString(1, key)
                it.simpleQueryForLong() != 0L
            }
        }
        override fun getString(key: String, defValue: String?) = value(key)?.let { it as String } ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (value(key) as Set<String>?)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = value(key)?.let { it as Int } ?: defValue
        override fun getLong(key: String, defValue: Long) = value(key)?.let { it as Long } ?: defValue
        override fun getFloat(key: String, defValue: Float) = value(key)?.let { it as Float } ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = value(key)?.let { it as Boolean } ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
        override fun edit(): SharedPreferences.Editor = Editor()
        private inner class Editor : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean = synchronized(this@Store) {
                withConnection { helper ->
                    val db = helper.writableDatabase
                    db.beginTransaction()
                    try {
                        if (clear) db.delete("state", null, null)
                        changes.forEach { (key, value) ->
                            if (value == null) db.delete("state", "key=?", arrayOf(key)) else put(db, key, encode(value))
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                changes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@Store, key) } }
                true
            }
            // Durability is required before StudyStore advertises a saved review or a sync snapshot.
            override fun apply() { check(commit()) { "Could not commit profile" } }
        }
        private fun put(db: SQLiteDatabase, key: String, encoded: String) {
            db.insertWithOnConflict("state", null, ContentValues().apply { put("key", key); put("value", encoded) }, SQLiteDatabase.CONFLICT_REPLACE)
                .also { check(it != -1L) { "Could not persist profile key" } }
        }
        private fun encode(value: Any): String = JSONObject().apply {
            put("type", when (value) { is String -> "string"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Boolean -> "boolean"; is Set<*> -> "set"; else -> error("Unsupported value") })
            put("value", if (value is Set<*>) JSONArray(value.toList()) else value)
        }.toString()
        private fun decode(text: String): Any = JSONObject(text).let { json -> when (json.getString("type")) {
            "string" -> json.getString("value")
            "int" -> json.getInt("value")
            "long" -> json.getLong("value")
            "float" -> json.getDouble("value").toFloat()
            "boolean" -> json.getBoolean("value")
            "set" -> json.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> error("Unsupported persisted type")
        } }
    }
}
