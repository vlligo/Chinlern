package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HardWordReviewTest {
    @Test fun practiceReviewCanAddAnotherPackAndFilterActivities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        store.renamePack("Alpha")
        val first = store.activePackId
        store.engine.progress[Task(store.words.first().id, Skill.WRITING).key] = Progress(errors = 2, attempts = 2)
        store.save()
        store.importPack("Beta", listOf(store.words.last()))
        store.engine.progress[Task(store.words.first().id, Skill.CHARACTER_MEANING).key] = Progress(errors = 1, attempts = 2)
        val second = store.activePackId
        store.choosePracticePacks(setOf(first))
        store.onboardingDone = true
        store.route = "learn"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(allOf(withText("Трудные слова"), isDisplayed())).perform(click())
            val alpha = onView(allOf(withText("Alpha"), isAssignableFrom(android.widget.CheckBox::class.java)))
            val beta = onView(allOf(withText("Beta"), isAssignableFrom(android.widget.CheckBox::class.java)))
            alpha.check(matches(isChecked()))
            beta.check(matches(not(isChecked()))).perform(scrollTo(), click())
            alpha.check(matches(isChecked()))
            beta.check(matches(isChecked()))
            val activities = listOf("Пиньинь и тоны", "Аудирование", "Перевод", "Выбор иероглифа", "Написание")
            activities.forEach { name -> onView(withText(name)).perform(scrollTo(), click()) }
            onView(withText("Начать")).check(matches(not(isEnabled())))
            onView(withText("Перевод")).perform(scrollTo(), click())
            onView(withText("Написание")).perform(scrollTo(), click())
            onView(withText("Начать")).perform(click())
            val review = StudyStore(context)
            assertEquals(setOf(first, second), review.hardReviewPackIds)
            assertEquals(setOf(first), review.practicePackIds)
            assertEquals(setOf(Skill.WRITING, Skill.CHARACTER_MEANING), review.tasks().map { it.skill }.toSet())
        }
    }

    @Test fun progressReviewUsesOnlyWordsCheckedInLibraryAcrossPacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val firstPack = store.activePackId
        val first = store.words.first()
        val excluded = store.words[1]
        val second = store.words.last()
        store.engine.progress[Task(first.id, Skill.WRITING).key] = Progress(errors = 2, attempts = 2)
        store.engine.progress[Task(excluded.id, Skill.WRITING).key] = Progress(errors = 9, attempts = 9)
        store.selected.retainAll(setOf(first.id))
        store.importPack("Beta", listOf(second))
        store.engine.progress[Task(second.id, Skill.CHARACTER_MEANING).key] = Progress(errors = 1, attempts = 2)
        store.choosePracticePacks(setOf(firstPack, store.activePackId))
        store.onboardingDone = true
        store.route = "stats"; store.save()

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(startsWith("Набор: "))).check(doesNotExist())
            onView(withText("Выбранные слова · 2")).check(matches(withText("Выбранные слова · 2")))
            onView(withText("Практиковать трудные слова")).perform(click())
            onView(withText(startsWith("Наборы слов ·"))).check(doesNotExist())
            onView(withText("Начать")).perform(click())
        }

        val review = StudyStore(context)
        assertTrue(review.selectedReview)
        assertNull(review.hardReviewPackIds)
        assertEquals(setOf(first.hanzi, second.hanzi), review.studyWords.map { it.hanzi }.toSet())
        assertEquals(setOf(Skill.WRITING, Skill.CHARACTER_MEANING), review.tasks().map { it.skill }.toSet())
        review.recordAnswer(false)
        val reloaded = StudyStore(context)
        val missed = reloaded.missedReviewTasks().single()
        reloaded.beginSession(focus = listOf(missed), packReview = true, selectedReview = reloaded.selectedReview)
        reloaded.nextQuestion()
        assertEquals(missed, reloaded.current)
    }

    @Test fun multiplePacksAndSkillsSurviveReloadAndCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        var store = StudyStore(context)
        val firstPack = store.activePackId
        val firstWord = store.words.first()
        val secondWord = store.words.last()
        val firstTask = Task(firstWord.id, Skill.WRITING)
        store.engine.progress[firstTask.key] = Progress(errors = 4, attempts = 4)
        store.save()
        store.importPack("Second review pack", listOf(secondWord))
        val secondPack = store.activePackId
        val secondTask = Task(secondWord.id, Skill.CHARACTER_MEANING)
        val excludedTask = Task(secondWord.id, Skill.CHARACTER_PINYIN)
        store.engine.progress[secondTask.key] = Progress(errors = 1, attempts = 6)
        store.engine.progress[excludedTask.key] = Progress(errors = 10, attempts = 10)
        store.selected.clear()
        store.choosePracticePacks(setOf(secondPack))
        store.save()
        val packs = setOf(firstPack, secondPack)
        assertFalse(store.beginHardReview(packs, emptySet()))
        assertTrue(store.beginHardReview(packs, setOf(Skill.WRITING, Skill.CHARACTER_MEANING)))
        assertEquals(2, store.session.getInt("limit"))
        store.nextQuestion()
        assertEquals(firstWord.hanzi, store.studyWords.first { it.id == store.current!!.wordId }.hanzi)
        assertEquals(Skill.WRITING, store.current!!.skill)
        store.recordAnswer(false)
        store = StudyStore(context)
        assertFalse(store.feedback!!)
        assertEquals(1, store.missedReviewTasks().size)
        assertTrue(store.correctAnswer())
        assertTrue(store.missedReviewTasks().isEmpty())
        store.nextQuestion()
        assertEquals(Skill.CHARACTER_MEANING, store.current!!.skill)
        store.recordAnswer(false)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        store.beginSession(focus = store.missedReviewTasks(), packReview = true, reviewPacks = store.hardReviewPackIds)
        store.nextQuestion()
        assertEquals(Skill.CHARACTER_MEANING, store.current!!.skill)
        store.recordAnswer(true)
        store.leaveSession()
        store = StudyStore(context)
        assertEquals(setOf(secondPack), store.practicePackIds)
        assertTrue(store.selected.isEmpty())
        assertTrue(store.studyWords.isEmpty())
        assertEquals(8, store.engine.peek(secondTask).attempts)
        assertEquals(10, store.engine.peek(excludedTask).attempts)
        store.switchPack(firstPack)
        assertEquals(5, store.engine.peek(firstTask).attempts)
        assertEquals(4, store.engine.peek(firstTask).errors)
    }

    @Test fun rankedPackReviewSurvivesReloadCorrectionsAndReturnToMixedPractice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        var store = StudyStore(context)
        val original = store.activePackId
        val reviewWords = store.words.take(2)
        store.importPack("Повторение", reviewWords)
        val packs = setOf(original, store.activePackId)
        store.choosePracticePacks(packs)
        store.selected.clear()
        val hardest = Task(reviewWords.last().id, Skill.WRITING)
        val second = Task(reviewWords.first().id, Skill.MEANING_CHARACTER)
        store.engine.progress[hardest.key] = Progress(errors = 4, attempts = 4, due = Long.MAX_VALUE)
        store.engine.progress[second.key] = Progress(errors = 1, attempts = 6)
        val recovered = Task(reviewWords.first().id, Skill.CHARACTER_MEANING)
        store.engine.progress[recovered.key] = Progress(errors = 1, attempts = 8, mastered = true)
        store.save()
        val ranked = store.hardReviewTasks()
        assertEquals(listOf(hardest, second), ranked)
        val regularIds = store.selectedStudyWords.map { it.id }
        store.beginSession(focus = ranked, packReview = true)
        store.nextQuestion()
        assertEquals(hardest, store.current)
        assertEquals(2, store.session.getInt("limit"))
        store.recordAnswer(false)
        store = StudyStore(context)
        assertFalse(store.feedback!!)
        assertEquals(regularIds, store.selectedStudyWords.map { it.id })
        assertEquals(listOf(hardest), store.missedReviewTasks())
        assertTrue(store.correctAnswer())
        assertTrue(store.missedReviewTasks().isEmpty())
        store.nextQuestion()
        assertEquals(second, store.current)
        store.recordAnswer(false)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        assertEquals(1, store.sessionCorrect)
        store.beginSession(focus = store.missedReviewTasks(), packReview = true, reviewPacks = store.hardReviewPackIds)
        store.nextQuestion()
        assertEquals(second, store.current)
        store.recordAnswer(true)
        store.nextQuestion()
        assertTrue(store.sessionFinished)
        store.leaveSession()
        store = StudyStore(context)
        assertEquals(packs, store.practicePackIds)
        assertTrue(store.selected.isEmpty())
        assertEquals(regularIds, store.studyWords.map { it.id })
        assertEquals(8, store.engine.peek(second).attempts)
    }
}
