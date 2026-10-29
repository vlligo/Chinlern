package com.example.chinlern

import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses dummy local input only; never submits a valid registration or changes accounts. */
@RunWith(AndroidJUnit4::class)
class AccountDialogTest {
    @Test fun masksPasswordsAllowsRevealAndRequiresMatchingConfirmation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("accountDialog").apply { isAccessible = true }.invoke(activity)
            }
            onView(withHint("Пароль")).inRoot(isDialog()).check { view, error ->
                if (error != null) throw error
                val input = view as EditText
                assertTrue(input.transformationMethod is PasswordTransformationMethod)
                val layout = input.parent.parent as TextInputLayout
                val eye = layout.findViewById<android.view.View>(com.google.android.material.R.id.text_input_end_icon)
                eye.performClick()
                assertFalse(input.transformationMethod is PasswordTransformationMethod)
                eye.performClick()
                assertTrue(input.transformationMethod is PasswordTransformationMethod)
            }
            onView(withText("Создать аккаунт")).inRoot(isDialog()).perform(click())
            onView(withHint("Повторите пароль")).inRoot(isDialog()).check(matches(isDisplayed())).check { view, _ ->
                assertTrue((view as EditText).transformationMethod is PasswordTransformationMethod)
            }
            onView(withHint("Электронная почта")).inRoot(isDialog()).perform(replaceText("test@example.invalid"))
            onView(withHint("Пароль")).inRoot(isDialog()).perform(replaceText("dummy-password-1"))
            onView(withHint("Повторите пароль")).inRoot(isDialog()).perform(replaceText("dummy-password-2"), closeSoftKeyboard())
            onView(withText("Зарегистрироваться")).inRoot(isDialog()).perform(click())
            onView(withHint("Повторите пароль")).inRoot(isDialog()).check(matches(hasErrorText("Пароли не совпадают")))
            onView(withText("Уже есть аккаунт")).inRoot(isDialog()).perform(click())
            onView(withHint("Пароль")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Отмена")).inRoot(isDialog()).perform(click())
        }
    }
}
