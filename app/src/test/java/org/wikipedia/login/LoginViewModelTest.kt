package org.wikipedia.login

import androidx.lifecycle.SavedStateHandle
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.wikimedia.testkitchen.instrument.InstrumentImpl
import org.wikipedia.dataclient.WikiSite

@RunWith(RobolectricTestRunner::class)
class LoginViewModelTest {
    private val loginClient = mockk<LoginClient>(relaxed = true)
    private val instrument = mockk<InstrumentImpl>(relaxed = true)
    private val wikiSite = WikiSite("en.wikipedia.org")
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        savedStateHandle = SavedStateHandle(mapOf(LoginActivity.LOGIN_REQUEST_SOURCE to "test_source"))
        viewModel = LoginViewModel(savedStateHandle, loginClient, wikiSite)
        viewModel.instrument = instrument
    }

    @Test
    fun testInitialState() {
        val state = viewModel.uiState.value
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals("", state.twoFactorCode)
        assertEquals("", state.captchaWord)
        assertFalse(state.is2faVisible)
        assertFalse(state.captchaActive)
        assertFalse(state.isLoading)
        assertNull(state.usernameError)
        assertNull(state.passwordError)
        assertNull(state.captchaError)
        assertNull(state.errorState)
        assertEquals("test_source", state.loginSource)
    }

    @Test
    fun testFirstKeystrokeInstrumentation() {
        viewModel.onUsernameChanged("u")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "username") }
        viewModel.onUsernameChanged("user")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "username") }

        viewModel.onPasswordChanged("p")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "password") }
        viewModel.onPasswordChanged("pass")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "password") }

        viewModel.onTwoFactorCodeChanged("1")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "2fa") }
        viewModel.onTwoFactorCodeChanged("123")
        verify(exactly = 1) { instrument.submitInteraction("type", elementId = "2fa") }
    }

    @Test
    fun testUsernameValidationError() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        viewModel.onUsernameChanged("bad#username")
        viewModel.validateThenLogin("invalid username", "captcha required")

        assertEquals("invalid username", viewModel.uiState.value.usernameError)
        verify { instrument.submitInteraction("error", actionContext = mapOf("validation_error" to "username_invalid")) }
        assertTrue(events.any { it is LoginEvent.FocusUsername })
        verify(exactly = 0) { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        job.cancel()
    }

    @Test
    fun testCaptchaEmptyValidationError() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }
        cbSlot.captured.uiPrompt(LoginResult(wikiSite, LoginResult.STATUS_UI, "validUser", "validPassword", "need captcha"), Exception("captcha"), "captcha123", "token123")

        assertTrue(viewModel.uiState.value.captchaActive)
        assertEquals("", viewModel.uiState.value.captchaWord)

        viewModel.validateThenLogin("invalid username", "captcha required")
        assertEquals("captcha required", viewModel.uiState.value.captchaError)
        verify { instrument.submitInteraction("error", actionContext = mapOf("validation_error" to "fancy_captcha_empty")) }
        assertTrue(events.any { it is LoginEvent.FocusCaptcha })

        job.cancel()
    }

    @Test
    fun testLoginSuccessFlow() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.validateThenLogin("invalid username", "captcha required")

        assertTrue(viewModel.uiState.value.isLoading)
        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val passResult = LoginResult(wikiSite, LoginResult.STATUS_PASS, "validUser", "validPassword", "ok")
        cbSlot.captured.success(passResult)

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(events.any { it is LoginEvent.LoginSuccess && it.result == passResult })

        job.cancel()
    }

    @Test
    fun testLoginFailureFlow() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("wrongPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val failResult = LoginResult(wikiSite, LoginResult.STATUS_FAIL, "validUser", "wrongPassword", "Wrong password", messageCode = "wrongpass")
        cbSlot.captured.success(failResult)

        assertFalse(viewModel.uiState.value.isLoading)
        verify { instrument.submitInteraction("error", actionContext = mapOf("code" to "wrongpass")) }
        assertTrue(events.any { it is LoginEvent.ShowMessage && it.message == "Wrong password" })

        job.cancel()
    }

    @Test
    fun testTwoFactorPromptFlow() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val oathResult = LoginOATHResult(wikiSite, LoginResult.STATUS_UI, "validUser", "validPassword", "2fa prompt")
        val error = Exception("2fa required")
        cbSlot.captured.uiPrompt(oathResult, error, null, "token123")

        assertTrue(viewModel.uiState.value.is2faVisible)
        assertFalse(viewModel.uiState.value.isEmailAuth)
        verify { instrument.submitInteraction("impression", elementId = "2fa_oath") }
        assertTrue(events.any { it is LoginEvent.ShowError && it.caught == error })
        assertTrue(events.any { it is LoginEvent.Focus2fa })

        job.cancel()
    }

    @Test
    fun testEmailAuthPromptFlow() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val emailResult = LoginEmailAuthResult(wikiSite, LoginResult.STATUS_UI, "validUser", "validPassword", "email auth prompt")
        val error = Exception("email auth required")
        cbSlot.captured.uiPrompt(emailResult, error, null, "token123")

        assertTrue(viewModel.uiState.value.is2faVisible)
        assertTrue(viewModel.uiState.value.isEmailAuth)
        verify { instrument.submitInteraction("impression", elementId = "2fa_email") }
        assertTrue(events.any { it is LoginEvent.ShowError && it.caught == error })
        assertTrue(events.any { it is LoginEvent.Focus2fa })

        job.cancel()
    }

    @Test
    fun testHCaptchaPromptFlow() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val result = LoginResult(wikiSite, LoginResult.STATUS_UI, "validUser", "validPassword", "hcaptcha")
        cbSlot.captured.hCaptchaPrompt(result, Exception("hcaptcha"), "site-key-123", "token123")

        assertTrue(viewModel.uiState.value.isLoading)
        verify { instrument.submitInteraction("hcaptcha_load") }
        assertTrue(events.any { it is LoginEvent.ShowHCaptcha && it.siteKey == "site-key-123" })

        job.cancel()
    }

    @Test
    fun testGenericErrorSetsErrorState() = runBlocking {
        val events = mutableListOf<LoginEvent>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.eventFlow.collect { events.add(it) }
        }

        val cbSlot = slot<LoginClient.LoginCallback>()
        viewModel.onUsernameChanged("validUser")
        viewModel.onPasswordChanged("validPassword")
        viewModel.doLogin()

        verify { loginClient.login(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cbSlot)) }

        val networkError = Exception("Network connection failed")
        cbSlot.captured.error(networkError)

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(networkError, viewModel.uiState.value.errorState)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorState)

        job.cancel()
    }

    @Test
    fun testAccountCreatedUpdatesCredentials() {
        viewModel.onAccountCreated("newUsername", "newPassword")
        assertEquals("newUsername", viewModel.uiState.value.username)
        assertEquals("newPassword", viewModel.uiState.value.password)
    }
}
