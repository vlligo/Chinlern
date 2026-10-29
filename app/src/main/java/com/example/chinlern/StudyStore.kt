package com.example.chinlern

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StudyStore(context: Context, userId: String? = null) {
    val syncAccess = SyncAccess(context)
    private val prefs = profilePreferences(context, userId)
    private val packs = JSONObject(prefs.getString("packs", "{}")!!)
    var onSyncDataChanged: (() -> Unit)? = null
    private var lastSyncSnapshot = ""
    var syncBase: Map<String, String> = try { SyncSnapshot.decode(prefs.getString("syncBase", "{}")!!) } catch (_: Exception) { emptyMap() }
        private set
    var syncRevision: String? = prefs.getString("syncRevision", null)
        private set
    var lastSyncedAt: Long = prefs.getLong("lastSyncedAt", 0)
        private set
    var activePackId = prefs.getString("activePack", "default")!!
        private set
    val packNames: List<Pair<String, String>> get() = packs.keys().asSequence().map { it to packs.getJSONObject(it).getString("name") }.toList()
    val packName: String get() = packs.getJSONObject(activePackId).getString("name")
    private var mixed: MixedStudy? = null
    private var mixedPrevious = parseTask(prefs.getString("mixedPrevious", null))
    val practicePackIds = (prefs.getStringSet("practicePacks", null) ?: setOf(activePackId)).toMutableSet()
    val studyEngine: LearningEngine get() = mixedStudy()?.engine ?: engine
    val studyWords: List<Word> get() = mixedStudy()?.words ?: words.filter { it.id in selected }
    val practiceLabel: String get() = packNames.filter { it.first in practicePackIds }.joinToString(", ") { it.second }
    val engine = LearningEngine()
    var words: MutableList<Word> = mutableListOf()
    val selected = mutableSetOf<String>()
    var audioEnabled = prefs.getBoolean("audio", true)
    var audioProvider = prefs.getString("provider", "system")!!
    var speakAfterAnswer = prefs.getBoolean("speakAfterAnswer", true)
    var slowAudio = prefs.getBoolean("slow", false)
    var writing = prefs.getBoolean("writing", false)
    var route = prefs.getString("route", "learn")!!
    var current: Task? = null
    var feedback: Boolean? = null
    var revealed = false
    var draft = ""
    var toneDraft = mutableListOf<Int>()
    var choices = emptyList<String>()
    var beforeAnswer: Progress? = null
    var answerIgnored = false
        private set
    private var answerUndo: JSONObject? = null
    val canIgnoreAnswer: Boolean get() = feedback != null && !answerIgnored && answerUndo != null
    var strokes = ""

    init {
        if (!packs.has(activePackId)) packs.put(activePackId, JSONObject().put("name", "Мои слова"))
        words = try { decodeWords(prefs.getString("words", null) ?: seedJson).toMutableList() }
        catch (_: Exception) { decodeWords(seedJson).toMutableList() }
        selected.addAll(prefs.getStringSet("selected", words.map { it.id }.toSet()) ?: emptySet())
        selected.retainAll(words.map { it.id }.toSet())
        practicePackIds.retainAll(packNames.map { it.first }.toSet())
        if (practicePackIds.isEmpty()) practicePackIds.add(activePackId)
        try {
            val saved = JSONObject(prefs.getString("progress", "{}")!!)
            saved.keys().forEach { key -> engine.progress[key] = readProgress(saved.getJSONObject(key)) }
            engine.step = prefs.getInt("step", 0)
            engine.previous = parseTask(prefs.getString("previous", null))
            current = parseTask(prefs.getString("current", null))?.takeIf { t -> studyWords.any { it.id == t.wordId } }
            feedback = when (prefs.getInt("feedback", -1)) { 1 -> true; 0 -> false; else -> null }
            revealed = prefs.getBoolean("revealed", false)
            draft = prefs.getString("draft", "")!!
            toneDraft = prefs.getString("tones", "")!!.mapNotNull { it.digitToIntOrNull() }.toMutableList()
            choices = JSONArray(prefs.getString("choices", "[]")!!).strings()
            prefs.getString("before", null)?.let { beforeAnswer = readProgress(JSONObject(it)) }
            strokes = prefs.getString("strokes", "")!!
            answerIgnored = prefs.getBoolean("answerIgnored", false)
            answerUndo = prefs.getString("answerUndo", null)?.let { JSONObject(it) }
            if (current == null || feedback == null) { answerUndo = null; answerIgnored = false }
        } catch (_: Exception) { current = null; feedback = null; draft = "" }
    }
    private fun mixedStudy(): MixedStudy? {
        if (practicePackIds == setOf(activePackId)) return null
        if (mixed == null) {
            val sources = packNames.filter { it.first in practicePackIds }.map { (id, _) ->
                if (id == activePackId) MixedStudy.Source(id, words.toList(), selected.toSet(), engine.progress.mapValues { it.value.copy() }, engine.step)
                else {
                    val pack = packs.getJSONObject(id)
                    val progress = pack.getJSONObject("progress")
                    MixedStudy.Source(id, decodeWords(pack.getString("words")), pack.getJSONArray("selected").strings().toSet(),
                        progress.keys().asSequence().associateWith { readProgress(progress.getJSONObject(it)) }, pack.optInt("step"))
                }
            }
            mixed = MixedStudy(sources).also { it.engine.previous = mixedPrevious }
        }
        return mixed
    }
    private fun flushMixed() {
        mixed?.let { session -> session.sources.forEach { source ->
            val progress = session.progressFor(source)
            val step = source.step + session.engine.step
            if (source.id == activePackId) {
                engine.progress.clear(); engine.progress.putAll(progress); engine.step = step
            } else if (packs.has(source.id)) {
                val json = JSONObject()
                progress.filterValues { it != Progress() }.forEach { (key, value) -> json.put(key, writeProgress(value)) }
                packs.getJSONObject(source.id).put("progress", json).put("step", step)
            }
        } }
    }
    fun choosePracticePacks(ids: Set<String>) {
        require(ids.isNotEmpty() && ids.all { packs.has(it) }) { "Выберите хотя бы один набор." }
        save(); mixed = null; mixedPrevious = null; practicePackIds.clear(); practicePackIds.addAll(ids)
        clearQuestion(); save()
    }
    data class LibraryPack(val id: String, val name: String, val words: List<Word>, val selected: Set<String>)

    fun libraryPacks(): List<LibraryPack> {
        save()
        return packNames.filter { it.first in practicePackIds }.map { (id, name) ->
            val pack = packs.getJSONObject(id)
            LibraryPack(id, name, decodeWords(pack.getString("words")), pack.getJSONArray("selected").strings().toSet())
        }
    }

    fun selectLibraryWord(packId: String, wordId: String, checked: Boolean) {
        require(packId in practicePackIds && packs.has(packId))
        invalidatePractice()
        if (packId == activePackId) {
            require(words.any { it.id == wordId })
            if (checked) selected.add(wordId) else selected.remove(wordId)
        } else {
            val pack = packs.getJSONObject(packId)
            require(decodeWords(pack.getString("words")).any { it.id == wordId })
            val selection = pack.getJSONArray("selected").strings().toMutableSet()
            if (checked) selection.add(wordId) else selection.remove(wordId)
            pack.put("selected", JSONArray(selection.toList()))
        }
        save()
    }

    fun selectLibraryWords(checked: Boolean) {
        invalidatePractice(); save()
        practicePackIds.forEach { id ->
            if (id == activePackId) {
                selected.clear()
                if (checked) selected.addAll(words.map { it.id })
            } else {
                val pack = packs.getJSONObject(id)
                pack.put("selected", JSONArray(if (checked) decodeWords(pack.getString("words")).map { it.id } else emptyList<String>()))
            }
        }
        save()
    }

    /** Change the editing target without changing the user's multi-pack selection or page. */
    fun editPack(id: String) {
        save()
        val chosen = practicePackIds.toSet()
        val previousRoute = route
        switchPack(id)
        practicePackIds.clear(); practicePackIds.addAll(chosen)
        mixed = null; route = previousRoute; save()
    }

    fun invalidatePractice() { flushMixed(); mixed = null; mixedPrevious = null; clearQuestion() }
    fun addStudySynonym(word: Word, meaning: String) {
        val updated = word.copy(meanings = (word.meanings + meaning).distinct())
        val session = mixed
        if (session == null) words[words.indexOfFirst { it.id == word.id }] = updated
        else {
            val origin = session.origins.getValue(word.id)
            session.words[session.words.indexOfFirst { it.id == word.id }] = updated
            if (origin.packId == activePackId) words[words.indexOfFirst { it.id == origin.wordId }] = updated.copy(id = origin.wordId)
            else {
                val pack = packs.getJSONObject(origin.packId)
                pack.put("words", encodeWords(decodeWords(pack.getString("words")).map { if (it.id == origin.wordId) updated.copy(id = origin.wordId) else it }))
            }
        }
    }
    /** Capture persistent pack coordinates, so rollback also works after mixed queues rebase on reload. */
    fun recordAnswer(correct: Boolean) {
        val task = current ?: return
        if (feedback != null) return
        val session = mixedStudy()
        save()
        val origin = session?.origins?.getValue(task.wordId)
        val packId = origin?.packId ?: activePackId
        val key = Task(origin?.wordId ?: task.wordId, task.skill).key
        val savedProgress = packs.getJSONObject(packId).getJSONObject("progress")
        val steps = JSONObject()
        (if (session == null) setOf(activePackId) else practicePackIds).forEach {
            steps.put(it, packs.getJSONObject(it).getInt("step"))
        }
        answerUndo = JSONObject().put("pack", packId).put("key", key)
            .put("progress", savedProgress.optJSONObject(key)?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("steps", steps).put("previous", studyEngine.previous?.key ?: JSONObject.NULL)
            .put("activePrevious", engine.previous?.key ?: JSONObject.NULL)
        beforeAnswer = studyEngine.state(task).copy()
        studyEngine.answer(task, correct)
        feedback = correct; answerIgnored = false
        save()
    }

    fun ignoreAnswer(): Boolean {
        if (!canIgnoreAnswer) return false
        val undo = answerUndo ?: return false
        save()
        // Restore only the scored skill and queue counters; vocabulary and other skills stay intact.
        mixed = null
        val target = packs.getJSONObject(undo.getString("pack")).getJSONObject("progress")
        val key = undo.getString("key")
        if (undo.isNull("progress")) target.remove(key)
        else target.put(key, JSONObject(undo.getJSONObject("progress").toString()))
        val steps = undo.getJSONObject("steps")
        steps.keys().forEach { id -> packs.getJSONObject(id).put("step", steps.getInt(id)) }
        val active = packs.getJSONObject(activePackId)
        engine.progress.clear()
        val progress = active.getJSONObject("progress")
        progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
        engine.step = active.getInt("step")
        engine.previous = if (undo.isNull("activePrevious")) null else parseTask(undo.getString("activePrevious"))
        mixedPrevious = if (undo.isNull("previous")) null else parseTask(undo.getString("previous"))
        answerIgnored = true; answerUndo = null; beforeAnswer = null
        save()
        // Keep the revealed answer visible, but it can no longer be scored a second time.
        return true
    }

    fun correctAnswer(): Boolean {
        if (!ignoreAnswer()) return false
        feedback = null
        recordAnswer(true)
        return true
    }

    fun tasks() = studyEngine.tasks(studyWords, writing, audioEnabled)
    fun clearQuestion() { current = null; feedback = null; revealed = false; draft = ""; toneDraft.clear(); choices = emptyList(); beforeAnswer = null; answerUndo = null; answerIgnored = false; strokes = "" }
    fun nextQuestion() {
        clearQuestion()
        current = studyEngine.next(tasks())
        current?.let { task -> choices = studyEngine.options(studyWords.first { it.id == task.wordId }) }
        save()
    }
    fun save() {
        mixed?.let { mixedPrevious = it.engine.previous }
        flushMixed()
        val progressJson = JSONObject()
        engine.progress.filterValues { it != Progress() }.forEach { (key, p) -> progressJson.put(key, writeProgress(p)) }
        packs.put(activePackId, JSONObject().put("name", packName).put("words", encodeWords(words))
            .put("selected", JSONArray(selected.toList())).put("progress", progressJson).put("step", engine.step))
        prefs.edit().putStringSet("practicePacks", practicePackIds.toSet()).putString("packs", packs.toString()).putString("activePack", activePackId).putString("words", encodeWords(words)).putStringSet("selected", selected.toSet())
            .putString("progress", progressJson.toString()).putInt("step", engine.step)
            .putString("previous", engine.previous?.key).putString("mixedPrevious", mixedPrevious?.key).putBoolean("audio", audioEnabled)
            .putBoolean("speakAfterAnswer", speakAfterAnswer).putString("provider", audioProvider).putBoolean("slow", slowAudio).putBoolean("writing", writing)
            .putString("route", route).putString("current", current?.key)
            .putInt("feedback", feedback?.let { if (it) 1 else 0 } ?: -1)
            .putBoolean("revealed", revealed).putString("draft", draft)
            .putString("tones", toneDraft.joinToString("")).putString("choices", JSONArray(choices).toString())
            .putString("before", beforeAnswer?.let { writeProgress(it).toString() })
            .putString("answerUndo", answerUndo?.toString()).putBoolean("answerIgnored", answerIgnored)
            .putString("strokes", strokes)
            .putString("syncBase", SyncSnapshot.encode(syncBase)).putString("syncRevision", syncRevision)
            .putLong("lastSyncedAt", lastSyncedAt).apply()
        val snapshot = SyncSnapshot.encode(snapshotFromPacks())
        if (snapshot != lastSyncSnapshot) { lastSyncSnapshot = snapshot; onSyncDataChanged?.invoke() }
    }

    private fun snapshotFromPacks(): Map<String, String> = packs.keys().asSequence().associateWith { SyncSnapshot.pack(packs.getJSONObject(it)) }

    fun syncSnapshot(): Map<String, String> { save(); return snapshotFromPacks() }

    /** Apply only after a successful server transaction; baseline and local state persist together. */
    fun applySync(pending: Map<String, String>, cloud: Map<String, String>, revision: String): Int {
        val current = syncSnapshot()
        val merged = SyncSnapshot.merge(pending, current, cloud)
        validateSyncPacks(merged.packs)
        val oldActive = current[activePackId]
        val mixedChanged = practicePackIds.any { current[it] != merged.packs[it] }
        if (mixedChanged || oldActive != merged.packs[activePackId]) mixed = null
        packs.keys().asSequence().toList().forEach { packs.remove(it) }
        merged.packs.forEach { (id, data) -> packs.put(id, JSONObject(data)) }
        if (!packs.has(activePackId)) activePackId = merged.packs.keys.sorted().first()
        if (oldActive != merged.packs[activePackId]) {
            val pack = packs.getJSONObject(activePackId)
            words = decodeWords(pack.getString("words")).toMutableList()
            selected.clear(); selected.addAll(pack.getJSONArray("selected").strings())
            engine.progress.clear()
            val progress = pack.getJSONObject("progress")
            progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
            engine.step = pack.getInt("step"); engine.previous = null
            clearQuestion()
            if (route == "quiz") route = "learn"
        }
        practicePackIds.retainAll(merged.packs.keys)
        if (practicePackIds.isEmpty()) practicePackIds.add(activePackId)
        if (mixedChanged) { mixedPrevious = null; clearQuestion(); if (route == "quiz") route = "learn" }
        syncBase = cloud.toMap(); syncRevision = revision; lastSyncedAt = System.currentTimeMillis()
        save()
        return merged.conflicts
    }
    fun switchPack(id: String) {
        require(packs.has(id)) { "Набор не найден." }
        if (id == activePackId) return
        save()
        mixed = null; mixedPrevious = null
        if (practicePackIds.size == 1) { practicePackIds.clear(); practicePackIds.add(id) }
        val pack = packs.getJSONObject(id)
        val incoming = decodeWords(pack.getString("words"))
        activePackId = id
        words = incoming.toMutableList()
        selected.clear(); selected.addAll(pack.getJSONArray("selected").strings())
        engine.progress.clear()
        val progress = pack.getJSONObject("progress")
        progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
        engine.step = pack.optInt("step"); engine.previous = null
        clearQuestion(); route = "learn"; save()
    }
    fun importPack(name: String, incoming: List<Word>) {
        require(name.trim().length in 1..80) { "Название должно содержать от 1 до 80 знаков." }
        require(incoming.size in 1..2000) { "Добавьте от 1 до 2000 слов." }
        incoming.forEach { validate(it) }
        require(incoming.map { it.id }.distinct().size == incoming.size && incoming.map { it.hanzi }.distinct().size == incoming.size) { "В наборе есть повторяющиеся слова или id." }
        save()
        val id = java.util.UUID.randomUUID().toString()
        packs.put(id, JSONObject().put("name", name.trim()).put("words", encodeWords(incoming))
            .put("selected", JSONArray(incoming.map { it.id })).put("progress", JSONObject()).put("step", 0))
        switchPack(id)
    }
    fun renamePack(name: String) {
        require(name.trim().length in 1..80) { "Название должно содержать от 1 до 80 знаков." }
        packs.getJSONObject(activePackId).put("name", name.trim()); save()
    }
    fun deletePack() {
        require(packs.length() > 1) { "Нельзя удалить последний набор." }
        val old = activePackId
        switchPack(packNames.first { it.first != old }.first)
        mixed = null; packs.remove(old); practicePackIds.remove(old)
        if (practicePackIds.isEmpty()) practicePackIds.add(activePackId)
        save()
    }
    fun exportBackup(): String = LibraryBackup.encode(LibraryBackup.Data(syncSnapshot(), practicePackIds.toSet()))

    fun inspectBackup(text: String): LibraryBackup.Data = LibraryBackup.decode(text).also {
        validateSyncPacks(it.packs)
    }

    fun previewBackup(data: LibraryBackup.Data): LibraryBackup.Merge {
        validateSyncPacks(data.packs)
        return LibraryBackup.merge(syncSnapshot(), data.packs).also { validateSyncPacks(it.packs) }
    }

    fun importBackup(data: LibraryBackup.Data): LibraryBackup.Merge {
        val merged = previewBackup(data) // All validation completes before changing persistent data.
        val current = syncSnapshot()
        if (merged.packs == current) {
            val selection = practicePackIds + data.practicePacks.filter { it in merged.packs }
            if (selection != practicePackIds) choosePracticePacks(selection)
            return merged
        }
        mixed = null; mixedPrevious = null
        packs.keys().asSequence().toList().forEach { packs.remove(it) }
        merged.packs.forEach { (id, text) -> packs.put(id, JSONObject(text)) }
        val active = packs.getJSONObject(activePackId)
        words = decodeWords(active.getString("words")).toMutableList()
        selected.clear(); selected.addAll(active.getJSONArray("selected").strings())
        engine.progress.clear()
        val progress = active.getJSONObject("progress")
        progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
        engine.step = active.getInt("step"); engine.previous = null
        practicePackIds.addAll(data.practicePacks.filter { it in merged.packs })
        clearQuestion()
        if (route == "quiz") route = "learn"
        // Cloud baseline and authentication remain untouched; this is an ordinary local edit.
        save()
        return merged
    }

    fun exportPack(): String = JSONObject(encodeWords(words)).put("name", packName).toString(2)
    fun saveDraft() {
        prefs.edit().putString("draft", draft).putString("strokes", strokes).apply()
    }
    fun replaceWord(word: Word) = replaceWords(listOf(word))
    fun replaceWords(incoming: List<Word>) {
        invalidatePractice()
        incoming.forEach { validate(it) }
        val replacements = incoming.associateBy { it.id }
        val oldIds = words.map { it.id }.toSet()
        words = (words.map { replacements[it.id] ?: it } + incoming.filter { it.id !in oldIds }).toMutableList()
        selected.addAll(replacements.keys)
        engine.progress.keys.removeAll { key -> key.substringBefore(':') in replacements }
        clearQuestion()
        save()
    }
    companion object {
        private fun profilePreferences(context: Context, userId: String?): android.content.SharedPreferences {
            val guest = context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE)
            if (userId == null) return guest
            val profile = context.getSharedPreferences("chinlearn_user_$userId", Context.MODE_PRIVATE)
            val registry = context.getSharedPreferences("chinlearn_profiles", Context.MODE_PRIVATE)
            // Existing local data joins the first account only. Other accounts have isolated stores.
            if (!profile.contains("packs") && !registry.contains("guestTransferredTo")) {
                val editor = profile.edit()
                guest.all.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(editor.commit()) { "Не удалось перенести локальные данные." }
                check(registry.edit().putString("guestTransferredTo", userId).commit()) { "Не удалось сохранить профиль." }
            }
            return profile
        }

        fun validateSyncPacks(packs: Map<String, String>) {
            require(packs.isNotEmpty()) { "Облачная библиотека пуста." }
            packs.forEach { (id, text) ->
                require(id.isNotBlank() && id.length <= 100) { "Некорректный идентификатор набора." }
                val pack = JSONObject(text)
                require(pack.getString("name").trim().length in 1..80) { "Некорректное название набора." }
                val words = decodeWords(pack.getString("words"))
                val ids = words.map { it.id }.toSet()
                require(pack.getJSONArray("selected").strings().all { it in ids }) { "Некорректный выбор слов." }
                require(pack.getInt("step") >= 0) { "Некорректный шаг обучения." }
                val progress = pack.getJSONObject("progress")
                progress.keys().forEach { key ->
                    val task = parseTask(key)
                    require(task != null && task.wordId in ids) { "Некорректный навык." }
                    val p = readProgress(progress.getJSONObject(key))
                    require(p.attempts >= 0 && p.errors in 0..p.attempts && p.streak in 0..p.attempts &&
                        p.nextAt >= 0 && p.due >= 0 && p.interval in 0..30) { "Некорректный прогресс." }
                }
            }
        }

        private fun parseTask(key: String?): Task? {
            if (key == null) return null
            val parts = key.split(':')
            return if (parts.size == 2) Task(parts[0], Skill.valueOf(parts[1])) else null
        }
        private fun readProgress(j: JSONObject) = Progress(j.optInt("attempts"), j.optInt("errors"), j.optInt("streak"), j.optBoolean("mastered"), j.optInt("nextAt"), j.optLong("due"), j.optInt("interval"))
        private fun writeProgress(p: Progress) = JSONObject().put("attempts", p.attempts).put("errors", p.errors).put("streak", p.streak).put("mastered", p.mastered).put("nextAt", p.nextAt).put("due", p.due).put("interval", p.interval)
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun decodeWords(text: String): List<Word> {
            val arr = if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("words")
            require(arr.length() in 1..2000) { "В файле должно быть от 1 до 2000 слов." }
            val result = (0 until arr.length()).map { index ->
                try {
                val j = arr.getJSONObject(index)
                val word = Word(j.optString("id", j.getString("hanzi")), j.getString("hanzi").trim(),
                    j.getString("pinyin").trim(), j.optString("numbered", j.getString("pinyin")).trim(), j.getJSONArray("meanings").strings().map { it.trim() }.filter { it.isNotEmpty() },
                    (j.optJSONArray("distractors")?.strings() ?: ((0 until arr.length()).map { arr.getJSONObject(it).getString("hanzi") } + listOf("水", "火", "木", "人", "口", "日", "月", "山"))).map { it.trim() }.filter { it.isNotEmpty() && it != j.getString("hanzi") && it !in (j.optJSONArray("alternatives")?.strings() ?: emptyList()) }.distinct().take(5), j.optString("hint"),
                    j.optJSONArray("alternatives")?.strings()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList())
                validate(word)
                word
                } catch (e: Exception) { throw IllegalArgumentException("Карточка ${index + 1}: ${e.message ?: "проверьте поля"}", e) }
            }
            require(result.map { it.id }.distinct().size == result.size) { "У слов должны быть уникальные id." }
            require(result.map { it.hanzi }.distinct().size == result.size) { "В файле есть повторяющиеся иероглифы." }
            return result
        }
        fun validate(w: Word) {
            require(w.id.isNotBlank() && ':' !in w.id && w.id.length <= 100) { "Некорректный id." }
            require(w.hanzi.isNotBlank() && w.hanzi.length <= 30) { "Введите иероглифы (до 30 знаков)." }
            require(w.alternatives.size <= 30 && w.alternatives.all { it.isNotBlank() && it.length <= 30 }) { "Допустимо до 30 вариантов, каждый от 1 до 30 знаков." }
            require(w.meanings.isNotEmpty()) { "Добавьте хотя бы один перевод." }
            require(w.meanings.all { it.length <= 200 } && w.hint.length <= 2000) { "Слишком длинный перевод или подсказка." }
            require(Answers.pinyinMatches(w.pinyin, w.numbered)) { "Пиньинь и запись с цифрами должны совпадать. Пример: shuǐ / shui3." }
            require(w.distractors.filter { it != w.hanzi && it !in w.alternatives }.distinct().size >= 3) { "Добавьте минимум 3 разных неверных варианта (без допустимых альтернатив)." }
        }
        fun encodeWords(words: List<Word>): String = JSONObject().put("version", 1).put("words", JSONArray(words.map { w ->
            JSONObject().put("id", w.id).put("hanzi", w.hanzi).put("pinyin", w.pinyin).put("numbered", w.numbered)
                .put("meanings", JSONArray(w.meanings)).put("distractors", JSONArray(w.distractors))
                .put("hint", w.hint).put("alternatives", JSONArray(w.alternatives))
        })).toString(2)
        val seedJson = """[
          {"id":"water","hanzi":"水","pinyin":"shuǐ","numbered":"shui3","meanings":["вода"],"distractors":["永","冰","泳","求"],"hint":"水 — вода. У 永 есть дополнительная точка сверху."},
          {"id":"fire","hanzi":"火","pinyin":"huǒ","numbered":"huo3","meanings":["огонь","пламя"],"distractors":["人","大","灭","犬"],"hint":"Две боковые черты напоминают языки пламени."},
          {"id":"tree","hanzi":"木","pinyin":"mù","numbered":"mu4","meanings":["дерево","древесина"],"distractors":["本","末","未","术"],"hint":"Ствол и ветви. У 本 есть дополнительная черта у корня."},
          {"id":"person","hanzi":"人","pinyin":"rén","numbered":"ren2","meanings":["человек","люди"],"distractors":["入","八","大","个"],"hint":"人 и 入 похожи: обращайте внимание на соединение черт сверху."},
          {"id":"mouth","hanzi":"口","pinyin":"kǒu","numbered":"kou3","meanings":["рот"],"distractors":["日","回","田","目"],"hint":"Внутри 口 нет черт."},
          {"id":"sun","hanzi":"日","pinyin":"rì","numbered":"ri4","meanings":["солнце","день"],"distractors":["目","曰","白","田"],"hint":"Одна горизонтальная черта внутри; у 目 их две."},
          {"id":"moon","hanzi":"月","pinyin":"yuè","numbered":"yue4","meanings":["луна","месяц"],"distractors":["目","用","日","且"],"hint":"月 означает и луну, и календарный месяц."},
          {"id":"mountain","hanzi":"山","pinyin":"shān","numbered":"shan1","meanings":["гора","горы"],"distractors":["出","仙","凶","屮"],"hint":"Три вершины, средняя выше остальных."},
          {"id":"big","hanzi":"大","pinyin":"dà","numbered":"da4","meanings":["большой","крупный","большая","большое"],"distractors":["太","天","犬","夫"],"hint":"Человек с раскинутыми руками. У 太 есть точка."},
          {"id":"small","hanzi":"小","pinyin":"xiǎo","numbered":"xiao3","meanings":["маленький","малый","небольшой","маленькая","маленькое"],"distractors":["少","水","木","尔"],"hint":"У 少 есть дополнительная наклонная черта снизу."},
          {"id":"woman","hanzi":"女","pinyin":"nǚ","numbered":"nü3","meanings":["женщина","женский"],"distractors":["文","母","安","汝"],"hint":"При вводе можно писать nǚ, nü3, nv3 или nu:3."},
          {"id":"child","hanzi":"子","pinyin":"zǐ","numbered":"zi3","meanings":["ребенок","дитя","сын"],"distractors":["孑","了","字","予"],"hint":"В 子 длинная горизонтальная черта пересекает середину."},
          {"id":"good","hanzi":"好","pinyin":"hǎo","numbered":"hao3","meanings":["хороший","хорошо","хорошая","хорошее"],"distractors":["妈","她","如","妹"],"hint":"В этой карточке 好 читается hǎo: хороший. Справа — 子."},
          {"id":"china","hanzi":"中国","pinyin":"Zhōngguó","numbered":"zhong1 guo2","meanings":["китай"],"distractors":["中园","串国","忠国","申国"],"hint":"中 — середина; 国 — страна. Используем упрощённое 国 (традиционное 國)."},
          {"id":"student","hanzi":"学生","pinyin":"xuésheng","numbered":"xue2 sheng5","meanings":["ученик","ученица","студент","студентка","учащийся"],"distractors":["学牛","字生","学王","宇生"],"hint":"В этом слове второй слог нейтральный: xuésheng. У 生 на одну черту больше, чем у 牛."},
          {"id":"hello","hanzi":"你好","pinyin":"nǐ hǎo","numbered":"ni3 hao3","meanings":["привет","здравствуйте","здравствуй"],"distractors":["你妤","他好","你妈","妳好"],"alternatives":["妳好"],"hint":"Словарные тоны: 3 + 3. В связной речи первый третий тон обычно звучит как второй."}
        ]"""
    }
}
