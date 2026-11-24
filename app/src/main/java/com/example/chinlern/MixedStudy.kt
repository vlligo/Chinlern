package com.example.chinlern

/** One queue entry per identical word, with progress written back to every selected occurrence. */
class MixedStudy(val sources: List<Source>) {
    data class Source(val id: String, val words: List<Word>, val selected: Set<String>, val progress: Map<String, Progress>, val step: Int)
    data class Origin(val packId: String, val wordId: String)
    val engine = LearningEngine()
    val origins = mutableMapOf<String, Origin>()
    private val idsByOrigin = mutableMapOf<Origin, String>()
    fun wordId(origin: Origin): String? = idsByOrigin[origin]
    val words = mutableListOf<Word>()
    init {
        val identities = mutableMapOf<SharedWords.Identity, String>()
        sources.sortedBy { it.id }.forEachIndexed { packIndex, source ->
            source.words.forEachIndexed { wordIndex, word ->
                if (word.id in source.selected) {
                    val id = identities.getOrPut(SharedWords.identity(word)) {
                        "pack${packIndex}word$wordIndex".also { id ->
                            origins[id] = Origin(source.id, word.id)
                            words.add(word.copy(id = id))
                        }
                    }
                    idsByOrigin[Origin(source.id, word.id)] = id
                    Skill.entries.forEach { skill -> source.progress[Task(word.id, skill).key]?.let { p ->
                        val key = Task(id, skill).key
                        SharedWords.merge(listOfNotNull(engine.progress[key], p.copy(nextAt = (p.nextAt - source.step).coerceAtLeast(0))))
                            ?.let { engine.progress[key] = it }
                    } }
                }
            }
        }
    }
    fun progressFor(source: Source): Map<String, Progress> {
        val result = source.progress.mapValues { it.value.copy() }.toMutableMap()
        for (sourceId in source.selected) {
            val id = wordId(Origin(source.id, sourceId)) ?: continue
            for (skill in Skill.entries) {
                val key = Task(sourceId, skill).key
                val p = engine.progress[Task(id, skill).key]
                if (p != null && p != Progress()) result[key] = p.copy(nextAt = source.step + p.nextAt)
                else result.remove(key)
            }
        }
        return result
    }
}
