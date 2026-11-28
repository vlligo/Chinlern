package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test

class DictionaryScreenTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bookmarksOneReadingAndRestoresTheSearchThroughRecreation() {
        val store = prepare()
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            openAndSearch("行")
            openLibraryResult("行")
            waitForSearchText("Выбрать чтение").perform(scrollToLibraryControl(), click())
            onView(withText("xíng")).perform(click())
            onView(withText("В закладки")).perform(scrollToLibraryControl(), click())
            onView(withText("Убрать закладку")).check(matches(isDisplayed()))
            assertEquals("xing2", StudyStore(context).dictionaryBookmarks.all().single().pinyin)
            pressBack()
            librarySearchField().check(matches(withText("行")))

            activity.recreate()
            librarySearchField().check(matches(withText("行")))
            openLibraryResult("行")
            onView(withContentDescription("Закладки")).perform(scrollToLibraryControl(), click())
            onView(withText(startsWith("行 · xíng"))).perform(click())
            onView(withText("xíng")).check(matches(isDisplayed()))
            onView(withText("Убрать закладку")).perform(scrollToLibraryControl(), click())
            pressBack()
            pressBack()
            librarySearchField().check(matches(withText("行")))
        }
        val restored = StudyStore(context)
        assertTrue(restored.dictionaryBookmarks.all().isEmpty())
        assertEquals(before, restored.syncSnapshot())
    }

    @Test fun dictionaryMeaningPrefillsTheEditorAndSavesToTheChosenPack() {
        val store = prepare()
        val active = store.activePackId
        store.importPack("Dictionary target", listOf(store.words.first()))
        val target = store.activePackId
        store.editPack(active)
        store.route = "words"
        store.save()
        ActivityScenario.launch(MainActivity::class.java).use {
            openAndSearch("圖書館")
            openLibraryResult("圖書館")
            onView(withText("图书馆 / 圖書館")).check(matches(isDisplayed()))
            onView(withText("túshūguǎn")).check(matches(isDisplayed()))

            fun chooseMeaning() {
                waitForSearchText("Добавить в набор").perform(scrollToLibraryControl(), click())
                onView(withText("library")).perform(click())
                onView(withText("Dictionary target")).perform(click())
            }
            chooseMeaning()
            onView(withHint("Иероглифы")).check(matches(withText("圖書館")))
            onView(withHint("Переводы / синонимы · через ;")).check(matches(withText("library")))
            onView(withHint("Пиньинь с диакритикой")).check(matches(withText("túshūguǎn")))
            onView(withText("Сохранить")).perform(click())
            onView(withText("图书馆 / 圖書館")).check(matches(isDisplayed()))
            onView(withText("Карточка · Dictionary target")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            chooseMeaning()
            onView(withText("Сохранить")).perform(click())
            pressBack()
            librarySearchField().check(matches(withText("圖書館")))
            openLibraryResult("圖書館")
            waitForSearchText("Карточка · Dictionary target").perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("Викисловарь · Русский · CC BY-SA 4.0")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            waitForSearchText("CC-CEDICT · English · CC BY-SA 4.0").perform(scrollToLibraryControl()).check(matches(isDisplayed()))
        }
        val restored = StudyStore(context)
        val word = restored.packWords(target).single { it.hanzi == "圖書館" }
        assertEquals(listOf("图书馆"), word.alternatives)
        assertEquals(listOf("library"), word.meanings)
        assertEquals(active, restored.activePackId)
        assertFalse(restored.words.any { it.hanzi == "圖書館" })
    }

    private fun prepare(): StudyStore {
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        return StudyStore(context).also { it.route = "words"; it.save() }
    }

    private fun openAndSearch(query: String) {
        onView(withText("Открыть словарь")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        filterLibraryCards(query)
    }

    @Test fun searchesInRussianAndOpensTheWiktionaryArticle() {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {
            openAndSearch("библиотека")
            openLibraryResult("图书馆")
            onView(withText("Викисловарь · Русский · CC BY-SA 4.0")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("библиотека")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("Статья в Викисловаре · 图书馆")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            waitForSearchText("CC-CEDICT · English · CC BY-SA 4.0").perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText(startsWith("library"))).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("В закладки")).perform(scrollToLibraryControl(), click())
        }
        val bookmark = StudyStore(context).dictionaryBookmarks.all().single()
        assertEquals("ruwiktionary", bookmark.source.id)
    }

}
