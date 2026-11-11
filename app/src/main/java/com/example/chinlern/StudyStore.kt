package com.example.chinlern

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StudyStore(context: Context, userId: String? = null, private val clock: () -> Long = System::currentTimeMillis) {
    val syncAccess = SyncAccess(context, userId)
    private val prefs = profilePreferences(context, userId)
    private val packs = JSONObject(prefs.getString("packs", "{}")!!)
    var onSyncDataChanged: (() -> Unit)? = null
    private var lastSyncSnapshot = ""
    private var encodedVocabulary: Pair<List<Word>, String>? = null
    private var indexedVocabulary: Map<String, String>? = null
    private var wordIndex = emptyMap<SharedWords.Identity, List<MixedStudy.Origin>>()
    private val introductionKeys = mutableMapOf<SharedWords.Identity, String>()
    private val sharedOverrides = mutableMapOf<SharedWords.Identity, MutableMap<Skill, Progress?>>()

    private fun wordGroups(): Map<SharedWords.Identity, List<MixedStudy.Origin>> {
        val vocabulary = packs.keys().asSequence().associateWith { id ->
            if (id == activePackId) encodedWords() else packs.getJSONObject(id).getString("words")
        }
        if (vocabulary != indexedVocabulary) {
            wordIndex = vocabulary.flatMap { (id, text) ->
                decodedWords(text).map { SharedWords.identity(it) to MixedStudy.Origin(id, it.id) }
            }.groupBy({ it.first }, { it.second })
            indexedVocabulary = vocabulary
            introductionKeys.keys.retainAll(wordIndex.keys)
        }
        return wordIndex
    }

    private fun wordAt(origin: MixedStudy.Origin): Word =
        (if (origin.packId == activePackId) words else decodedWords(packs.getJSONObject(origin.packId).getString("words")))
            .first { it.id == origin.wordId }

    fun sharedPackNames(): Map<SharedWords.Identity, List<String>> = wordGroups().filterValues { it.size > 1 }
        .mapValues { (_, members) -> members.map { it.packId }.distinct().map { id ->
            packLabel(id, packs.getJSONObject(id).getString("name"))
        } }

    private fun stepFor(packId: String): Int = mixed?.let { session ->
        session.sources.firstOrNull { it.id == packId }?.let { it.step + session.engine.step }
    } ?: if (packId == activePackId) engine.step else packs.getJSONObject(packId).optInt("step")

    /** Read live shared results without writing preferences (also used by the overview). */
    private fun sharedProgress(): Map<MixedStudy.Origin, Map<Skill, Progress?>> {
        val groups = wordGroups().filterValues { it.size > 1 }
        if (groups.isEmpty()) return emptyMap()
        val live = mixed?.let { session -> session.sources.associate { it.id to session.progressFor(it) } }.orEmpty()
        return buildMap {
            groups.forEach { (identity, members) ->
                val combined = Skill.entries.associateWith { skill ->
                    val override = sharedOverrides[identity]
                    if (override?.containsKey(skill) == true) override[skill]
                    else SharedWords.merge(members.mapNotNull { origin ->
                        val key = Task(origin.wordId, skill).key
                        val p = live[origin.packId]?.get(key) ?: if (origin.packId in live) null
                        else if (origin.packId == activePackId) engine.progress[key]
                        else packs.getJSONObject(origin.packId).optJSONObject("progress")?.optJSONObject(key)?.let(::readProgress)
                        p?.copy(nextAt = (p.nextAt - stepFor(origin.packId)).coerceAtLeast(0))
                    })
                }
                members.forEach { origin -> put(origin, combined) }
            }
        }
    }

    private fun reconcileSharedProgress() {
        val shared = sharedProgress()
        shared.forEach { (origin, states) -> states.forEach { (skill, relative) ->
            val key = Task(origin.wordId, skill).key
            val target = if (origin.packId == activePackId) null else packs.getJSONObject(origin.packId).getJSONObject("progress")
            val previous = if (target == null) engine.progress[key] else target.optJSONObject(key)?.let(::readProgress)
            if (previous?.copy(nextAt = (previous.nextAt - stepFor(origin.packId)).coerceAtLeast(0)) != relative) {
                val updated = relative?.copy(nextAt = stepFor(origin.packId) + relative.nextAt)
                if (target == null) {
                    if (updated == null) engine.progress.remove(key) else engine.progress[key] = updated
                } else if (updated == null) target.remove(key) else target.put(key, writeProgress(updated))
            }
        } }
        mixed?.let { session -> session.origins.forEach { (id, origin) ->
            shared[origin]?.forEach { (skill, relative) ->
                val key = Task(id, skill).key
                if (relative == null) session.engine.progress.remove(key)
                else session.engine.progress[key] = relative.copy(nextAt = session.engine.step + relative.nextAt)
            }
        } }
        sharedOverrides.clear()
    }

    private fun shareResult(word: Word, skill: Skill, progress: Progress?, step: Int) {
        sharedOverrides.getOrPut(SharedWords.identity(word)) { mutableMapOf() }[skill] =
            progress?.takeIf { it.copy(nextAt = 0) != Progress() }?.copy(nextAt = (progress.nextAt - step).coerceAtLeast(0))
    }

    private fun progressAt(origin: MixedStudy.Origin, skill: Skill): Progress? {
        mixed?.let { session ->
            val id = session.occurrences.entries.firstOrNull { origin in it.value }?.key
            if (id != null) return session.engine.progress[Task(id, skill).key]?.let { p ->
                p.copy(nextAt = session.sources.first { it.id == origin.packId }.step + p.nextAt)
            }
        }
        val key = Task(origin.wordId, skill).key
        return if (origin.packId == activePackId) engine.progress[key]?.copy()
        else packs.getJSONObject(origin.packId).optJSONObject("progress")?.optJSONObject(key)?.let(::readProgress)
    }

    private fun encodedWords(): String {
        val cached = encodedVocabulary
        if (cached != null && cached.first == words) return cached.second
        return encodeWords(words).also { encodedVocabulary = words.toList() to it }
    }
    private val decodedVocabulary = object : LinkedHashMap<String, List<Word>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Word>>?) = size > 8
    }
    private fun decodedWords(text: String): List<Word> =
        decodedVocabulary.getOrPut(text) { decodeWords(text) }

    private var encodedSyncBase: Pair<Map<String, String>, String>? = null
    private fun syncBaseText(): String {
        val cached = encodedSyncBase
        if (cached != null && cached.first === syncBase) return cached.second
        return SyncSnapshot.encode(syncBase).also { encodedSyncBase = syncBase to it }
    }

    var syncBase: Map<String, String> = try { SyncSnapshot.decode(prefs.getString("syncBase", "{}")!!) } catch (_: Exception) { emptyMap() }
        private set
    var syncRevision: String? = prefs.getString("syncRevision", null)
        private set
    var lastSyncedAt: Long = prefs.getLong("lastSyncedAt", 0)
        private set
    var activePackId = prefs.getString("activePack", "default")!!
        private set
    val packNames: List<Pair<String, String>> get() = PackOrder.sort(packs.keys().asSequence().map { it to packs.getJSONObject(it).getString("name") }.toList())
    val packName: String get() = packs.getJSONObject(activePackId).getString("name")
    fun packFolder(id: String): String = packs.getJSONObject(id).optString("folder", "")
    fun isPackHidden(id: String): Boolean = packs.getJSONObject(id).optBoolean("hidden", false)
    val visiblePackNames get() = packNames.filterNot { isPackHidden(it.first) }
    val folderNames get() = FolderPaths.all(packNames.map { packFolder(it.first) })
    fun packLabel(id: String, name: String): String = packFolder(id).let { if (it.isEmpty()) name else "$it / $name" }

    fun movePack(id: String, folder: String) = movePacks(setOf(id), folder)
    fun movePacks(ids: Set<String>, folder: String) {
        require(ids.isNotEmpty() && ids.all { packs.has(it) }) { "Выберите существующие наборы." }
        FolderPaths.validate(folder.trim())
        ids.forEach { packs.getJSONObject(it).put("folder", folder.trim()) }
        save()
    }
    fun renameFolder(folder: String, name: String) {
        relocateFolder(folder, FolderPaths.child(FolderPaths.parent(folder), name))
    }
    fun moveFolder(folder: String, parent: String) {
        require(parent.isEmpty() || parent in folderNames) { "Папка назначения не найдена." }
        require(!FolderPaths.contains(folder, parent)) { "Нельзя поместить папку внутрь самой себя." }
        relocateFolder(folder, FolderPaths.child(parent, FolderPaths.title(folder)))
    }
    private fun relocateFolder(folder: String, target: String) {
        require(folder.isNotEmpty() && folder in folderNames) { "Папка не найдена." }
        if (folder == target) return
        require(target !in folderNames) { "В этом месте уже есть папка с таким названием." }
        val changes = packNames.filter { FolderPaths.contains(folder, packFolder(it.first)) }.associate { (id, _) ->
            id to FolderPaths.replace(packFolder(id), folder, target)
        }
        changes.values.forEach(FolderPaths::validate)
        changes.forEach { (id, path) -> packs.getJSONObject(id).put("folder", path) }
        save()
    }
    fun removeFolder(folder: String) {
        require(folder.isNotEmpty() && folder in folderNames) { "Папка не найдена." }
        val parent = FolderPaths.parent(folder)
        val outside = folderNames.filterNot { FolderPaths.contains(folder, it) }.toSet()
        val children = folderNames.filter { FolderPaths.parent(it) == folder }
        require(children.none { FolderPaths.child(parent, FolderPaths.title(it)) in outside }) {
            "Сначала переименуйте вложенную папку: в родительской папке уже есть такое название."
        }
        packNames.filter { FolderPaths.contains(folder, packFolder(it.first)) }.forEach { (id, _) ->
            val suffix = packFolder(id).removePrefix(folder).trimStart('/')
            packs.getJSONObject(id).put("folder", listOf(parent, suffix).filter { it.isNotEmpty() }.joinToString("/"))
        }
        save()
    }
    fun setPackHidden(id: String, hidden: Boolean) {
        require(!hidden || isPackHidden(id) || visiblePackNames.size > 1) { "Оставьте хотя бы один видимый набор." }
        save()
        packs.getJSONObject(id).put("hidden", hidden)
        if (hidden) {
            session = JSONObject(); mixed = null; mixedPrevious = null; clearQuestion()
        }
        save()
    }
    private fun normalizePracticePacks() {
        // Concurrent hides on different devices can otherwise hide the entire library.
        if (visiblePackNames.isEmpty()) packs.getJSONObject(packNames.first().first).put("hidden", false)
        val previous = practicePackIds.toSet()
        practicePackIds.retainAll(visiblePackNames.map { it.first }.toSet())
        if (practicePackIds.isEmpty()) practicePackIds.add(visiblePackNames.first().first)
        if (previous != practicePackIds) mixed = null
    }
    private var mixed: MixedStudy? = null
    private var mixedPrevious = parseTask(prefs.getString("mixedPrevious", null))
    val practicePackIds = (prefs.getStringSet("practicePacks", null) ?: setOf(activePackId)).toMutableSet()
    val studyEngine: LearningEngine get() = mixedStudy()?.engine ?: engine
    val selectedStudyWords: List<Word> get() = mixedStudy()?.words ?: words.filter { it.id in selected }
    val studyWords: List<Word> get() {
        mixedStudy()?.let { return it.words }
        val focus = if (shortSession) session.optJSONArray("tasks")?.strings()?.mapNotNull { parseTask(it)?.wordId }?.toSet() else null
        return words.filter { if (focus == null) it.id in selected else it.id in focus }
    }
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
    var onboardingDone = prefs.getBoolean("onboardingDone", false)
    var dailyNewWords = prefs.getInt("dailyNewWords", 5)
    var desiredRetention: Double = (prefs.getString("srsRetention", "0.9")?.toDoubleOrNull() ?: 0.9).let {
        if (it.isFinite() && it in 0.80..0.97) it else 0.90
    }
        set(value) { require(value.isFinite() && value in 0.80..0.97); field = value }
    private val introductions = JSONObject(prefs.getString("introductions", "{}")!!)
    var session = JSONObject(prefs.getString("shortSession", "{}")!!)
        private set
    val shortSession get() = session.optBoolean("active")
    // Older extra-learning sessions stored "balanced"; resume them with normal scheduling too.
    val sessionUsesSchedule get() = session.optBoolean("scheduled", session.optBoolean("balanced"))
    val sessionCount get() = session.optInt("count")
    val sessionCorrect get() = session.optInt("correct")
    val sessionFinished get() = shortSession && (session.optBoolean("finished") ||
        (session.optInt("limit") > 0 && sessionCount >= session.optInt("limit")) ||
        (session.optLong("until") > 0 && clock() >= session.optLong("until")))
    private var lastPracticeAt = prefs.getLong("lastPracticeAt", 0L)
    private var practiceDay = prefs.getString("practiceDay", "")!!

    /** Replace stale local questions, retaining every recorded learning result. */
    fun refreshPractice(): Boolean {
        val now = clock()
        val stale = (current != null || shortSession) &&
            (lastPracticeAt == 0L || practiceDay != today() || now - lastPracticeAt >= 4 * 60 * 60 * 1000L)
        if (!stale) return false
        val timed = shortSession && session.optLong("until") > 0
        val focus = session.optJSONArray("tasks")?.strings()?.mapNotNull { parseTask(it) }
        val scheduled = sessionUsesSchedule
        val restartShort = shortSession && !session.optBoolean("finished") &&
            (session.optInt("limit") == 0 || sessionCount < session.optInt("limit"))
        clearQuestion()
        studyEngine.previous = null
        // A fresh session must never reset memory strength, learning steps or real due times.
        if (restartShort) beginSession(timed, focus, scheduled)
        else { session = JSONObject(); practiceDay = today(); touchPractice(); save() }
        return true
    }
    private fun touchPractice() {
        lastPracticeAt = clock()
        if (practiceDay.isEmpty()) practiceDay = today()
    }
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(clock()))
    private fun introductionKey(word: Word): String {
        // Identity is stable across packs, imports and changes to notes/examples.
        val identity = SharedWords.identity(word)
        return introductionKeys.getOrPut(identity) {
            "word:" + JSONObject().put("forms", JSONArray(identity.forms)).put("pinyin", identity.pronunciation)
                .put("meanings", JSONArray(identity.meanings)).toString()
        }
    }
    fun needsPreview(word: Word) = !introductions.has(introductionKey(word)) &&
        Skill.entries.none { studyEngine.progress[Task(word.id, it).key]?.let { p -> p.attempts > 0 || p.mastered } == true }
    fun introduce(word: Word) { touchPractice(); introductions.put(introductionKey(word), today()); savePractice() }
    /** Extra practice expands eligibility while retaining normal scheduling and the daily setting. */
    fun extraStudyTasks(newWords: Boolean, writing: Boolean = this.writing): List<Task> {
        // Generate against the full selection to preserve ambiguity and audio exclusions.
        // A finished focused session exposes only its own words through studyWords.
        val selection = selectedStudyWords
        val available = ListeningAvailability.filter(
            studyEngine.tasks(selection, writing, audioEnabled), selection, audioProvider)
        val availableIds = available.map { it.wordId }.toSet()
        val words = selection.filter { it.id in availableIds && needsPreview(it) == newWords }
        val ids = (if (newWords) words.take(5) else words).map { it.id }.toSet()
        return available.filter { it.wordId in ids }
    }
    fun beginExtraStudy(newWords: Boolean, writing: Boolean = this.writing): Boolean {
        val focus = extraStudyTasks(newWords, writing)
        if (focus.isEmpty()) return false
        this.writing = writing
        beginSession(focus = focus, scheduled = true)
        return true
    }
    private fun eligibleWords(words: List<Word>): List<Word> {
        // Explicit focused drills can include words outside the daily introduction budget.
        if (shortSession && session.has("tasks")) return words
        val date = today()
        val used = introductions.keys().asSequence().count { introductions.optString(it) == date }
        val fresh = words.filter { needsPreview(it) }.take((dailyNewWords - used).coerceAtLeast(0)).map { it.id }.toSet()
        return words.filter { !needsPreview(it) || it.id in fresh }
    }
    fun beginSession(minutes: Boolean = false, focus: List<Task>? = null, scheduled: Boolean = false) {
        practiceDay = today()
        touchPractice()
        clearQuestion()
        session = JSONObject().put("active", true).put("limit", if (minutes) 0 else 10)
            .put("until", if (minutes) clock() + 300_000L else 0L)
            .put("count", 0).put("correct", 0).put("scheduled", scheduled)
        focus?.let { session.put("tasks", JSONArray(it.map { task -> task.key })) }
        save()
    }
    fun endSession() { session.put("finished", true); clearQuestion(); save() }
    fun leaveSession() { session = JSONObject(); clearQuestion(); save() }


    init {
        if (userId != null && lastSyncedAt > 0) syncAccess.rememberPreviousAccess()
        if (!packs.has(activePackId)) packs.put(activePackId, JSONObject().put("name", "Мои слова"))
        words = try { decodedWords(prefs.getString("words", null) ?: seedJson).toMutableList() }
        catch (_: Exception) { decodedWords(seedJson).toMutableList() }
        selected.addAll(prefs.getStringSet("selected", words.map { it.id }.toSet()) ?: emptySet())
        selected.retainAll(words.map { it.id }.toSet())
        practicePackIds.retainAll(packNames.map { it.first }.toSet())
        if (practicePackIds.isEmpty()) practicePackIds.add(activePackId)
        try {
            val saved = JSONObject(prefs.getString("progress", "{}")!!)
            saved.keys().forEach { key -> engine.progress[key] = readProgress(saved.getJSONObject(key)) }
            engine.step = prefs.getInt("step", 0)
            engine.previous = parseTask(prefs.getString("previous", null))
            reconcileSharedProgress()
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
        if (prefs.getInt("sharedWordsVersion", 0) < 1) {
            // Old mixed IDs may point at removed duplicate entries. Preserve results, restart prompts.
            val hasDuplicates = wordGroups().values.any { it.size > 1 }
            if (hasDuplicates) {
                prefs.edit().putString("beforeSharedWords", prefs.getString("packs", "{}")).apply()
                session = JSONObject(); mixed = null; mixedPrevious = null; clearQuestion()
            }
            migrateIntroductions()
            prefs.edit().putInt("sharedWordsVersion", 1).putString("introductions", introductions.toString()).apply()
            if (hasDuplicates) save()
        }
        refreshPractice()
    }

    private fun migrateIntroductions() {
        wordGroups().forEach { (_, members) ->
            val key = introductionKey(wordAt(members.first()))
            val dates = members.mapNotNull { origin -> introductions.optString("${origin.packId}/${origin.wordId}").takeIf { it.isNotEmpty() } }
            (dates + listOfNotNull(introductions.optString(key).takeIf { it.isNotEmpty() })).minOrNull()?.let { introductions.put(key, it) }
            members.forEach { introductions.remove("${it.packId}/${it.wordId}") }
        }
    }
    private fun mixedStudy(): MixedStudy? {
        if (practicePackIds == setOf(activePackId)) return null
        if (mixed == null) {
            val sources = packNames.filter { it.first in practicePackIds }.map { (id, _) ->
                if (id == activePackId) MixedStudy.Source(id, words.toList(), selected.toSet(), engine.progress.mapValues { it.value.copy() }, engine.step)
                else {
                    val pack = packs.getJSONObject(id)
                    val progress = pack.getJSONObject("progress")
                    MixedStudy.Source(id, decodedWords(pack.getString("words")), pack.getJSONArray("selected").strings().toSet(),
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
        require(ids.isNotEmpty() && ids.all { packs.has(it) && !isPackHidden(it) }) { "Выберите хотя бы один набор." }
        save(); session = JSONObject(); mixed = null; mixedPrevious = null; practicePackIds.clear(); practicePackIds.addAll(ids)
        clearQuestion(); save()
    }
    data class LibraryPack(val id: String, val name: String, val words: List<Word>, val selected: Set<String>,
        val recognized: Set<String>, val writable: Set<String>)

    fun libraryPacks(): List<LibraryPack> {
        // Inspect live state without saving/serializing the entire library just to display it.
        val mixedProgress = mixed?.let { session -> session.sources.associate { it.id to session.progressFor(it) } }.orEmpty()
        val shared = sharedProgress()
        return packNames.filter { it.first in practicePackIds }.map { (id, name) ->
            val pack = packs.getJSONObject(id)
            val vocabulary = if (id == activePackId) words.toList() else decodedWords(pack.getString("words"))
            val liveProgress = mixedProgress[id] ?: if (id == activePackId) engine.progress else null
            val progress = pack.optJSONObject("progress")
            fun mastered(task: Task): Boolean = if (shared.containsKey(MixedStudy.Origin(id, task.wordId)))
                shared.getValue(MixedStudy.Origin(id, task.wordId))[task.skill]?.mastered == true
                else if (liveProgress != null) liveProgress[task.key]?.mastered == true
                else progress?.optJSONObject(task.key)?.optBoolean("mastered") == true
            val recognition = LearningEngine().tasks(vocabulary, false, true).groupBy { it.wordId }
            LibraryPack(id, name, vocabulary, if (id == activePackId) selected.toSet() else pack.getJSONArray("selected").strings().toSet(),
                vocabulary.filter { word -> recognition[word.id].orEmpty().let { tasks ->
                    tasks.isNotEmpty() && tasks.all { mastered(it) }
                } }.map { it.id }.toSet(),
                vocabulary.filter { mastered(Task(it.id, Skill.WRITING)) }.map { it.id }.toSet())
        }
    }

    fun setWordKnown(packId: String, wordId: String, category: WordKnowledge, known: Boolean) {
        require(packs.has(packId)) { "Набор не найден." }
        val vocabulary = if (packId == activePackId) words else decodedWords(packs.getJSONObject(packId).getString("words"))
        require(vocabulary.any { it.id == wordId }) { "Слово не найдено." }
        // Flush mixed practice before changing a source pack, so its cache cannot undo the mark.
        invalidatePractice()
        val target: LearningEngine
        if (packId == activePackId) { target = engine; engine.setKnown(wordId, category, known, clock()) }
        else {
            val progress = packs.getJSONObject(packId).getJSONObject("progress")
            target = LearningEngine(progress.keys().asSequence().associateWith { readProgress(progress.getJSONObject(it)) }.toMutableMap())
            target.step = packs.getJSONObject(packId).optInt("step")
            target.setKnown(wordId, category, known, clock())
            target.progress.forEach { (key, value) -> progress.put(key, writeProgress(value)) }
        }
        category.skills.forEach { shareResult(vocabulary.first { it.id == wordId }, it, target.progress[Task(wordId, it).key], target.step) }
        save()
    }

    fun selectLibraryWord(packId: String, wordId: String, checked: Boolean) {
        require(packId in practicePackIds && packs.has(packId))
        invalidatePractice()
        if (packId == activePackId) {
            require(words.any { it.id == wordId })
            if (checked) selected.add(wordId) else selected.remove(wordId)
        } else {
            val pack = packs.getJSONObject(packId)
            require(decodedWords(pack.getString("words")).any { it.id == wordId })
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
                pack.put("selected", JSONArray(if (checked) decodedWords(pack.getString("words")).map { it.id } else emptyList<String>()))
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

    fun invalidatePractice() { session = JSONObject(); flushMixed(); mixed = null; mixedPrevious = null; clearQuestion() }
    fun addStudySynonym(word: Word, meaning: String) {
        // An accepted answer belongs to the shared word; keep each occurrence's examples and notes.
        val members = wordGroups().getValue(SharedWords.identity(word))
        val oldSession = mixed
        flushMixed()
        members.groupBy { it.packId }.forEach { (id, occurrences) ->
            val ids = occurrences.map { it.wordId }.toSet()
            val vocabulary = if (id == activePackId) words else decodedWords(packs.getJSONObject(id).getString("words"))
            val updated = vocabulary.map { if (it.id in ids) it.copy(meanings = (it.meanings + meaning).distinct()) else it }
            if (id == activePackId) words = updated.toMutableList()
            else packs.getJSONObject(id).put("words", encodeWords(updated))
        }
        // A newly accepted meaning can make a previously separate card identical, too.
        current?.let { task -> answerUndo?.let { undo ->
            val entries = undo.optJSONArray("shared") ?: JSONArray().put(JSONObject()
                .put("pack", undo.getString("pack")).put("key", undo.getString("key"))
                .put("progress", undo.opt("progress")))
            val captured = (0 until entries.length()).map { entries.getJSONObject(it).let { it.getString("pack") to it.getString("key") } }.toSet()
            wordGroups()[SharedWords.identity(word.copy(meanings = (word.meanings + meaning).distinct()))].orEmpty().forEach { origin ->
                val key = Task(origin.wordId, task.skill).key
                if (origin.packId to key !in captured) entries.put(JSONObject().put("pack", origin.packId).put("key", key)
                    .put("progress", progressAt(origin, task.skill)?.let(::writeProgress) ?: JSONObject.NULL))
            }
            undo.put("shared", entries)
        } }
        mixed = null
        reconcileSharedProgress()
        if (oldSession != null) {
            val rebuilt = mixedStudy()!!
            fun remap(task: Task?): Task? = task?.let { old ->
                val origin = oldSession.origins[old.wordId]
                rebuilt.occurrences.entries.firstOrNull { origin in it.value }?.let { Task(it.key, old.skill) }
            }
            current = remap(current)
            rebuilt.engine.previous = remap(oldSession.engine.previous)
            mixedPrevious = rebuilt.engine.previous
            listOf("tasks", "reviewed").forEach { field -> session.optJSONArray(field)?.let { entries ->
                session.put(field, JSONArray(entries.strings().mapNotNull { remap(parseTask(it))?.key }.distinct()))
            } }
        }
    }
    /** Capture persistent pack coordinates, so rollback also works after mixed queues rebase on reload. */
    fun recordAnswer(correct: Boolean) {
        val task = current ?: return
        if (feedback != null) return
        touchPractice()
        val session = mixedStudy()
        val origin = session?.origins?.getValue(task.wordId)
        val packId = origin?.packId ?: activePackId
        val key = Task(origin?.wordId ?: task.wordId, task.skill).key
        // Capture this one skill directly. Saving the whole library before and after each
        // answer doubled the work on the UI thread; mixed queues only need coordinate rebasing.
        val previousProgress = studyEngine.progress[task.key]?.takeIf { it != Progress() }?.let { p ->
            val baseStep = session?.sources?.first { it.id == packId }?.step ?: 0
            writeProgress(if (session == null) p else p.copy(nextAt = baseStep + p.nextAt))
        }
        val steps = JSONObject()
        if (session == null) steps.put(activePackId, engine.step)
        else session.sources.forEach { steps.put(it.id, it.step + session.engine.step) }
        answerUndo = JSONObject().put("sessionReviewed", this.session.optJSONArray("reviewed") ?: JSONArray()).put("sessionCount", sessionCount).put("sessionCorrect", sessionCorrect).put("pack", packId).put("key", key)
            .put("progress", previousProgress ?: JSONObject.NULL)
            .put("steps", steps).put("previous", studyEngine.previous?.key ?: JSONObject.NULL)
            .put("activePrevious", engine.previous?.key ?: JSONObject.NULL)
        val word = studyWords.first { it.id == task.wordId }
        val members = wordGroups()[SharedWords.identity(word)].orEmpty()
        if (members.size > 1) answerUndo!!.put("shared", JSONArray(members.map { member ->
            JSONObject().put("pack", member.packId).put("key", Task(member.wordId, task.skill).key)
                .put("progress", progressAt(member, task.skill)?.takeIf { it != Progress() }?.let(::writeProgress) ?: JSONObject.NULL)
        }))
        beforeAnswer = studyEngine.state(task).copy()
        studyEngine.answer(task, correct, now = clock(), retention = desiredRetention)
        if (!correct && task.skill in characterSkills && draft in choices) {
            val state = studyEngine.state(task)
            state.confusions = state.confusions + (draft to ((state.confusions[draft] ?: 0) + 1))
        }
        shareResult(word, task.skill, studyEngine.progress[task.key], studyEngine.step)
        if (shortSession) {
            this.session.put("count", sessionCount + 1).put("correct", sessionCorrect + if (correct) 1 else 0)
            if (this.session.has("tasks")) this.session.put("reviewed",
                JSONArray((this.session.optJSONArray("reviewed")?.strings().orEmpty() + task.key).distinct()))
        }
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
        undo.optJSONArray("shared")?.let { entries -> repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val saved = packs.optJSONObject(entry.getString("pack"))?.getJSONObject("progress") ?: return@repeat
            if (entry.isNull("progress")) saved.remove(entry.getString("key"))
            else saved.put(entry.getString("key"), JSONObject(entry.getJSONObject("progress").toString()))
        } }
        val steps = undo.getJSONObject("steps")
        steps.keys().forEach { id -> packs.getJSONObject(id).put("step", steps.getInt(id)) }
        val active = packs.getJSONObject(activePackId)
        engine.progress.clear()
        val progress = active.getJSONObject("progress")
        progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
        engine.step = active.getInt("step")
        engine.previous = if (undo.isNull("activePrevious")) null else parseTask(undo.getString("activePrevious"))
        mixedPrevious = if (undo.isNull("previous")) null else parseTask(undo.getString("previous"))
        if (shortSession) session.put("count", undo.optInt("sessionCount")).put("correct", undo.optInt("sessionCorrect"))
            .put("reviewed", undo.optJSONArray("sessionReviewed") ?: JSONArray())
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

    fun tasks(): List<Task> {
        // Generate against the whole selection first to retain ambiguity exclusions.
        val words = studyWords
        val eligible = eligibleWords(words).map { it.id }.toSet()
        val focus = session.optJSONArray("tasks")?.strings()?.toSet()
        val available = if (shortSession && focus != null)
            studyEngine.tasks(words, false, audioEnabled) + studyEngine.tasks(words, true, false)
        else studyEngine.tasks(words, writing, audioEnabled)
        return ListeningAvailability.filter(available, words, audioProvider).filter {
            it.wordId in eligible && (!shortSession || focus == null || it.key in focus)
        }
    }
    fun clearQuestion() { current = null; feedback = null; revealed = false; draft = ""; toneDraft.clear(); choices = emptyList(); beforeAnswer = null; answerUndo = null; answerIgnored = false; strokes = "" }
    fun nextQuestion() {
        refreshPractice()
        practiceDay = today()
        touchPractice()
        clearQuestion()
        if (sessionFinished) { savePractice(); return }
        val available = tasks()
        val explicitFocus = shortSession && session.has("tasks")
        val reviewed = session.optJSONArray("reviewed")?.strings()?.toSet().orEmpty()
        val manualDrill = explicitFocus && !sessionUsesSchedule
        val candidates = if (manualDrill) available.filter { it.key !in reviewed } else available
        // Extras use the same due times, learning priority and sibling spacing as normal practice.
        // Only explicitly chosen drills can force an early review once per skill.
        current = studyEngine.next(candidates, now = clock())
            ?: if (manualDrill) candidates.firstOrNull { it.wordId != studyEngine.previous?.wordId } ?: candidates.firstOrNull() else null
        if (current == null && shortSession) session.put("finished", true)
        current?.let { task ->
            val word = studyWords.first { it.id == task.wordId }
            val confused = studyEngine.peek(task).confusions.entries
                .filter { !word.acceptsForm(it.key) }.sortedByDescending { it.value }.map { it.key }
            val reciprocal = (mixedStudy()?.words ?: words).filter { other -> other.id != word.id &&
                studyEngine.progress[Task(other.id, task.skill).key]?.confusions?.keys?.any { word.acceptsForm(it) } == true
            }.map { it.hanzi }
            val comparisons = (confused + reciprocal).filter { !word.acceptsForm(it) }.distinct()
            choices = if (task.skill in characterSkills && comparisons.isNotEmpty())
                (comparisons.take(3) + word.hanzi).shuffled() else studyEngine.options(word)
        }
        savePractice()
    }
    /** A new prompt or preview changes session state, not vocabulary or recorded memory. */
    private fun savePractice() {
        prefs.edit().putLong("lastPracticeAt", lastPracticeAt).putString("practiceDay", practiceDay)
            .putString("shortSession", session.toString()).putString("introductions", introductions.toString())
            .putBoolean("writing", writing).putString("route", route).putString("current", current?.key)
            .putInt("feedback", feedback?.let { if (it) 1 else 0 } ?: -1)
            .putBoolean("revealed", revealed).putString("draft", draft)
            .putString("tones", toneDraft.joinToString("")).putString("choices", JSONArray(choices).toString())
            .putString("before", beforeAnswer?.let { writeProgress(it).toString() })
            .putString("answerUndo", answerUndo?.toString()).putBoolean("answerIgnored", answerIgnored)
            .putString("strokes", strokes).apply()
    }
    fun save() {
        if (lastPracticeAt == 0L && (current != null || shortSession)) touchPractice()
        mixed?.let { mixedPrevious = it.engine.previous }
        flushMixed()
        reconcileSharedProgress()
        val progressJson = JSONObject()
        engine.progress.filterValues { it != Progress() }.forEach { (key, p) -> progressJson.put(key, writeProgress(p)) }
        val vocabulary = encodedWords()
        packs.put(activePackId, packs.getJSONObject(activePackId).put("name", packName).put("words", vocabulary)
            .put("selected", JSONArray(selected.toList())).put("progress", progressJson).put("step", engine.step))
        normalizePracticePacks()
        // Keep practice membership in the synced pack, alongside its word checkboxes.
        packs.keys().forEach { id -> packs.getJSONObject(id).put("practice", id in practicePackIds) }
        val packData = packs.toString()
        prefs.edit().putLong("lastPracticeAt", lastPracticeAt).putString("practiceDay", practiceDay).putString("shortSession", session.toString()).putString("introductions", introductions.toString())
            .putString("srsRetention", desiredRetention.toString()).putBoolean("onboardingDone", onboardingDone).putInt("dailyNewWords", dailyNewWords).putStringSet("practicePacks", practicePackIds.toSet()).putString("packs", packData).putString("activePack", activePackId).putString("words", vocabulary).putStringSet("selected", selected.toSet())
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
            .putString("syncBase", syncBaseText()).putString("syncRevision", syncRevision)
            .putLong("lastSyncedAt", lastSyncedAt).apply()
        // Change detection needs only local equality. Canonicalize when sync actually runs.
        if (packData != lastSyncSnapshot) { lastSyncSnapshot = packData; onSyncDataChanged?.invoke() }
    }

    private val canonicalPackCache = mutableMapOf<String, Pair<String, String>>()
    private fun snapshotFromPacks(): Map<String, String> {
        val ids = packs.keys().asSequence().toSet()
        canonicalPackCache.keys.retainAll(ids)
        return ids.associateWith { id ->
            val pack = packs.getJSONObject(id)
            val raw = pack.toString()
            val cached = canonicalPackCache[id]
            if (cached?.first == raw) cached.second
            else SyncSnapshot.pack(pack).also { canonicalPackCache[id] = raw to it }
        }
    }

    fun syncSnapshot(): Map<String, String> { save(); return snapshotFromPacks() }

    /** UI mutations already call save(); the sync client can read that library without saving it again. */
    fun savedSyncSnapshot(): Map<String, String> {
        if (!packs.getJSONObject(activePackId).has("words") ||
            packs.keys().asSequence().any { !packs.getJSONObject(it).has("practice") }) save()
        return snapshotFromPacks()
    }

    fun acknowledgeSync(cloud: Map<String, String>, revision: String) {
        syncBase = cloud.toMap(); syncRevision = revision; lastSyncedAt = clock()
        prefs.edit().putString("syncBase", syncBaseText()).putString("syncRevision", syncRevision)
            .putLong("lastSyncedAt", lastSyncedAt).apply()
    }

    /** Apply only after a successful server transaction; baseline and local state persist together. */
    fun applySync(pending: Map<String, String>, cloud: Map<String, String>, revision: String): Int {
        if (pending == cloud) {
            // The server acknowledged our own snapshot. Keep any newer local answers and
            // avoid decoding/validating/rebuilding the complete library during practice.
            save()
            acknowledgeSync(cloud, revision)
            return 0
        }
        val current = syncSnapshot()
        val merged = SyncSnapshot.merge(pending, current, cloud)
        validateSyncPacks(merged.packs)
        val oldActive = current[activePackId]
        val previousPractice = practicePackIds.toSet()
        packs.keys().asSequence().toList().forEach { packs.remove(it) }
        merged.packs.forEach { (id, data) -> packs.put(id, JSONObject(data)) }
        if (!packs.has(activePackId)) activePackId = merged.packs.keys.sorted().first()
        practicePackIds.clear()
        // Older snapshots have no membership field; retain this device's choice for those packs.
        packs.keys().forEach { id ->
            if (packs.getJSONObject(id).optBoolean("practice", id in previousPractice)) practicePackIds.add(id)
        }
        normalizePracticePacks()
        val sharedChanged = wordGroups().values.any { members -> members.size > 1 &&
            members.any { current[it.packId] != merged.packs[it.packId] } }
        val mixedChanged = sharedChanged || previousPractice != practicePackIds || previousPractice.any { current[it] != merged.packs[it] }
        if (mixedChanged || oldActive != merged.packs[activePackId]) mixed = null
        if (oldActive != merged.packs[activePackId]) {
            val pack = packs.getJSONObject(activePackId)
            words = decodedWords(pack.getString("words")).toMutableList()
            selected.clear(); selected.addAll(pack.getJSONArray("selected").strings())
            engine.progress.clear()
            val progress = pack.getJSONObject("progress")
            progress.keys().forEach { engine.progress[it] = readProgress(progress.getJSONObject(it)) }
            engine.step = pack.getInt("step"); engine.previous = null
            clearQuestion()
            if (route == "quiz") route = "learn"
        }
        if (mixedChanged) { session = JSONObject(); mixedPrevious = null; clearQuestion(); if (route == "quiz") route = "learn" }
        syncBase = cloud.toMap(); syncRevision = revision; lastSyncedAt = System.currentTimeMillis()
        save()
        return merged.conflicts
    }
    fun switchPack(id: String) {
        require(packs.has(id)) { "Набор не найден." }
        if (id == activePackId) return
        save()
        session = JSONObject(); mixed = null; mixedPrevious = null
        if (practicePackIds.size == 1) { practicePackIds.clear(); practicePackIds.add(id) }
        val pack = packs.getJSONObject(id)
        val incoming = decodedWords(pack.getString("words"))
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
        require(isPackHidden(old) || visiblePackNames.size > 1) { "Оставьте хотя бы один видимый набор." }
        switchPack(visiblePackNames.first { it.first != old }.first)
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
            val selection = practicePackIds + data.practicePacks.filter { it in merged.packs && !isPackHidden(it) }
            if (selection != practicePackIds) choosePracticePacks(selection)
            return merged
        }
        session = JSONObject(); mixed = null; mixedPrevious = null
        packs.keys().asSequence().toList().forEach { packs.remove(it) }
        merged.packs.forEach { (id, text) -> packs.put(id, JSONObject(text)) }
        val active = packs.getJSONObject(activePackId)
        words = decodedWords(active.getString("words")).toMutableList()
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
    /** Navigation and revealing an answer must not re-serialize every pack's review history. */
    fun saveScreen() {
        prefs.edit().putString("route", route).putBoolean("onboardingDone", onboardingDone)
            .putBoolean("revealed", revealed).putString("tones", toneDraft.joinToString(""))
            .putBoolean("writing", writing).apply()
    }
    fun saveDraft() {
        touchPractice()
        prefs.edit().putLong("lastPracticeAt", lastPracticeAt).putString("practiceDay", practiceDay).putString("draft", draft).putString("strokes", strokes).apply()
    }
    fun replaceWord(word: Word) = replaceWords(listOf(word))
    fun replaceWords(incoming: List<Word>) {
        invalidatePractice()
        incoming.forEach { validate(it) }
        val replacements = incoming.associateBy { it.id }
        val previousWords = words.associateBy { it.id }
        val oldIds = words.map { it.id }.toSet()
        words = (words.map { replacements[it.id] ?: it } + incoming.filter { it.id !in oldIds }).toMutableList()
        selected.addAll(replacements.keys)
        val changedLearning = incoming.filter { updated ->
            val old = previousWords[updated.id]
            old == null || SharedWords.identity(old) != SharedWords.identity(updated)
        }.map { it.id }.toSet()
        engine.progress.keys.removeAll { key -> key.substringBefore(':') in changedLearning }
        clearQuestion()
        save()
    }
    fun duplicateWarnings(incoming: List<Word>): List<String> {
        save()
        val withinFile = incoming.flatMapIndexed { index, word -> incoming.drop(index + 1).mapNotNull { other ->
            if (word.writtenForms.any { it in other.writtenForms } ||
                (Answers.pronunciationKey(word) == Answers.pronunciationKey(other) && word.meanings.any { a -> other.meanings.any { Answers.meaning(a) == Answers.meaning(it) } }))
                "${word.hanzi} ↔ ${other.hanzi} · внутри файла" else null
        } }
        return withinFile + packNames.flatMap { (id, name) ->
            val existing = decodedWords(packs.getJSONObject(id).getString("words"))
            incoming.mapNotNull { word ->
                existing.firstOrNull { old -> old.writtenForms.any { it in word.writtenForms } ||
                    (Answers.pronunciationKey(old) == Answers.pronunciationKey(word) &&
                        old.meanings.any { a -> word.meanings.any { Answers.meaning(a) == Answers.meaning(it) } })
                }?.let { "${word.hanzi} ↔ ${it.hanzi} · $name" }
            }
        }.distinct()
    }

    fun previewUpdate(incoming: List<Word>, packId: String = activePackId): PackMaintenance.Update {
        incoming.forEach { validate(it) }
        val existing = if (packId == activePackId) words else decodedWords(packs.getJSONObject(packId).getString("words"))
        return PackMaintenance.preview(existing, incoming)
    }

    fun updatePack(name: String, incoming: List<Word>) {
        require(name.trim().length in 1..80) { "Введите название до 80 знаков." }
        val update = previewUpdate(incoming)
        update.words.forEach { validate(it) }
        invalidatePractice()
        val oldIds = words.map { it.id }.toSet()
        words = update.words.toMutableList()
        selected.addAll(words.filter { it.id !in oldIds }.map { it.id })
        packs.getJSONObject(activePackId).put("name", name.trim())
        save()
    }

    data class PackOverview(val name: String, val total: Int, val introduced: Int, val learned: Int,
        val mastered: Int, val due: Int, val skills: Map<Skill, List<Progress>>)

    fun overview(includeTotal: Boolean = false): List<PackOverview> {
        // The pager prepares Progress even when it is offscreen. Reading it must not
        // serialize review histories, write preferences, or trigger cloud sync.
        val mixedProgress = mixed?.let { session -> session.sources.associate { it.id to session.progressFor(it) } }.orEmpty()
        val shared = sharedProgress()
        val uniqueTasks = mutableMapOf<Pair<SharedWords.Identity, Skill>, Progress>()
        val introduced = mutableSetOf<SharedWords.Identity>()
        val uniqueWords = mutableSetOf<SharedWords.Identity>()
        val now = clock()
        val result = packNames.map { (id, name) ->
            val pack = packs.getJSONObject(id)
            val vocabulary = if (id == activePackId) words else decodedWords(pack.getString("words"))
            val progress = mixedProgress[id] ?: if (id == activePackId) engine.progress else {
                val saved = pack.getJSONObject("progress")
                saved.keys().asSequence().associateWith { readProgress(saved.getJSONObject(it)) }
            }
            val taskEngine = if (id == activePackId) engine else LearningEngine()
            val tasks = taskEngine.tasks(vocabulary, false, true) + taskEngine.tasks(vocabulary, true, false)
            val tasksByWord = tasks.groupBy { it.wordId }
            fun state(task: Task): Progress? = shared[MixedStudy.Origin(id, task.wordId)]?.let { it[task.skill] } ?: progress[task.key]
            fun isIntroduced(word: Word) = introductions.has(introductionKey(word)) ||
                Skill.entries.any { state(Task(word.id, it))?.let { p -> p.attempts > 0 || p.mastered } == true }
            if (includeTotal) vocabulary.forEach { word ->
                val identity = SharedWords.identity(word)
                uniqueWords.add(identity)
                if (isIntroduced(word)) introduced.add(identity)
                tasksByWord[word.id].orEmpty().forEach { task -> uniqueTasks[identity to task.skill] = state(task) ?: Progress() }
            }
            PackOverview(name, vocabulary.size,
                vocabulary.count(::isIntroduced),
                vocabulary.count { w -> tasksByWord[w.id].orEmpty().all { state(it)?.mastered == true } },
                tasks.count { state(it)?.mastered == true },
                tasks.count { state(it)?.let { p -> p.due > 0 && p.due <= now } == true },
                tasks.groupBy { it.skill }.mapValues { (_, values) -> values.map { state(it) ?: Progress() } })
        }
        if (!includeTotal) return result
        val byWord = uniqueTasks.entries.groupBy { it.key.first }
        return result + PackOverview("Все наборы", uniqueWords.size, introduced.size,
            uniqueWords.count { byWord[it].orEmpty().all { entry -> entry.value.mastered } },
            uniqueTasks.values.count { it.mastered }, uniqueTasks.values.count { it.due > 0 && it.due <= now },
            uniqueTasks.entries.groupBy { it.key.second }.mapValues { (_, entries) -> entries.map { it.value } })
    }

    companion object {
        val characterSkills = setOf(Skill.MEANING_CHARACTER, Skill.PINYIN_CHARACTER, Skill.AUDIO_CHARACTER)
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
                require(!pack.has("folder") || pack.get("folder") is String && pack.getString("folder").length <= 647) { "Некорректная папка." }
                FolderPaths.validate(pack.optString("folder", ""))
                require(!pack.has("hidden") || pack.get("hidden") is Boolean) { "Некорректная видимость набора." }
                require(!pack.has("practice") || pack.get("practice") is Boolean) { "Некорректный выбор набора для практики." }
                val words = decodeWords(pack.getString("words"))
                val ids = words.map { it.id }.toSet()
                require(pack.getJSONArray("selected").strings().all { it in ids }) { "Некорректный выбор слов." }
                require(pack.getInt("step") >= 0) { "Некорректный шаг обучения." }
                val progress = pack.getJSONObject("progress")
                progress.keys().forEach { key ->
                    val task = parseTask(key)
                    require(task != null && task.wordId in ids) { "Некорректный навык." }
                    val p = readProgress(progress.getJSONObject(key))
                    ProgressCodec.validate(p)
                }
            }
        }

        private fun parseTask(key: String?): Task? {
            if (key == null) return null
            val parts = key.split(':')
            return if (parts.size == 2) Task(parts[0], Skill.valueOf(parts[1])) else null
        }
        private fun readProgress(j: JSONObject) = ProgressCodec.read(j)
        private fun writeProgress(p: Progress) = ProgressCodec.write(p)
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
                    j.optJSONArray("alternatives")?.strings()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList(),
                    j.optString("example").trim(), j.optString("examplePinyin").trim(), j.optString("exampleTranslation").trim())
                validate(word)
                word
                } catch (e: Exception) { throw IllegalArgumentException("Карточка ${index + 1}: ${e.message ?: "проверьте поля"}", e) }
            }
            require(result.map { it.id }.distinct().size == result.size) { "У слов должны быть уникальные id." }
            require(result.map { it.hanzi }.distinct().size == result.size) { "В файле есть повторяющиеся иероглифы." }
            return result
        }
        fun validate(w: Word) {
            val exampleFields = listOf(w.example, w.examplePinyin, w.exampleTranslation)
            require(exampleFields.all { it.isBlank() } || exampleFields.all { it.isNotBlank() && it.length <= 2000 }) { "Для примера заполните предложение, пиньинь и перевод (до 2000 знаков каждый)." }
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
                .put("example", w.example).put("examplePinyin", w.examplePinyin).put("exampleTranslation", w.exampleTranslation)
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
