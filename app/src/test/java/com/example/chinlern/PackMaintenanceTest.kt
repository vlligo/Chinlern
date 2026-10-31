package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test

class PackMaintenanceTest {
    private val water = Word("water", "水", "shuǐ", "shui3", listOf("вода"), listOf("永", "冰", "泳"))
    private val fire = Word("fire", "火", "huǒ", "huo3", listOf("огонь"), listOf("人", "大", "犬"))

    @Test fun updateMatchesHanziAndPreservesLocalIdsAndMissingWords() {
        val incoming = water.copy(id = "水", example = "我喝水。", examplePinyin = "Wǒ hē shuǐ.", exampleTranslation = "Я пью воду.")
        val update = PackMaintenance.preview(listOf(water, fire), listOf(incoming))
        assertEquals(listOf(incoming.copy(id = "water"), fire), update.words)
        assertEquals(listOf(fire), update.retained)
        assertEquals(1, update.changed.size)
        assertTrue(update.added.isEmpty())
        assertTrue(PackMaintenance.preview(update.words, listOf(incoming)).changed.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun reusedIdCannotTransferProgressToDifferentWord() {
        PackMaintenance.preview(listOf(water), listOf(fire.copy(id = "water")))
    }

    @Test fun examplesRoundTripAndOldPacksRemainReadable() {
        val example = water.copy(example = "我喝水。", examplePinyin = "Wǒ hē shuǐ.", exampleTranslation = "Я пью воду.")
        assertEquals(listOf(example), StudyStore.decodeWords(StudyStore.encodeWords(listOf(example))))
        assertTrue(StudyStore.decodeWords(StudyStore.seedJson).all { it.example.isEmpty() })
    }

    @Test(expected = IllegalArgumentException::class) fun partialExampleIsRejected() {
        StudyStore.validate(water.copy(example = "我喝水。"))
    }

    @Test fun mixedProgressCopiesKeepConfusionsIndependent() {
        val task = Task(water.id, Skill.MEANING_CHARACTER)
        val original = Progress(attempts = 1, errors = 1, confusions = mapOf("永" to 1))
        val source = MixedStudy.Source("pack", listOf(water), setOf(water.id), mapOf(task.key to original), 1)
        val mixed = MixedStudy(listOf(source))
        val state = mixed.engine.state(Task(mixed.words.single().id, task.skill))
        state.confusions = state.confusions + ("永" to 2)
        assertEquals(1, original.confusions["永"])
        assertEquals(2, mixed.progressFor(source).getValue(task.key).confusions["永"])
    }
}
