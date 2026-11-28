package com.example.chinlern.dictionary

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import com.example.chinlern.Answers
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.security.DigestInputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Replaceable public data only; never references a profile or learning store. Call off the UI thread. */
class OfflineDictionary(context: Context, val source: Source = Source.CEDICT) {
    private val context = context.applicationContext
    enum class Source(val id: String, val label: String, internal val asset: String, internal val schema: Int) {
        CEDICT("cedict", "CC-CEDICT · English", "dictionary/cedict.pack", 1),
        RU_WIKTIONARY("ruwiktionary", "Викисловарь · Русский", "dictionary/ruwiktionary/dictionary.pack", 2)
    }
    data class Entry(val traditional: String, val simplified: String, val pinyin: String, val meanings: List<String>,
        val article: String? = null)

    private val directoryPath = source.asset.substringBeforeLast('/')
    fun notice(): String = context.assets.open("$directoryPath/NOTICE.txt").bufferedReader().use { it.readText() }
    fun license(): String = context.assets.open("dictionary/LICENSE.txt").bufferedReader().use { it.readText() }
    private val manifest by lazy { context.assets.open("$directoryPath/manifest.json").bufferedReader().use { JSONObject(it.readText()) } }
    internal val version: String get() = manifest.getString("database_sha256")
    private val columns = "traditional,simplified,pinyin,gloss" + if (source == Source.RU_WIKTIONARY) ",article" else ""

    private fun database(): SQLiteDatabase {
        check(manifest.getInt("schema") == source.schema) { "Unsupported dictionary schema" }
        val hash = version
        val directory = File(context.noBackupFilesDir, "dictionary").apply { mkdirs() }
        val target = File(directory, "${source.id}-$hash.db")
        synchronized(installLock) {
            if (!target.exists() || target.length() != manifest.getLong("database_bytes")) {
                val temporary = File(directory, if (source == Source.CEDICT) "install.tmp" else "${source.id}.tmp")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(GZIPInputStream(context.assets.open(source.asset)), digest).use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Dictionary checksum mismatch" }
                    check(temporary.renameTo(target)) { "Dictionary installation failed" }
                } finally { temporary.delete() }
            }
        }
        return SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }
    fun exact(form: String): List<Entry> = database().use { exact(it, form) }

    internal fun exactForms(forms: Iterable<String>): List<Entry> = database().use { db ->
        forms.flatMap { exact(db, it) }.distinct()
    }

    private fun exact(db: SQLiteDatabase, form: String): List<Entry> =
        db.rawQuery(
            "SELECT $columns FROM entries WHERE simplified=? OR traditional=? ORDER BY id",
            arrayOf(form, form)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.entry(0))
            }
        }

    /** Indexed reads by initial Han character share a database handle for one segmentation pass. */
    internal fun <T> withReadings(read: ((String) -> Map<String, Set<String>>, (String) -> List<Entry>) -> T): T = database().use { db ->
        fun byInitial(prefix: String): Map<String, Set<String>> {
            val next = String(Character.toChars(prefix.codePointAt(0) + 1))
            return buildMap<String, MutableSet<String>> {
                for (column in listOf("simplified", "traditional")) {
                    db.rawQuery("SELECT DISTINCT $column,pinyin FROM entries WHERE $column>=? AND $column<?",
                        arrayOf(prefix, next)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val readings = getOrPut(cursor.getString(0)) { linkedSetOf() }
                            cursor.getString(1).takeIf(String::isNotBlank)?.let(readings::add)
                        }
                    }
                }
            }
        }
        read(::byInitial) { form -> exact(db, form) }
    }

    fun search(query: String): List<Entry> = search(query, null)

    internal fun search(query: String, cancellation: CancellationSignal?): List<Entry> {
        val trimmed = query.trim()
        val text = trimmed.substring(0, trimmed.offsetByCodePoints(0, minOf(120, trimmed.codePointCount(0, trimmed.length))))
        if (text.isEmpty()) return emptyList()
        cancellation?.throwIfCanceled()
        database().use { db ->
            val entries = linkedMapOf<Long, Entry>()
            fun collect(where: String, args: Array<String>, matchReading: Boolean = false): Boolean {
                var rowsFound = false
                val limit = if (matchReading) "" else " LIMIT 50"
                db.rawQuery("SELECT id,$columns FROM entries WHERE $where ORDER BY id$limit", args, cancellation).use { cursor ->
                    while (entries.size < 50 && cursor.moveToNext()) {
                        cancellation?.throwIfCanceled()
                        rowsFound = true
                        val reading = cursor.getString(3)
                        if (!matchReading || Answers.pinyinMatches(text, reading)) {
                            entries[cursor.getLong(0)] = cursor.entry(1)
                        }
                    }
                }
                return rowsFound
            }
            collect("simplified=? OR traditional=?", arrayOf(text, text))
            if (text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) {
                prefixLimit(text)?.let { end ->
                    if (entries.size < 50) collect("(simplified>? AND simplified<?) OR (traditional>? AND traditional<?)", arrayOf(text, end, text, end))
                }
            } else {
                val key = pinyinKey(text)
                val toned = hasExplicitTone(text)
                val pinyinFound = key.isNotEmpty() && entries.size < 50 &&
                    collect("pinyin_key=?", arrayOf(key), matchReading = toned)
                if ((!toned || (!pinyinFound && !numbered.matches(text))) && entries.size < 50) {
                    val tokens = if (source == Source.CEDICT) englishToken.findAll(text.lowercase(Locale.ROOT))
                        else russianToken.findAll(text.lowercase(Locale.ROOT).replace("ё", "е"))
                    val keys = tokens.map { it.value }.distinct().take(6).toList()
                    if (keys.isNotEmpty()) collect(keys.joinToString(" AND ") { "id IN (SELECT entry_id FROM definitions WHERE token=?)" }, keys.toTypedArray())
                }
            }
            return entries.values.take(50)
        }
    }

    private fun Cursor.entry(start: Int): Entry {
        val gloss = getString(start + 3)
        val meanings = if (source == Source.CEDICT) gloss.split('/')
            else JSONArray(gloss).let { values -> List(values.length(), values::getString) }
        return Entry(getString(start), getString(start + 1), getString(start + 2), meanings,
            if (source == Source.RU_WIKTIONARY) getString(start + 4) else null)
    }

    companion object {
        private val installLock = Any()
        private val englishToken = Regex("[a-z]+")
        private val russianToken = Regex("[а-я]+")
        private val numbered = Regex("(?:[a-züv:]+[1-5][\\s'’·-]*)+", RegexOption.IGNORE_CASE)
        internal fun hasExplicitTone(value: String) = numbered.matches(value) ||
            Normalizer.normalize(value, Normalizer.Form.NFD).any { it in "\u0300\u0301\u0304\u030c" }

        private fun prefixLimit(value: String): String? {
            var index = value.length
            while (index > 0) {
                val point = value.codePointBefore(index)
                index -= Character.charCount(point)
                if (point < Character.MAX_CODE_POINT) {
                    val next = if (point == 0xd7ff) 0xe000 else point + 1
                    return value.substring(0, index) + String(Character.toChars(next))
                }
            }
            return null
        }

        fun pinyinKey(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT).replace("u:", "ü"), Normalizer.Form.NFD)
            .replace("u\u0308", "v").filter { it.isLetter() && Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        internal fun wiktionaryCredit(title: String?): String = title?.let {
            "Русский Викисловарь · ${wiktionaryUrl(it)} · CC BY-SA 4.0"
        }.orEmpty()

        private fun wiktionaryUrl(title: String) = "https://ru.wiktionary.org/wiki/${Uri.encode(title)}"

        internal fun openWiktionaryArticle(context: Context, title: String): Boolean = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wiktionaryUrl(title))))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
