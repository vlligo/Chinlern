package com.example.chinlern

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.junit.Test

class SearchEditingTest {
    @Test fun inlineSearchSupportsBackspaceAndClearAfterSearching() = onWordsPage {
        val search = librarySearchField()
        search.perform(click(), typeText("shui3x"), closeSoftKeyboard())
        search.check(matches(hasFocus())).perform(pressKey(KeyEvent.KEYCODE_DEL), closeSoftKeyboard())
        search.check(matches(withText("shui3")))
        onView(withContentDescription("Очистить поиск")).perform(click())
        search.check(matches(withText(""))).perform(typeText("huo3"), closeSoftKeyboard())
        search.check(matches(withText("huo3")))
    }

    private fun onWordsPage(test: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        StudyStore(context).apply { route = "words"; onboardingDone = true; save() }
        ActivityScenario.launch(MainActivity::class.java).use { test() }
    }
}
