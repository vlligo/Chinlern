package com.example.chinlern

import android.util.TypedValue
import android.text.Spanned
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.data.VocabularyReference
import com.example.chinlern.texts.ReaderRubySpan
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test

class ReaderToolsUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val paragraph = "我今天去图书馆看书，也写了一些汉字。\n"

    @Test fun preservesTheAnchorThroughTypographyAndReopeningAndStartsNewRevisionAtTop() {
        val store = prepare()
        val text = paragraph.repeat(70)
        val document = store.textLibrary.save("Resume fixture", text)
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            open(document.title)
            val anchor = scrollToSpan(text, paragraph.length * 34)
            assertTrue(anchor > 0)
            onView(withContentDescription(startsWith("Позиция в тексте 4"))).check(matches(isDisplayed()))
            onView(withText("Пиньинь")).perform(click())
            awaitPinyin(text)
            onView(withContentDescription("Настройки чтения")).perform(click())
            onView(withText("34")).perform(click())
            onView(withText("Широкий")).perform(scrollToLibraryControl(), click())
            onView(withText("Готово")).perform(click())
            assertTrue(anchor in visibleLine(text))
            assertTypography(text, 34f, 12f)
            backToLibrary()
            onView(withText(document.title)).perform(click())
            assertTrue(anchor in visibleLine(text))
            assertTypography(text, 34f, 12f)

            options("Изменить текст")
            onView(withText("Сохранить")).perform(click())
            assertEquals(0, visibleLine(text).first)
            options("Версии текста")
            onView(withText("1. ${document.title}")).perform(click())
            assertTrue(anchor in visibleLine(text))
        }
        val stored = StudyStore(context)
        assertEquals(0, stored.textLibrary.readingPosition(stored.textLibrary.documents().single()))
        assertEquals(before, stored.syncSnapshot())
    }

    @Test fun savedContextJumpsToTheOriginalSpanEvenFromANewerRevision() {
        val store = prepare()
        val insertion = "窗户开着。\n"
        val originalText = paragraph.repeat(40) + insertion + paragraph.repeat(30)
        val old = store.textLibrary.save("Context fixture", originalText)
        val start = originalText.indexOf("窗户")
        store.textLibrary.annotate(store.textLibrary.select(old, start, start + 2), VocabularyReference(store.activePackId, store.words.first().id))
        val newText = "这是一份新版本。\n" + paragraph.repeat(50)
        store.textLibrary.save("Context fixture", newText, previous = old)
        ActivityScenario.launch(MainActivity::class.java).use {
            open(old.title)
            onView(withText(newText)).check(matches(isDisplayed()))
            options("Сохранённые контексты")
            onView(withText("Версия 1 · 窗户 — 窗户开着。")).perform(click())
            assertTrue(start in visibleLine(originalText))

            scrollToSpan(originalText, 0)
            assertEquals(0, visibleLine(originalText).first)
            options("Сохранённые контексты")
            onView(withText("窗户 — 窗户开着。")).perform(click())
            assertTrue(start in visibleLine(originalText))
        }
    }

    private fun prepare(): StudyStore {
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        return StudyStore(context).also { it.route = "words"; it.save() }
    }

    private fun open(title: String) {
        onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
        onView(withText(title)).perform(click())
    }

    private fun options(label: String) {
        onView(withContentDescription("Действия с текстом")).perform(click())
        onView(withText(label)).perform(click())
    }

    private fun backToLibrary() { onView(withContentDescription("Назад")).perform(click()) }

    private fun awaitPinyin(text: String) {
        repeat(60) {
            var ready = false
            onView(withText(text)).check { view, failure ->
                if (failure != null) throw failure
                val passage = (view as TextView).text as? Spanned
                ready = passage?.getSpans(0, passage.length, ReaderRubySpan::class.java)?.isNotEmpty() == true
            }
            if (ready) return
            android.os.SystemClock.sleep(50)
        }
        fail("Inline pinyin did not load")
    }

    private fun assertTypography(text: String, size: Float, spacing: Float) {
        onView(withText(text)).check { view, failure ->
            if (failure != null) throw failure
            val passage = view as TextView
            val metrics = passage.resources.displayMetrics
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics), passage.textSize, 0.1f)
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spacing, metrics), passage.lineSpacingExtra, 0.1f)
        }
    }

    private fun scrollToSpan(text: String, target: Int): Int {
        onView(withText(text)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(TextView::class.java)
            override fun getDescription() = "scroll the reader to an original text offset"
            override fun perform(controller: UiController, view: View) {
                val passage = view as TextView
                val scroll = passage.readerScroll()
                val y = passage.readerTextTop(scroll) + passage.layout.getLineTop(passage.layout.getLineForOffset(target))
                scroll.scrollTo(0, if (target == 0) 0 else y)
                controller.loopMainThreadUntilIdle()
            }
        })
        return visibleLine(text).first
    }

    private fun visibleLine(text: String): IntRange {
        var result = IntRange.EMPTY
        onView(withText(text)).check { view, failure ->
            if (failure != null) throw failure
            val passage = view as TextView
            val scroll = passage.readerScroll()
            val layout = passage.layout
            val line = layout.getLineForVertical(scroll.scrollY - passage.readerTextTop(scroll))
            result = layout.getLineStart(line) until layout.getLineEnd(line)
        }
        return result
    }
}

internal fun TextView.readerScroll(): ScrollView = generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().first()

internal fun TextView.readerTextTop(scroll: ScrollView): Int {
    val bounds = android.graphics.Rect()
    getDrawingRect(bounds)
    scroll.offsetDescendantRectToMyCoords(this, bounds)
    return bounds.top + totalPaddingTop
}
