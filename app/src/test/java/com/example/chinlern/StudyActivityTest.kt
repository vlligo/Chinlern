package com.example.chinlern

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class StudyActivityTest {
    private fun time(date: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(date)!!.time
    private fun summary(vararg dates: String, now: String = "2026-11-14 12:00") = StudyActivity.summarize(
        mapOf(Skill.WRITING to listOf(Progress(attempts = dates.size,
            activity = dates.associateWith { StudyActivity.Day(1, 1) }))), time(now))

    @Test fun streakAllowsTodayToBeUnfinishedAndBreaksAfterMissedDay() {
        assertEquals(2, summary("2026-11-12", "2026-11-13").streak)
        assertEquals(3, summary("2026-11-12", "2026-11-13", "2026-11-14").streak)
        assertEquals(0, summary("2026-11-11", "2026-11-12").streak)
        assertEquals(2, summary("2026-11-11", "2026-11-12", "2026-11-14").best)
        assertEquals(0, summary().best)
    }
    @Test fun calendarHandlesLeapDaysYearBoundaryAndDst() {
        assertEquals(3, summary("2024-02-28", "2024-02-29", "2024-03-01", now = "2024-03-01 12:00").streak)
        assertEquals(2, summary("2025-12-31", "2026-01-01", now = "2026-01-01 12:00").streak)
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(3, summary("2026-03-07", "2026-03-08", "2026-03-09", now = "2026-03-09 00:30").streak)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun activitySurvivesHistoryTruncationAndCodecRoundTrips() {
        val engine = LearningEngine()
        val task = Task("water", Skill.WRITING)
        repeat(40) { n -> engine.answer(task, n % 2 == 0, StudyActivity.shifted(time("2026-01-01 12:00"), n)) }
        val progress = engine.peek(task)
        assertEquals(Fsrs.HISTORY_LIMIT, progress.memory!!.history.size)
        assertEquals(40, progress.activity.size)
        assertEquals(progress, ProgressCodec.read(ProgressCodec.write(progress)))
        val summary = StudyActivity.summarize(mapOf(Skill.WRITING to listOf(progress)), time("2026-02-09 12:00"))
        assertEquals(40, summary.streak)
        assertEquals(40L, summary.writing)
        assertEquals(20L, summary.correct)
        assertFalse(summary.incompleteHistory)
    }
    @Test fun oldHistoryIsRecoveredAndSharedCopiesAreNotAdded() {
        val engine = LearningEngine(); val task = Task("water", Skill.WRITING)
        engine.answer(task, true, time("2026-11-14 12:00"))
        val json = ProgressCodec.write(engine.peek(task)).apply { remove("activity"); put("attempts", 10) }
        val migrated = ProgressCodec.read(json)
        assertEquals(1, migrated.activity.size)
        val shared = SharedWords.merge(listOf(migrated, migrated.copy()))!!
        val summary = StudyActivity.summarize(mapOf(Skill.WRITING to listOf(shared)), time("2026-11-14 12:00"))
        assertEquals(1, summary.days.values.single().answers)
        assertTrue(summary.incompleteHistory)
    }
}
