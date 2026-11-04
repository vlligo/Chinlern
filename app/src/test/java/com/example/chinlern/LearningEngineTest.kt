package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LearningEngineTest {
    private val water = Word("water", "水", "shuǐ", "shui3", listOf("вода", "влага"), listOf("永", "冰", "泳", "水", "氵"), alternatives = listOf("氵"))
    @Test fun cachedTasksTrackInPlaceVocabularyChangesAndModeSwitches() {
        val engine = LearningEngine()
        val words = mutableListOf(water)
        val initial = engine.tasks(words, false, true)
        assertSame(initial, engine.tasks(words, false, true))
        assertEquals(listOf(Task(water.id, Skill.WRITING)), engine.tasks(words, true, false))
        assertFalse(engine.tasks(words, false, false).any { it.skill == Skill.AUDIO_CHARACTER })
        words.add(water.copy(id = "homophone", hanzi = "氵"))
        val ambiguous = engine.tasks(words, false, true)
        assertFalse(ambiguous.any { it.skill == Skill.PINYIN_CHARACTER })
        assertEquals(setOf("water", "homophone"), ambiguous.map { it.wordId }.toSet())
        words.removeAt(1)
        assertEquals(initial, engine.tasks(words, false, true))
        words[0] = water.copy(id = "replacement")
        assertEquals(setOf("replacement"), engine.tasks(words, false, true).map { it.wordId }.toSet())
    }

    @Test fun markedAndNumberedPinyinAreEquivalent() {
        listOf("shuǐ", "shui3", " SHUǏ ", "shui\u030c").forEach { assertTrue(it, Answers.pinyinMatches(it, "shui3")) }
        listOf("shui", "shui2", "shuí", "shuǐ2", "shui33", "sui3").forEach { assertFalse(it, Answers.pinyinMatches(it, "shui3")) }
    }
    @Test fun umlautAliasesDoNotEraseTheUmlautDistinction() {
        listOf("nǚ", "nü3", "nv3", "nu:3").forEach { assertTrue(it, Answers.pinyinMatches(it, "nü3")) }
        assertFalse(Answers.pinyinMatches("nu3", "nü3"))
    }
    @Test fun multiSyllableWordsRequireEveryTone() {
        assertTrue(Answers.pinyinMatches("Zhōngguó", "zhong1 guo2"))
        assertTrue(Answers.pinyinMatches("zhong1guo2", "zhong1 guo2"))
        assertTrue(Answers.pinyinMatches("nǐ hǎo", "ni3 hao3"))
        assertFalse(Answers.pinyinMatches("nihao3", "ni3 hao3"))
        assertFalse(Answers.pinyinMatches("ni2hao3", "ni3 hao3"))
        assertFalse(Answers.pinyinMatches("ni3hao3x", "ni3 hao3"))
        assertFalse(Answers.pinyinMatches("nǐ háǒ", "ni3 hao3"))
    }
    @Test fun neutralToneMayBeOmittedOrFive() {
        assertTrue(Answers.pinyinMatches("xuésheng", "xue2 sheng5"))
        assertTrue(Answers.pinyinMatches("xue2sheng5", "xue2 sheng5"))
        assertFalse(Answers.pinyinMatches("xuesheng", "xue2 sheng5"))
    }
    @Test fun russianNormalizationAndSynonyms() {
        assertEquals("ребенок", Answers.meaning(" РЕБЁНОК "))
        assertTrue(Answers.meaningMatches("  ВЛАГА. ", water))
        assertFalse(Answers.meaningMatches("водка", water))
    }
    @Test fun errorsAndStreaksAreIndependentPerDirection() {
        val e = LearningEngine(); val meaning = Task("water", Skill.CHARACTER_MEANING); val tone = Task("water", Skill.CHARACTER_TONE)
        e.answer(meaning, false); e.answer(tone, true)
        assertFalse(e.state(meaning).mastered); assertFalse(e.state(tone).mastered)
        assertEquals(Fsrs.Phase.LEARNING, e.state(tone).memory!!.phase)
        e.answer(meaning, true); assertFalse(e.state(meaning).mastered)
        e.answer(meaning, false); assertEquals(0, e.state(meaning).streak)
        e.answer(meaning, true); e.answer(meaning, true); assertTrue(e.state(meaning).mastered)
        assertEquals(2, e.state(meaning).errors)
    }
    @Test fun failureWaitsForRealTimeWithoutMasteredFillers() {
        val e = LearningEngine(); val failed = Task("water", Skill.CHARACTER_TONE); val filler = Task("fire", Skill.CHARACTER_MEANING)
        e.setKnown("fire", WordKnowledge.RECOGNITION, true, now = 1000)
        e.answer(failed, false, now = 1000)
        assertNull(e.next(listOf(failed, filler), now = 60_999))
        assertEquals(failed, e.next(listOf(failed, filler), now = 61_000))
        assertEquals(0, e.state(filler).attempts)
    }
    @Test fun completionRequiresAllSkills() {
        val e = LearningEngine(); val tasks = e.tasks(listOf(water), false, true)
        assertEquals(9, tasks.size)
        tasks.forEach { e.answer(it, true) }
        assertNull(e.next(tasks))
        assertFalse(e.state(Task("water", Skill.WRITING)).mastered)
    }
    @Test fun ambiguousPromptsAreExcludedAndTotalsAgree() {
        val e = LearningEngine(); val homophone = water.copy(id = "other", hanzi = "氺", meanings = listOf("другое"))
        val tasks = e.tasks(listOf(water, homophone), false, true)
        assertEquals(10, tasks.size)
        assertFalse(tasks.any { it.skill in listOf(Skill.PINYIN_MEANING, Skill.PINYIN_CHARACTER, Skill.AUDIO_MEANING, Skill.AUDIO_CHARACTER) })
        val sameMeaning = water.copy(id = "same", hanzi = "泉", pinyin = "quán", numbered = "quan2")
        assertFalse(e.tasks(listOf(water, sameMeaning), false, true).any { it.skill in listOf(Skill.MEANING_PINYIN, Skill.MEANING_CHARACTER) })
    }
    @Test fun optionsIncludeExactlyOneAcceptedFormAndPracticeVariants() {
        val engine = LearningEngine()
        val seen = mutableSetOf<String>()
        repeat(50) { seed ->
            val choices = engine.options(water, Random(seed))
            assertEquals(1, choices.count { water.acceptsForm(it) })
            assertEquals(choices.size, choices.distinct().size)
            seen.add(choices.first { water.acceptsForm(it) })
        }
        assertEquals(setOf("水", "氵"), seen)
        assertTrue(water.acceptsForm(" 氵 "))
        assertFalse(water.acceptsForm("冰"))
    }
    @Test fun dueReviewRetainsHistoryAndFutureSkills() {
        val e = LearningEngine(); val a = Task("water", Skill.CHARACTER_MEANING); val b = Task("fire", Skill.CHARACTER_MEANING)
        e.answer(a, Fsrs.Rating.EASY, now = 1000); e.answer(b, Fsrs.Rating.EASY, now = 1000 + Fsrs.DAY)
        val due = e.state(a).due
        val before = e.state(a).memory
        e.restart(listOf(a, b), dueOnly = true, now = due)
        assertEquals(before, e.state(a).memory)
        assertEquals(a, e.next(listOf(a, b), now = due))
        e.answer(a, true, now = due)
        assertTrue(e.state(a).interval > 8)
        assertEquals(2, e.state(a).attempts)
    }
    @Test fun audioCanBeDisabledWithoutErasingStatistics() {
        val e = LearningEngine(); e.answer(Task("water", Skill.AUDIO_MEANING), false)
        assertEquals(7, e.tasks(listOf(water), false, false).size)
        assertEquals(1, e.state(Task("water", Skill.AUDIO_MEANING)).errors)
    }
}
