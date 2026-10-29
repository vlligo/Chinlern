package com.example.chinlern

/** A temporary combined queue. Persistent word IDs and progress remain inside their own packs. */
class MixedStudy(val sources: List<Source>) {
    data class Source(val id: String, val words: List<Word>, val selected: Set<String>, val progress: Map<String, Progress>, val step: Int)
    data class Origin(val packId: String, val wordId: String)
    val engine = LearningEngine()
    val origins = mutableMapOf<String, Origin>()
    val words = mutableListOf<Word>()
    init {
        sources.sortedBy { it.id }.forEachIndexed { packIndex, source ->
            source.words.forEachIndexed { wordIndex, word ->
                if (word.id in source.selected) {
                    val id = "pack${packIndex}word$wordIndex"
                    origins[id] = Origin(source.id, word.id)
                    words.add(word.copy(id = id))
                    Skill.entries.forEach { skill -> source.progress[Task(word.id, skill).key]?.let { p ->
                        engine.progress[Task(id, skill).key] = p.copy(nextAt = (p.nextAt - source.step).coerceAtLeast(0))
                    } }
                }
            }
        }
    }
    fun progressFor(source: Source): Map<String, Progress> {
        val result = source.progress.mapValues { it.value.copy() }.toMutableMap()
        origins.filterValues { it.packId == source.id }.forEach { (id, origin) ->
            Skill.entries.forEach { skill -> engine.progress[Task(id, skill).key]?.let { p ->
                if (p != Progress()) result[Task(origin.wordId, skill).key] = p.copy(nextAt = source.step + p.nextAt)
            } }
        }
        return result
    }
}
