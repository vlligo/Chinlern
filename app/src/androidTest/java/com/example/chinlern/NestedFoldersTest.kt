package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NestedFoldersTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun subtreeOperationsAreAtomicAndSurviveBackupAndReload() {
        val profile = "nested-${System.nanoTime()}"
        var store = StudyStore(context, profile)
        val first = store.activePackId
        store.movePack(first, "A/Child/Leaf")
        store.importPack("Second", listOf(store.words.first()))
        val second = store.activePackId
        store.movePack(second, "B")
        val before = store.syncSnapshot()
        try { store.moveFolder("A", "A/Child"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.syncSnapshot())
        store.moveFolder("A", "B")
        assertEquals("B/A/Child/Leaf", store.packFolder(first))
        store.renameFolder("B/A", "Renamed")
        store = StudyStore(context, profile)
        assertEquals("B/Renamed/Child/Leaf", store.packFolder(first))
        assertEquals(store.syncSnapshot(), store.inspectBackup(store.exportBackup()).packs)
        store.removeFolder("B/Renamed")
        assertEquals("B/Child/Leaf", store.packFolder(first))
        store.moveFolder("B/Child", "")
        assertEquals("Child/Leaf", store.packFolder(first))
        assertEquals("B", store.packFolder(second))
        store.movePack(second, "Child/Other")
        val collision = store.syncSnapshot()
        try { store.renameFolder("Child/Leaf", "Other"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(collision, store.syncSnapshot())
    }

    @Test fun folderMenuNestsWholeBranchAndBothTreesCollapseDescendants() {
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        val first = store.activePackId
        store.renamePack("Lesson")
        store.movePack(first, "Lessons/Unit")
        store.importPack("Other", listOf(store.words.first()))
        val second = store.activePackId
        store.movePack(second, "Archive")
        store.choosePracticePacks(setOf(first, second))
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            fun tap(text: String) = onView(withText(text)).perform(click())
            fun desc(text: String) = onView(withContentDescription(text))
            onView(withText("Управление наборами и папками")).perform(scrollToLibraryControl(), click())
            desc("Действия с папкой: Lessons").perform(click()); tap("Переместить папку"); tap("Archive")
            assertEquals("Archive/Lessons/Unit", StudyStore(context).packFolder(first))
            desc("Папка: Archive/Lessons/Unit").perform(click())
            desc("Набор: Lesson").check(matches(isDisplayed()))
            desc("Папка: Archive").perform(click())
            desc("Набор: Lesson").check(doesNotExist())
            desc("Папка: Archive").perform(click())
            desc("Набор: Lesson").check(matches(isDisplayed()))
            tap("Готово")
            onView(libraryPracticePicker(2)).perform(scrollToLibraryControl(), click())
            desc("Учить набор: Archive/Lessons/Unit / Lesson").check(matches(isChecked()))
            desc("Папка: Archive, выбрано 2 из 2").perform(click())
            desc("Учить набор: Archive/Lessons/Unit / Lesson").check(doesNotExist())
            tap("Применить")
            assertEquals(setOf(first, second), StudyStore(context).practicePackIds)
        }
    }
}
