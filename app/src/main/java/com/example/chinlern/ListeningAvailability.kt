package com.example.chinlern

/** Hanping PLAY does not report when it substitutes a base syllable for missing erhua. */
object ListeningAvailability {
    // Complete erhua recordings verified in the installed Hanping voice's playback logs.
    // Unverified erhua is excluded rather than risking an impossible listening prompt.
    private val verifiedErhua = setOf("na3", "zhe4")

    fun allows(word: Word, provider: String): Boolean {
        if (provider != "hanping") return true
        val syllables = Regex("[a-z]+[1-5]").findAll(HanpingPinyin.encode(word.numbered)).map { it.value }.toList()
        return syllables.indices.all { i ->
            syllables[i] != "r5" || (i > 0 && syllables[i - 1] in verifiedErhua)
        }
    }

    fun filter(tasks: List<Task>, words: List<Word>, provider: String): List<Task> {
        if (provider != "hanping") return tasks
        val audible = words.filter { allows(it, provider) }.map { it.id }.toSet()
        return tasks.filter {
            it.skill !in setOf(Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER) || it.wordId in audible
        }
    }
}
