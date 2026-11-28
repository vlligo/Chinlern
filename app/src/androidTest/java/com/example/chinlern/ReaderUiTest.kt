package com.example.chinlern

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chinlern.data.ProfileStorage
import org.junit.Assert.*
import org.junit.Test

class ReaderUiTest {
    @Test fun pastedTextPersistsAndReadingDoesNotChangeLearning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        store.route = "words"; store.save()
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText("Добавить текст")).perform(click())
            onView(withHint("Название текста")).perform(replaceText("Reader fixture"))
            onView(withHint("Вставьте китайский текст")).perform(replaceText("水"), closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            onView(withText("水")).check(matches(isDisplayed()))
            onView(withText("水")).perform(tapCharacter(0, long = true))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun findAction(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (index in 0 until node.childCount) findAction(node.getChild(index), label)?.let { return it }
                return null
            }
            fun clickFloatingAction(label: String): Boolean {
                repeat(20) {
                    val node = automation.windows.firstNotNullOfOrNull { findAction(it.root, label) }
                        ?: findAction(automation.rootInActiveWindow, label)
                    if (node != null) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    android.os.SystemClock.sleep(100)
                }
                return false
            }
            fun activateFloatingAction(label: String) {
                if (!clickFloatingAction(label)) {
                    assertTrue("Selection toolbar overflow is available", clickFloatingAction("More options"))
                    assertTrue("Selection action is available for $label", clickFloatingAction(label))
                }
            }
            activateFloatingAction("Найти слово")
            waitForText("Мои слова")
            waitForSearchText("Сохранить контекст").perform(scrollToLibraryControl(), click())
            pressBack()

            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Сохранённые контексты")).perform(click())
            onView(withText("水 — 水")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("水")).perform(tapCharacter(0, long = true))
            activateFloatingAction("Учить фрагмент")
            waitForText("Скрыть текст")
            pressBack()
        }
        val restored = StudyStore(context)
        assertEquals("水", restored.textLibrary.documents().single().text)
        assertEquals("水", restored.textRecall.queue().due.single().quote)
        assertEquals(before, restored.syncSnapshot())
    }

    @Test fun tapShowsWholeDictionaryWordAndSavesToChosenPack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val originalPack = store.activePackId
        store.importPack("Reader target", listOf(store.words.first()))
        val targetPack = store.activePackId
        store.editPack(originalPack)
        store.route = "words"
        store.save()
        val text = "我今天去图书馆。"
        val document = store.textLibrary.save("Dictionary fixture", text)

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText(document.title)).perform(click())
            onView(withText(text)).perform(tapCharacter(text.indexOf('书')))
            waitForText("túshūguǎn")
            onView(withText("图书馆")).check(matches(isDisplayed()))
            onView(withText("Викисловарь · Русский · CC BY-SA 4.0")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("библиотека")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withContentDescription("Порядок черт · 图书馆")).perform(scrollToLibraryControl(), click())
            waitForSearch(onView(withContentDescription("Закрыть порядок черт"))).perform(click())
            val lookupSheet = withDecorView(hasDescendant(withText("Изменить границы")))
            waitForSearch(onView(withText("túshūguǎn")).inRoot(lookupSheet))
                .perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("Изменить границы")).inRoot(lookupSheet).perform(scrollToLibraryControl(), click())
            onView(withText("Конец вправо")).perform(click())
            onView(withText("Найти слово")).perform(click())
            waitForText("图书馆。")
            waitForText("Совпадений нет.")
            pressBack()

            onView(withText(text)).perform(tapCharacter(text.indexOf('馆')))
            waitForText("túshūguǎn")
            onView(withText("Добавить в набор")).perform(scrollToLibraryControl(), click())
            onView(withText("túshūguǎn · library")).perform(click())
            onView(withText("Reader target")).perform(click())
            onView(withHint("Иероглифы")).check(matches(withText("图书馆")))
            onView(withHint("Пиньинь с диакритикой")).check(matches(withText("túshūguǎn")))
            onView(withHint("Переводы / синонимы · через ;")).check(matches(withText("library")))
            onView(withHint("Пример на китайском (необязательно)")).check(matches(withText(text)))
            onView(withHint("Похожие неверные знаки · через ;")).perform(scrollTo(), replaceText("大;小;水"))
            onView(withHint("Пиньинь примера")).perform(scrollTo(), replaceText("Wǒ jīntiān qù túshūguǎn."))
            onView(withHint("Перевод примера на русский")).perform(scrollTo(), replaceText("Сегодня я иду в библиотеку."), closeSoftKeyboard())
            onView(withText("Сохранить")).perform(click())
            waitForText(text)
            onView(withContentDescription("Действия с текстом")).perform(click())
            onView(withText("Сохранённые контексты")).perform(click())
            onView(withText("图书馆 — $text")).check(matches(isDisplayed()))
        }
        val saved = StudyStore(context)
        val word = saved.packWords(targetPack).single { it.hanzi == "图书馆" }
        assertEquals(listOf("library"), word.meanings)
        assertEquals(originalPack, saved.activePackId)
        assertEquals(listOf("图书馆 — $text"), saved.textLibrary.savedContexts(document))
        saved.editPack(targetPack)
        assertTrue(word.id in saved.selected)
    }

    @Test fun customCardCanBeCancelledAndARepeatedSaveOffersTheExistingCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        store.route = "words"
        val before = store.syncSnapshot()
        val text = "𠮷。"
        val document = store.textLibrary.save("Custom fixture", text)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Мои тексты")).perform(scrollToLibraryControl(), click())
            onView(withText(document.title)).perform(click())
            onView(withText(text)).perform(tapCharacter(0))
            waitForText("Совпадений нет.")

            fun choosePack() = onView(withText(store.packName)).perform(click())
            fun fillExample() {
                onView(withHint("Похожие неверные знаки · через ;")).perform(scrollTo(), replaceText("大;小;水"))
                onView(withHint("Пиньинь примера")).perform(scrollTo(), replaceText("Jí."))
                onView(withHint("Перевод примера на русский")).perform(scrollTo(), replaceText("Удача."), closeSoftKeyboard())
            }
            onView(withText("Добавить в набор")).perform(scrollToLibraryControl(), click())
            choosePack()
            onView(withHint("Иероглифы")).check(matches(withText("𠮷")))
            onView(withText("Отмена")).perform(click())
            assertEquals(before, StudyStore(context).syncSnapshot())

            onView(withText("Добавить в набор")).perform(scrollToLibraryControl(), click())
            choosePack()
            onView(withHint("Пиньинь с диакритикой")).perform(replaceText("jí"))
            onView(withHint("Пиньинь с цифрами · ni3 hao3")).perform(replaceText("ji2"))
            onView(withHint("Переводы / синонимы · через ;")).perform(replaceText("удача"))
            fillExample()
            onView(withText("Сохранить")).perform(click())
            waitForText(text)

            onView(withText(text)).perform(tapCharacter(0))
            waitForText("Мои слова")
            onView(withText("Добавить в набор")).perform(scrollToLibraryControl(), click())
            onView(withText("jí · удача")).perform(click())
            choosePack()
            fillExample()
            onView(withText("Сохранить")).perform(click())
            onView(withText("Карточка уже есть в наборе")).check(matches(isDisplayed()))
            waitForSearchText("Сохранить контекст").perform(scrollToLibraryControl(), click())
            waitForText(text)
        }
        val saved = StudyStore(context)
        assertEquals(1, saved.words.count { it.hanzi == "𠮷" })
        assertEquals(listOf("𠮷 — 𠮷。"), saved.textLibrary.savedContexts(document))
    }

    private fun tapCharacter(offset: Int, long: Boolean = false) = androidx.test.espresso.action.GeneralClickAction(
        if (long) androidx.test.espresso.action.Tap.LONG else androidx.test.espresso.action.Tap.SINGLE,
        { view ->
            val text = view as android.widget.TextView
            val layout = text.layout
            val line = layout.getLineForOffset(offset)
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            val end = offset + Character.charCount(Character.codePointAt(text.text, offset))
            val x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(end)) / 2
            floatArrayOf(location[0] + text.totalPaddingLeft + x,
                location[1] + text.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)
        }, androidx.test.espresso.action.Press.FINGER)

    private fun waitForText(text: String) {
        waitForSearchText(text).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
    }
}
