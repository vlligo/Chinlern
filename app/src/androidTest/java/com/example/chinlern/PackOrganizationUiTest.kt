package com.example.chinlern

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test

class PackOrganizationUiTest {
    private fun dragTo(description: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription() = "Long-press and drag to $description"
        override fun perform(ui: UiController, view: View) {
            fun find(v: View): View? {
                if (v.contentDescription == description) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            val target = requireNotNull(find(view.rootView))
            fun center(v: View): Pair<Float, Float> {
                val pos = IntArray(2); v.getLocationOnScreen(pos)
                return (pos[0] + v.width / 2f) to (pos[1] + v.height / 2f)
            }
            val (sx, sy) = center(view); val (tx, ty) = center(target)
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, y: Float) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                check(ui.injectMotionEvent(motion)); motion.recycle()
            }
            event(MotionEvent.ACTION_DOWN, sx, sy)
            ui.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            for (i in 1..15) {
                event(MotionEvent.ACTION_MOVE, sx + (tx - sx) * i / 15, sy + (ty - sy) * i / 15)
                ui.loopMainThreadForAtLeast(30)
            }
            event(MotionEvent.ACTION_UP, tx, ty); ui.loopMainThreadForAtLeast(300)
        }
    }

    @Test fun treeSupportsDraggingBulkMovesAndFolderActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("chinlearn_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = StudyStore(context)
        val first = store.activePackId
        store.renamePack("Alpha")
        store.importPack("Beta", listOf(store.words.first()))
        val second = store.activePackId
        store.movePack(second, "Lessons")
        store.choosePracticePacks(setOf(first, second))
        store.route = "words"; store.save()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun tap(text: String) = onView(withText(text)).perform(click())
            fun desc(text: String) = onView(withContentDescription(text))
            onView(withText("Управление наборами и папками")).perform(scrollToLibraryControl(), click())
            desc("Перетащить набор: Alpha").perform(dragTo("Папка: Lessons"))
            assertEquals("Lessons", StudyStore(context).packFolder(first))
            assertEquals(setOf(first, second), StudyStore(context).practicePackIds)
            desc("Выбрать набор: Alpha").perform(click())
            desc("Выбрать набор: Beta").perform(click())
            tap("Переместить выбранные (2)"); tap("＋ Новая папка")
            onView(withHint("Название папки")).perform(replaceText("Together"), closeSoftKeyboard())
            tap("Сохранить")
            assertEquals("Together", StudyStore(context).packFolder(first))
            assertEquals("Together", StudyStore(context).packFolder(second))
            desc("Действия с папкой: Together").perform(click()); tap("Переименовать папку")
            onView(withHint("Название папки")).perform(replaceText("Renamed"), closeSoftKeyboard())
            tap("Сохранить")
            desc("Действия с папкой: Renamed").perform(click()); tap("Убрать папку, сохранить наборы")
            assertEquals("", StudyStore(context).packFolder(first))
            assertEquals("", StudyStore(context).packFolder(second))
            desc("Набор: Beta").perform(click()); tap("Скрыть")
            assertTrue(StudyStore(context).isPackHidden(second))
            tap("Показывать скрытые наборы")
            desc("Набор: Beta").perform(click()); tap("Показать")
            assertFalse(StudyStore(context).isPackHidden(second))
            desc("Набор: Beta").perform(click()); tap("Переименовать / удалить"); tap("Удалить набор"); tap("Отмена")
            assertEquals(2, StudyStore(context).packNames.size)
            onView(withText("Управление наборами и папками")).perform(scrollToLibraryControl(), click())
            desc("Набор: Beta").perform(click()); tap("Переименовать / удалить"); tap("Удалить набор"); tap("Удалить")
            scenario.recreate()
            assertEquals(listOf(first), StudyStore(context).packNames.map { it.first })
        }
    }
}
