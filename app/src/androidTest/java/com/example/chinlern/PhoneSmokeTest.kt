package com.example.chinlern

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only. No reset, account operations, word edits or selection changes. */
@RunWith(AndroidJUnit4::class)
class PhoneSmokeTest {
    @Test fun inspectWordsAndPickerWithoutChangingLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneSmoke") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var originalRoute = ""
            var before = emptyMap<String, String>()
            var chosen = emptySet<String>()
            fun store(activity: MainActivity) = MainActivity::class.java.getDeclaredField("store")
                .apply { isAccessible = true }.get(activity) as StudyStore
            fun render(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("render")
                .apply { isAccessible = true }.invoke(activity)
            scenario.onActivity { activity ->
                val data = store(activity)
                assertFalse("Run with sync disabled to avoid changing cloud state", data.syncAccess.enabled)
                originalRoute = data.route; before = data.syncSnapshot(); chosen = data.practicePackIds.toSet()
                data.route = "words"; render(activity)
            }
            try {
                onView(withText("Ваши слова")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(image))
                    java.io.File(activity.cacheDir, "phone-words.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
                onView(withText("Наборы для практики: ${chosen.size} ▾")).perform(scrollTo(), click())
                onView(withText("Наборы для практики")).check(matches(isDisplayed()))
                onView(withText("Отмена")).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(before, store(activity).syncSnapshot())
                    assertEquals(chosen, store(activity).practicePackIds)
                }
            } finally {
                scenario.onActivity { activity -> store(activity).route = originalRoute; render(activity) }
            }
        }
    }
}
