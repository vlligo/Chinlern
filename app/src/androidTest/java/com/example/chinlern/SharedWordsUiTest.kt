package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SharedWordsUiTest {
    @Test fun knowledgeCheckboxUpdatesTheOtherCardWhileStudySelectionRemainsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        val word = store.words.first()
        store.importPack("Second", listOf(word.copy(id = "copy")))
        store.choosePracticePacks(store.packNames.map { it.first }.toSet())
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollToLibraryControl(), replaceText("Second"), closeSoftKeyboard())
            onView(withContentDescription("Умею писать: 水 · Second")).perform(scrollToLibraryControl(), click())
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollToLibraryControl(), replaceText("Мои слова 水"), closeSoftKeyboard())
            onView(withContentDescription("Умею писать: 水 · Мои слова")).perform(scrollToLibraryControl()).check(matches(isChecked()))
            onView(withContentDescription("Учить 水 · Мои слова")).perform(scrollToLibraryControl(), click())
            assertEquals(16, StudyStore(context).studyWords.size)
            assertFalse(StudyStore(context).libraryPacks().first { it.name == "Мои слова" }.selected.contains(word.id))
            scenario.recreate()
            onView(withHint("Найти слово, пиньинь или перевод")).perform(scrollToLibraryControl(), replaceText("Second"), closeSoftKeyboard())
            onView(withContentDescription("Умею писать: 水 · Second")).perform(scrollToLibraryControl()).check(matches(isChecked()))
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(activity.cacheDir, "shared-words-preview.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
}
