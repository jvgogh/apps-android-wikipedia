package org.wikipedia.login

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.login.LoginViewModel.LoginEvent

@RunWith(RobolectricTestRunner::class)
class LoginViewModelTest {
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        viewModel = LoginViewModel(SavedStateHandle(mapOf(LoginActivity.LOGIN_REQUEST_SOURCE to LoginActivity.SOURCE_NAV)))
    }

    private fun result(status: String = LoginResult.STATUS_UI, message: String? = null) =
        LoginResult(WikipediaApp.instance.wikiSite, status, "user", "pw", message)

    @Test
    fun loginSourceIsReadFromArguments() {
        assertEquals(LoginActivity.SOURCE_NAV, viewModel.loginSource)
    }

    @Test
    fun loginButtonRequiresUsernameAndPassword() {
        assertFalse(viewModel.uiState.value.isLoginEnabled)
        viewModel.onUsernameChange("user")
        assertFalse(viewModel.uiState.value.isLoginEnabled)
        viewModel.onPasswordChange("pw")
        assertTrue(viewModel.uiState.value.isLoginEnabled)
    }

    @Test
    fun invalidUsernameShowsErrorAndDoesNotStartLogin() {
        viewModel.onUsernameChange("bad[name]")
        viewModel.onPasswordChange("pw")
        viewModel.validateThenLogin()
        assertTrue(viewModel.uiState.value.showUsernameError)
        assertFalse(viewModel.uiState.value.isLoading)
        viewModel.onUsernameChange("good")
        assertTrue(viewModel.uiState.value.showUsernameError)
    }

    @Test
    fun usernameAndPasswordSurviveStateRestoration() {
        val handle = SavedStateHandle()
        LoginViewModel(handle).apply {
            onUsernameChange("user")
            onPasswordChange("pw")
        }
        val restored = LoginViewModel(handle).uiState.value
        assertEquals("user", restored.username)
        assertEquals("pw", restored.password)
    }

    @Test
    fun oathPromptShowsTwoFactorFieldAndRequiresCode() {
        viewModel.onUsernameChange("user")
        viewModel.onPasswordChange("pw")
        viewModel.loginCallback.uiPrompt(LoginOATHResult(WikipediaApp.instance.wikiSite, LoginResult.STATUS_UI, "user", "pw", null),
            RuntimeException("2fa"))
        var state = viewModel.uiState.value
        assertEquals(R.string.login_2fa_hint, state.twoFactorHint)
        assertTrue(state.isTwoFactorVisible)
        assertFalse(state.isLoginEnabled)
        viewModel.onTwoFactorChange("123456")
        state = viewModel.uiState.value
        assertTrue(state.isLoginEnabled)
    }

    @Test
    fun emailAuthPromptUsesEmailHint() {
        viewModel.loginCallback.uiPrompt(LoginEmailAuthResult(WikipediaApp.instance.wikiSite, LoginResult.STATUS_UI, "user", "pw", null),
            RuntimeException("email"))
        assertEquals(R.string.login_email_auth_hint, viewModel.uiState.value.twoFactorHint)
    }

    @Test
    fun captchaPromptShowsCaptchaAndRequiresWord() {
        viewModel.onUsernameChange("user")
        viewModel.onPasswordChange("pw")
        viewModel.loginCallback.uiPrompt(result(), RuntimeException("captcha"), captchaId = "abc")
        val captcha = viewModel.uiState.value.captcha
        assertNotNull(captcha)
        assertTrue(captcha!!.imageUrl.contains("wpCaptchaId=abc"))

        viewModel.validateThenLogin()
        assertTrue(viewModel.uiState.value.captcha!!.showError)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.onCaptchaWordChange("word")
        assertFalse(viewModel.uiState.value.captcha!!.showError)
    }

    @Test
    fun genericErrorShowsErrorViewAndResetsAuthState() {
        viewModel.loginCallback.uiPrompt(result(), RuntimeException("captcha"), captchaId = "abc")
        viewModel.loginCallback.error(RuntimeException("boom"))
        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertNull(state.captcha)
        assertFalse(state.isTwoFactorVisible)
        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun failedLoginEmitsMessage() = runBlocking {
        viewModel.loginCallback.success(result(LoginResult.STATUS_FAIL, "nope"))
        val events = mutableListOf<LoginEvent>()
        events += viewModel.events.first()
        events += viewModel.events.first()
        assertTrue(events.any { it is LoginEvent.LoginFailed && it.message == "nope" })
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun passedLoginEmitsLoginPassed() = runBlocking {
        val passed = result(LoginResult.STATUS_PASS)
        viewModel.loginCallback.success(passed)
        val event = viewModel.events.first()
        assertTrue(event is LoginEvent.LoginPassed && event.result === passed)
    }

    @Test
    fun firstKeystrokeIsLoggedOnce() = runBlocking {
        viewModel.onUsernameChange("u")
        viewModel.onUsernameChange("us")
        val first = viewModel.events.first()
        assertEquals(LoginEvent.Interaction("type", elementId = "username"), first)
        viewModel.onPasswordChange("p")
        assertEquals(LoginEvent.Interaction("type", elementId = "password"), viewModel.events.first())
    }
}
