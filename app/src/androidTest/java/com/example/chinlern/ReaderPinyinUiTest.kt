package com.example.chinlern

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.text.Spanned
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import com.example.chinlern.texts.ReaderRubySpan
import org.junit.Assert.*
import org.junit.Test
import org.hamcrest.Matchers.startsWith

class ReaderPinyinUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importsSuppliedReadingsAndGlossesAndLooksUpWordsWithoutAGloss() {
        val store = prepare()
        val before = store.syncSnapshot()
        val text = "研學點：圖書館。行。"
        val markup = "{研學點|yan2 xue2 dian3|учебная точка}：{圖書館|túshūguǎn}。{行|háng}。"
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText("Добавить текст")).perform(click())
            onView(withHint("Название текста")).perform(replaceText("Annotated fixture"), closeSoftKeyboard())
            onView(withText("Текст с пиньинем и переводами")).perform(scrollTo(), click())
            onView(withHint("Вставьте китайский текст")).perform(scrollTo(), replaceText(markup), closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            onView(withText(text)).check(matches(isDisplayed()))
            onView(withText("Пиньинь")).perform(click())
            awaitRuby(text, "háng")
            onView(withText(text)).perform(tapRuby("研學點", onReading = true))
            onView(withText("учебная точка")).check(matches(isDisplayed()))
            onView(withText("Добавить в набор")).perform(scrollToLibraryControl(), click())
            onView(withText("yánxuédiǎn · учебная точка")).perform(click())
            onView(withText(store.packName)).perform(click())
            onView(withHint("Пиньинь с цифрами · ni3 hao3")).check(matches(withText("yan2 xue2 dian3")))
            onView(withHint("Переводы / синонимы · через ;")).check(matches(withText("учебная точка")))
            pressBack()
            pressBack()

            onView(withText(text)).perform(tapRuby("圖書館", onReading = true))
            waitFor("Из текста")
            waitForSearchText("CC-CEDICT · English · CC BY-SA 4.0")
            onView(withText(startsWith("library"))).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Изменить текст")).perform(click())
            onView(withText("Текст с пиньинем и переводами")).check(matches(isChecked()))
            onView(withHint("Вставьте китайский текст")).check(matches(withText(markup)))
        }
        val saved = StudyStore(context)
        assertEquals(text, saved.textLibrary.documents().single().text)
        assertEquals(before, saved.syncSnapshot())
    }

    @Test fun savesAndDisplaysTranslationAlongsideSuppliedPinyin() {
        prepare()
        val text = "你好。"
        val translation = "Привет."
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText("Добавить текст")).perform(click())
            onView(withHint("Название текста")).perform(replaceText("Translation fixture"), closeSoftKeyboard())
            onView(withText("Текст с пиньинем и переводами")).perform(scrollTo(), click())
            onView(withHint("Вставьте китайский текст"))
                .perform(scrollTo(), replaceText("{你|nǐ}{好|hǎo}。"), closeSoftKeyboard())
            onView(withHint("Перевод текста (необязательно)"))
                .perform(scrollTo(), replaceText(translation), closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            onView(withText("Пиньинь")).perform(click())
            awaitRuby(text, "nǐ")
            activity.recreate()
            awaitRuby(text, "hǎo")
            onView(withText("Перевод")).perform(click())
            onView(withText(translation)).check(matches(isDisplayed()))
            onView(withText("Закрыть")).perform(click())
            onView(withText(text)).check(matches(isDisplayed()))
        }
        assertEquals(translation, StudyStore(context).textLibrary.documents().single().translation)
    }

    @Test fun showsPinyinOnOriginalTextAndRemembersAnOccurrenceReading() {
        val store = prepare()
        val text = "图书馆。\n圖書館。行。𠮷。Cafe\u0301🙂"
        val document = store.textLibrary.save("Inline pinyin fixture", text)
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            open(document.title)
            onView(withText("Пиньинь")).check(matches(isNotChecked())).perform(click())
            onView(withText("Пиньинь")).check(matches(isChecked()))
            val initial = awaitRuby(text, "?")
            assertEquals(listOf("图书馆", "圖書館"), initial.filterValues { it == "túshūguǎn" }.keys
                .map { text.substring(it.first, it.last + 1) })
            assertEquals("?", initial[text.indexOf('行')..text.indexOf('行')])
            assertTrue(initial.keys.none { text.indexOf("𠮷") in it })
            onView(withText(text)).perform(tapRuby("图书馆", onReading = true))
            onView(withText("图书馆")).check(matches(isDisplayed()))
            pressBack()

            onView(withText(text)).perform(tapRuby("图书馆", long = true))
            var selected = ""
            onView(withText(text)).check { view, failure ->
                if (failure != null) throw failure
                val passage = view as TextView
                selected = text.substring(minOf(passage.selectionStart, passage.selectionEnd),
                    maxOf(passage.selectionStart, passage.selectionEnd))
                assertTrue(selected.isNotEmpty() && selected in "图书馆")
            }
            assertTrue("Android’s copy action is available", floatingAction("Copy"))
            assertEquals(selected, awaitClipboard(activity))

            onView(withText(text)).perform(tapRuby("行", onReading = true))
            waitFor("Чтение в этом месте")
            onView(withText("Чтение в этом месте")).perform(click())
            onView(withText("xíng")).perform(click())
            onView(withText("Чтение в этом месте · xíng")).check(matches(isDisplayed()))
            pressBack()
            awaitRuby(text, "xíng")
            activity.recreate()
            onView(withText("Пиньинь")).check(matches(isChecked()))
            awaitRuby(text, "xíng")
            onView(withText("Пиньинь")).perform(click())
            assertTrue(readPassage(text).isEmpty())
        }
        assertEquals(text, StudyStore(context).textLibrary.documents().single().text)
        assertEquals(before, StudyStore(context).syncSnapshot())
    }

    @Test fun reflowPreservesTheOriginalAnchorOnALongPassage() {
        val store = prepare()
        val text = "图书馆與銀行。Cafe\u0301🙂。\n".repeat(1250)
        assertEquals(20000, text.length)
        val document = store.textLibrary.save("Long pinyin fixture", text)
        val target = text.indexOf("图书馆", text.length / 2)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            open(document.title)
            onView(withText(text)).perform(object : ViewAction {
                override fun getConstraints() = isAssignableFrom(TextView::class.java)
                override fun getDescription() = "scroll to a known original-text anchor"
                override fun perform(controller: UiController, view: View) {
                    val passage = view as TextView
                    val layout = passage.layout
                    val scroll = passage.readerScroll()
                    scroll.scrollTo(0, passage.readerTextTop(scroll) + layout.getLineTop(layout.getLineForOffset(target)))
                    controller.loopMainThreadUntilIdle()
                }
            })
            val originalAnchor = visibleLine(text).first
            assertTrue(originalAnchor > text.length / 3)
            onView(withText("Пиньинь")).perform(click())
            awaitRuby(text, "túshūguǎn")
            assertTrue("the first visible line contains the original anchor", originalAnchor in visibleLine(text))
            rotate(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            awaitRuby(text, "túshūguǎn")
            assertTrue("rotation restores the original anchor", originalAnchor in visibleLine(text))
            rotate(activity, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            awaitRuby(text, "túshūguǎn")
            onView(withText("Пиньинь")).perform(click())
            assertEquals(originalAnchor, visibleLine(text).first)
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

    private fun rotate(activity: ActivityScenario<MainActivity>, request: Int, expected: Int) {
        activity.onActivity { it.requestedOrientation = request }
        repeat(60) {
            var reached = false
            activity.onActivity { reached = it.resources.configuration.orientation == expected }
            if (reached) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Reader did not rotate to orientation $expected")
    }

    private fun visibleLine(text: String): IntRange {
        lateinit var result: IntRange
        onView(withText(text)).check { view, failure ->
            if (failure != null) throw failure
            val reading = view as TextView
            val scroll = reading.readerScroll()
            val layout = reading.layout
            val line = layout.getLineForVertical(scroll.scrollY - reading.readerTextTop(scroll))
            result = layout.getLineStart(line) until layout.getLineEnd(line)
        }
        return result
    }

    private fun readPassage(text: String): Map<IntRange, String> {
        var result = emptyMap<IntRange, String>()
        onView(withText(text)).check { view, failure ->
            if (failure != null) throw failure
            val passage = view as TextView
            assertEquals(text, passage.text.toString())
            val contents = passage.text as? Spanned ?: return@check
            result = contents.getSpans(0, contents.length, ReaderRubySpan::class.java).associate { ruby ->
                val start = contents.getSpanStart(ruby)
                val end = contents.getSpanEnd(ruby)
                assertEquals("a word and its reading stay on one line",
                    passage.layout.getLineForOffset(start), passage.layout.getLineForOffset(end - 1))
                (start until end) to ruby.reading
            }
        }
        return result
    }

    private fun awaitRuby(text: String, reading: String): Map<IntRange, String> {
        repeat(60) {
            val result = readPassage(text)
            if (reading in result.values) return result
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("No inline annotation for $reading")
    }

    @Suppress("DEPRECATION")
    private fun tapRuby(word: String, onReading: Boolean = false, long: Boolean = false) = GeneralClickAction(
        if (long) Tap.LONG else Tap.SINGLE,
        { view ->
            val passage = view as TextView
            val layout = passage.layout
            val contents = passage.text as Spanned
            val start = contents.indexOf(word)
            val end = start + word.length
            val span = contents.getSpans(start, end, ReaderRubySpan::class.java).single { contents.getSpanStart(it) == start }
            val location = IntArray(2).also(view::getLocationOnScreen)
            val line = layout.getLineForOffset(start)
            val y = if (onReading) layout.getLineBaseline(line) + passage.paint.ascent() - 6
                else layout.getLineBaseline(line) + passage.paint.ascent() / 2
            floatArrayOf(location[0] + passage.totalPaddingLeft + layout.getPrimaryHorizontal(start) +
                span.getSize(passage.paint, contents, start, end, null) / 2f, location[1] + passage.totalPaddingTop + y)
        }, Press.FINGER
    )

    private fun waitFor(text: String) {
        repeat(60) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (_: androidx.test.espresso.NoMatchingViewException) {
                android.os.SystemClock.sleep(50)
            }
        }
        throw AssertionError("View not found for $text")
    }

    private fun awaitClipboard(activity: ActivityScenario<MainActivity>): String? {
        repeat(30) {
            var copied: String? = null
            activity.onActivity {
                copied = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
            }
            if (copied != null) return copied
            android.os.SystemClock.sleep(100)
        }
        return null
    }

    private fun floatingAction(label: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(30) {
            val node = automation.windows.firstNotNullOfOrNull { find(it.root) }
            if (node != null) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            android.os.SystemClock.sleep(100)
        }
        return false
    }
}
