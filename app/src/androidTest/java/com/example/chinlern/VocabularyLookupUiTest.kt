package com.example.chinlern

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test

class VocabularyLookupUiTest {
    @Test fun hiddenPackSearchOpensDetailsAndReturnsToQueryWithoutChangingProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val original = store.activePackId
        val word = store.words.first().copy(id = "lookup", meanings = listOf("уникальный перевод"), hint = "Заметка для поиска")
        store.importPack("Hidden lookup", listOf(word))
        store.setPackHidden(store.activePackId, true)
        store.switchPack(original)
        store.route = "words"; store.save()
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            filterLibraryCards("уникальный")
            openLibraryResult("水")
            onView(withText("уникальный перевод")).check(matches(isDisplayed()))
            onView(withText("CC-CEDICT · English · CC BY-SA 4.0")).perform(scrollToLibraryControl()).check(matches(isDisplayed()))
            onView(withText("Карточка · Hidden lookup")).perform(scrollToLibraryControl(), click())
            onView(withText("Заметка для поиска")).check(matches(isDisplayed()))
            onView(withText("Наборы: Hidden lookup")).check(matches(isDisplayed()))
            pressBack()
            pressBack()
            librarySearchField().check(matches(withText("уникальный")))
            filterLibraryCards("несуществующее")
            waitForSearchText("Совпадений нет.").check(matches(isDisplayed()))
            pressBack()
        }
        assertEquals(before, StudyStore(context).syncSnapshot())
        assertEquals(original, StudyStore(context).activePackId)
    }

    @Test fun selectedExampleTextOpensLookupAndReturnsToOriginalDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.example.chinlern.data.ProfileStorage.open(context, "chinlearn_v1").edit().clear().commit()
        val store = StudyStore(context)
        val water = store.words.first().copy(example = "学习", examplePinyin = "xué xí", exampleTranslation = "учиться")
        store.replaceWord(water)
        store.importPack("Selection fixture", listOf(Word("study", "学习", "xué xí", "xue2 xi2", listOf("учиться"), listOf("水", "火", "木"))))
        store.route = "words"; store.save()
        val before = store.syncSnapshot()
        ActivityScenario.launch(MainActivity::class.java).use {
            filterLibraryCards("shui3")
            openLibraryResult("水")
            onView(withText("Карточка · Мои слова")).perform(scrollToLibraryControl(), click())
            onView(withText("学习")).perform(scrollTo(), androidx.test.espresso.action.GeneralClickAction(
                androidx.test.espresso.action.Tap.LONG,
                { view ->
                    val text = view as android.widget.TextView
                    val location = IntArray(2).also { view.getLocationOnScreen(it) }
                    floatArrayOf(location[0] + text.totalPaddingLeft + text.textSize / 2,
                        location[1] + text.totalPaddingTop + text.layout.getLineBottom(0) / 2f)
                }, androidx.test.espresso.action.Press.FINGER))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun findNode(
                node: android.view.accessibility.AccessibilityNodeInfo?,
                matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
            ): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (matches(node)) return node
                for (index in 0 until node.childCount) findNode(node.getChild(index), matches)?.let { return it }
                return null
            }
            fun findInWindows(matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) =
                automation.windows.firstNotNullOfOrNull { findNode(it.root, matches) }
                    ?: findNode(automation.rootInActiveWindow, matches)
            fun clickFloatingAction(label: String): Boolean {
                repeat(20) {
                    val node = findInWindows {
                        it.text?.toString() == label || it.contentDescription?.toString() == label
                    }
                    if (node != null) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    android.os.SystemClock.sleep(100)
                }
                return false
            }
            if (!clickFloatingAction("Найти слово")) {
                assertTrue("Selection toolbar overflow is available", clickFloatingAction("More options"))
                assertTrue("Lookup selection action is available", clickFloatingAction("Найти слово"))
            }
            // The floating toolbar briefly owns window focus after dispatching its action.
            fun selectedQueryVisible() = findInWindows {
                it.className == "android.widget.EditText" && it.text?.toString() == "学习"
            } != null
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (!selectedQueryVisible() && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            assertTrue("The selected example text reached the search field", selectedQueryVisible())
            openResult("学习 —")
            onView(withText("Карточка · Selection fixture")).perform(scrollToLibraryControl(), click())
            onView(withText("Наборы: Selection fixture")).check(matches(isDisplayed()))
            pressBack()
            pressBack()
            onView(withHint("Слово или перевод")).check(matches(withText("学习")))
            pressBack()
            onView(withText("学习")).check(matches(isDisplayed()))
            onView(withText("水")).check(matches(isDisplayed()))
        }
        assertEquals(before, StudyStore(context).syncSnapshot())
    }


    private fun openResult(prefix: String) {
        val row = onView(withText(startsWith(prefix)))
        waitForSearch(row).perform(scrollToLibraryControl(), click())
    }

}
