package org.wikipedia.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wikimedia.testkitchen.instrument.InstrumentImpl
import org.wikipedia.WikipediaApp
import org.wikipedia.auth.AccountUtil
import org.wikipedia.captcha.CaptchaResult
import org.wikipedia.createaccount.CreateAccountActivity
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.extensions.getInstrumentActionContext
import org.wikipedia.util.log.L

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val twoFactorCode: String = "",
    val captchaWord: String = "",
    val is2faVisible: Boolean = false,
    val isEmailAuth: Boolean = false,
    val captchaActive: Boolean = false,
    val captchaResult: CaptchaResult? = null,
    val captchaUrl: String? = null,
    val isLoadingCaptcha: Boolean = false,
    val isLoading: Boolean = false,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val captchaError: String? = null,
    val errorState: Throwable? = null,
    val isTemporaryAccount: Boolean = AccountUtil.isTemporaryAccount,
    val tempAccountUserName: String? = AccountUtil.userName,
    val loginSource: String = ""
)

sealed interface LoginEvent {
    object HideKeyboard : LoginEvent
    object FocusUsername : LoginEvent
    object FocusCaptcha : LoginEvent
    object Focus2fa : LoginEvent
    data class ShowMessage(val message: String) : LoginEvent
    data class ShowError(val caught: Throwable) : LoginEvent
    data class ShowHCaptcha(val siteKey: String) : LoginEvent
    data class PasswordResetPrompt(val token: String?) : LoginEvent
    data class LoginSuccess(val result: LoginResult) : LoginEvent
}

class LoginViewModel(
    savedStateHandle: SavedStateHandle,
    private val loginClient: LoginClient = LoginClient(),
    var wikiSite: WikiSite = WikipediaApp.instance.wikiSite
) : ViewModel() {

    var instrument: InstrumentImpl? = null

    private val loginCallback = LoginCallbackImpl()

    var firstStepToken: String? = null
        private set
    var uiPromptResult: LoginResult? = null
        private set
    var captchaResult: CaptchaResult? = null
        private set
    var hCaptchaToken: String? = null
        private set

    private var usernameTypedEventSent = false
    private var passwordTypedEventSent = false
    private var twoFactorTypedEventSent = false

    private val _uiState = MutableStateFlow(
        LoginUiState(
            loginSource = savedStateHandle.get<String>(LoginActivity.LOGIN_REQUEST_SOURCE).orEmpty()
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<LoginEvent>()
    val eventFlow: SharedFlow<LoginEvent> = _eventFlow.asSharedFlow()

    fun onUsernameChanged(text: String) {
        if (text.isNotEmpty() && !usernameTypedEventSent) {
            instrument?.submitInteraction("type", elementId = "username")
            usernameTypedEventSent = true
        }
        _uiState.update { it.copy(username = text, usernameError = null) }
    }

    fun onPasswordChanged(text: String) {
        if (text.isNotEmpty() && !passwordTypedEventSent) {
            instrument?.submitInteraction("type", elementId = "password")
            passwordTypedEventSent = true
        }
        _uiState.update { it.copy(password = text, passwordError = null) }
    }

    fun onTwoFactorCodeChanged(text: String) {
        if (text.isNotEmpty() && !twoFactorTypedEventSent) {
            instrument?.submitInteraction("type", elementId = "2fa")
            twoFactorTypedEventSent = true
        }
        _uiState.update { it.copy(twoFactorCode = text) }
    }

    fun onCaptchaWordChanged(text: String) {
        _uiState.update { it.copy(captchaWord = text, captchaError = null) }
    }

    fun onAccountCreated(newUsername: String, newPassword: String) {
        _uiState.update {
            it.copy(
                username = newUsername,
                password = newPassword,
                usernameError = null,
                passwordError = null
            )
        }
    }

    fun onHCaptchaSuccess(token: String) {
        hCaptchaToken = token
        doLogin()
    }

    fun onHCaptchaError() {
        _uiState.update { it.copy(isLoading = false) }
    }

    fun onStop() {
        _uiState.update { it.copy(isLoading = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorState = null) }
    }

    private fun clearErrors() {
        _uiState.update {
            it.copy(
                usernameError = null,
                passwordError = null,
                captchaError = null
            )
        }
    }

    fun resetAuthState() {
        firstStepToken = null
        uiPromptResult = null
        captchaResult = null
        hCaptchaToken = null
        _uiState.update {
            it.copy(
                is2faVisible = false,
                twoFactorCode = "",
                captchaActive = false,
                captchaResult = null,
                captchaUrl = null,
                captchaWord = "",
                captchaError = null
            )
        }
    }

    fun validateThenLogin(
        usernameErrorMessage: String,
        captchaErrorMessage: String
    ) {
        clearErrors()
        val currentState = _uiState.value
        if (!CreateAccountActivity.USERNAME_PATTERN.matcher(currentState.username).matches()) {
            instrument?.submitInteraction("error", actionContext = mapOf("validation_error" to "username_invalid"))
            _uiState.update { it.copy(usernameError = usernameErrorMessage) }
            viewModelScope.launch { _eventFlow.emit(LoginEvent.FocusUsername) }
            return
        }
        if (currentState.captchaActive && currentState.captchaWord.isEmpty()) {
            instrument?.submitInteraction("error", actionContext = mapOf("validation_error" to "fancy_captcha_empty"))
            _uiState.update { it.copy(captchaError = captchaErrorMessage) }
            viewModelScope.launch { _eventFlow.emit(LoginEvent.FocusCaptcha) }
            return
        }
        doLogin()
    }

    fun doLogin() {
        val currentState = _uiState.value
        val username = currentState.username
        val password = currentState.password
        val twoFactorCode = currentState.twoFactorCode

        _uiState.update { it.copy(isLoading = true) }

        if (uiPromptResult == null && captchaResult == null && hCaptchaToken == null) {
            loginClient.login(
                viewModelScope,
                wikiSite,
                username,
                password,
                cb = loginCallback
            )
        } else {
            loginClient.login(
                viewModelScope,
                wikiSite,
                username,
                password,
                token = firstStepToken,
                captchaId = captchaResult?.captchaId,
                captchaWord = hCaptchaToken ?: if (captchaResult != null) currentState.captchaWord else null,
                twoFactorCode = if (uiPromptResult is LoginOATHResult) twoFactorCode else null,
                emailAuthCode = if (uiPromptResult is LoginEmailAuthResult) twoFactorCode else null,
                isContinuation = uiPromptResult != null,
                cb = loginCallback
            )
        }
    }

    fun requestNewCaptcha() {
        instrument?.submitInteraction("fancy_captcha_request_new")
        _uiState.update { it.copy(isLoadingCaptcha = true) }
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            cancelCaptcha()
            viewModelScope.launch { _eventFlow.emit(LoginEvent.ShowError(throwable)) }
        }) {
            val response = ServiceFactory.get(wikiSite).getNewCaptcha()
            val result = CaptchaResult(response.captchaId())
            captchaResult = result
            instrument?.submitInteraction("fancy_captcha_show")
            _uiState.update {
                it.copy(
                    isLoadingCaptcha = false,
                    captchaActive = true,
                    captchaResult = result,
                    captchaUrl = result.getCaptchaUrl(wikiSite),
                    captchaWord = "",
                    captchaError = null
                )
            }
        }
    }

    fun cancelCaptcha() {
        captchaResult = null
        _uiState.update {
            it.copy(
                captchaActive = false,
                captchaResult = null,
                captchaUrl = null,
                captchaWord = "",
                captchaError = null,
                isLoadingCaptcha = false
            )
        }
    }

    private inner class LoginCallbackImpl : LoginClient.LoginCallback {
        override fun success(result: LoginResult) {
            _uiState.update { it.copy(isLoading = false) }
            if (result.pass()) {
                viewModelScope.launch { _eventFlow.emit(LoginEvent.LoginSuccess(result)) }
            } else if (result.fail()) {
                instrument?.submitInteraction("error", actionContext = mapOf("code" to result.messageCode.orEmpty()))
                val message = result.message.orEmpty()
                viewModelScope.launch { _eventFlow.emit(LoginEvent.ShowMessage(message)) }
                L.w("Login failed with result $message")
            }
        }

        override fun uiPrompt(result: LoginResult, caught: Throwable, captchaId: String?, token: String?) {
            firstStepToken = token
            var shouldShowCaptchaError = false
            if (captchaId != null) {
                if (captchaResult != null) {
                    shouldShowCaptchaError = true
                }
                captchaResult = CaptchaResult(captchaId)
                instrument?.submitInteraction("fancy_captcha_show")
            }
            val isOath = result is LoginOATHResult
            val isEmail = result is LoginEmailAuthResult
            if (isOath || isEmail) {
                instrument?.submitInteraction("impression", elementId = if (isOath) "2fa_oath" else "2fa_email")
                uiPromptResult = result
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    is2faVisible = isOath || isEmail || it.is2faVisible,
                    isEmailAuth = isEmail,
                    twoFactorCode = if (isOath || isEmail) "" else it.twoFactorCode,
                    captchaActive = captchaResult != null,
                    captchaResult = captchaResult,
                    captchaUrl = captchaResult?.getCaptchaUrl(wikiSite),
                    captchaWord = if (captchaId != null) "" else it.captchaWord
                )
            }
            viewModelScope.launch {
                _eventFlow.emit(LoginEvent.HideKeyboard)
                if (isOath || isEmail || shouldShowCaptchaError) {
                    _eventFlow.emit(LoginEvent.ShowError(caught))
                }
                if (isOath || isEmail) {
                    _eventFlow.emit(LoginEvent.Focus2fa)
                }
            }
        }

        override fun hCaptchaPrompt(result: LoginResult, caught: Throwable, siteKey: String, token: String?) {
            _uiState.update { it.copy(isLoading = true) }
            firstStepToken = token
            instrument?.submitInteraction("hcaptcha_load")
            viewModelScope.launch { _eventFlow.emit(LoginEvent.ShowHCaptcha(siteKey)) }
        }

        override fun passwordResetPrompt(token: String?) {
            viewModelScope.launch { _eventFlow.emit(LoginEvent.PasswordResetPrompt(token)) }
        }

        override fun error(caught: Throwable) {
            _uiState.update { it.copy(isLoading = false) }
            resetAuthState()
            viewModelScope.launch {
                _eventFlow.emit(LoginEvent.HideKeyboard)
                if (caught is LoginFailedException) {
                    _eventFlow.emit(LoginEvent.ShowError(caught))
                } else {
                    instrument?.submitInteraction("error", actionContext = caught.getInstrumentActionContext())
                    _uiState.update { it.copy(errorState = caught) }
                }
            }
        }
    }
}
