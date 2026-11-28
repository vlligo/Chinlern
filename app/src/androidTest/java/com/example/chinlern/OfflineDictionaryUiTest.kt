package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test

class OfflineDictionaryUiTest {
    @Test fun absentPersonalWordHasOfflineDefinitionAndDoesNotCreateLearningCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        store.route = "words"; store.save()
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            filterLibraryCards("电脑")
            val strokes = waitForSearch(onView(withContentDescription("Порядок черт · 电脑")))
            strokes.perform(scrollToLibraryControl(), click())
            onView(withContentDescription("Закрыть порядок черт")).check(matches(isDisplayed())).perform(click())
            waitForSearch(librarySearchField()).check(matches(withText("电脑")))
            openLibraryResult("电脑")
            onView(withText("电脑 / 電腦")).check(matches(isDisplayed()))
            waitForSearch(onView(withText(containsString("computer")))).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
        }
        assertEquals(before, StudyStore(context).syncSnapshot())
    }
}
