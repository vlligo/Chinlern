package com.example.chinlern

import android.graphics.Rect
import android.view.View
import android.widget.ListView
import androidx.core.widget.NestedScrollView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf

/** The pager keeps a second practice picker attached on the Practice page. */
fun libraryPracticePicker(count: Int): Matcher<View> = allOf(
    withText("Наборы для практики: $count ▾"),
    isDescendantOfA(isAssignableFrom(ListView::class.java))
)

/** Scroll existing library header/card controls into view on the recycled list. */
fun scrollToLibraryControl() = object : ViewAction {
    override fun getDescription() = "scroll to a library control"
    override fun getConstraints(): Matcher<View> = anyOf(isDisplayed(),
        isDescendantOfA(isAssignableFrom(ListView::class.java)),
        isDescendantOfA(isAssignableFrom(NestedScrollView::class.java)), ViewActions.scrollTo().constraints)
    override fun perform(uiController: UiController, view: View) {
        var parent = view.parent
        var list: ListView? = null
        while (parent is View) {
            if (parent is ListView) { list = parent; break }
            parent = parent.parent
        }
        if (list == null) {
            if (!isDisplayed().matches(view)) {
                if (isDescendantOfA(isAssignableFrom(NestedScrollView::class.java)).matches(view)) {
                    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                    uiController.loopMainThreadUntilIdle()
                } else {
                    ViewActions.scrollTo().perform(uiController, view)
                }
            }
        }
        else {
            // ListView does not forward nested child rectangles like ScrollView does.
            // Respect bottom padding so the dock cannot cover the target of a tap.
            val scroller = list
            repeat(3) {
                val target = IntArray(2); view.getLocationOnScreen(target)
                val bounds = IntArray(2); scroller.getLocationOnScreen(bounds)
                val top = bounds[1] + scroller.paddingTop
                val bottom = bounds[1] + scroller.height - scroller.paddingBottom
                val delta = when {
                    target[1] < top -> target[1] - top
                    target[1] + view.height > bottom -> target[1] + view.height - bottom
                    else -> 0
                }
                scroller.scrollListBy(delta)
                uiController.loopMainThreadUntilIdle()
            }

        }
    }
}

fun waitForSearch(row: ViewInteraction): ViewInteraction {
    val deadline = android.os.SystemClock.uptimeMillis() + 15000
    while (true) {
        try {
            row.check { _, missing -> if (missing != null) throw missing }
            return row
        } catch (missing: NoMatchingViewException) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) throw missing
            android.os.SystemClock.sleep(100)
        }
    }
}

fun waitForSearchText(text: String) = waitForSearch(onView(withText(text)))

fun librarySearchField() = onView(withHint("Найти слово, пиньинь или перевод"))

fun filterLibraryCards(query: String) {
    librarySearchField().perform(ViewActions.replaceText(query), ViewActions.closeSoftKeyboard())
    val deadline = android.os.SystemClock.uptimeMillis() + 15000
    while (true) {
        try {
            onView(withText("Поиск…")).check(doesNotExist())
            return
        } catch (pending: AssertionError) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) throw pending
            android.os.SystemClock.sleep(100)
        }
    }
}

fun openLibraryResult(form: String) {
    waitForSearch(onView(withContentDescription("Подробнее · $form"))).perform(scrollToLibraryControl(), ViewActions.click())
}
