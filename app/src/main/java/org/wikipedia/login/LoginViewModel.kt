package org.wikipedia.login

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.auth.AccountUtil
import org.wikipedia.captcha.CaptchaResult
import org.wikipedia.createaccount.CreateAccountActivity
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.extensions.getInstrumentActionContext
import org.wikipedia.util.log.L

class LoginViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    data class CaptchaUiState(
        val imageUrl: String,
        val word: String = "",
        val showError: Boolean = false,
        val isImageLoading: Boolean = false
    )

    data class LoginUiState(
        val username: String = "",
        val password: String = "",
        val twoFactorCode: String = "",
        @StringRes val twoFactorHint: Int? = null,
        val showUsernameError: Boolean = false,
        val captcha: CaptchaUiState? = null,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val tempAccountName: String? = null
    ) {
        val isTwoFactorVisible get() = twoFactorHint != null
        val isLoginEnabled get() = !isLoading && username.isNotEmpty() && password.isNotEmpty() &&
                (!isTwoFactorVisible || twoFactorCode.isNotEmpty())
    }

    enum class FocusTarget { USERNAME, CAPTCHA, TWO_FACTOR }

    sealed interface LoginEvent {
        data class Interaction(val action: String, val elementId: String? = null, val actionContext: Map<String, String>? = null) : LoginEvent
        data class LoginPassed(val result: LoginResult) : LoginEvent
        data class LoginFailed(val message: String) : LoginEvent
        data class ShowMessage(val message: String) : LoginEvent
        data class ShowError(val caught: Throwable) : LoginEvent
        data class ShowHCaptcha(val siteKey: String) : LoginEvent
        data class LaunchResetPassword(val username: String, val token: String?) : LoginEvent
        data object HideKeyboard : LoginEvent
    }

    val loginSource: String = savedStateHandle.get<String>(LoginActivity.LOGIN_REQUEST_SOURCE).orEmpty()

    private val wiki = WikipediaApp.instance.wikiSite
    private val loginClient = LoginClient()
    private val typedFields = mutableSetOf<String>()

    private var uiPromptResult: LoginResult? = null
    private var captchaResult: CaptchaResult? = null
    private var hCaptchaToken: String? = null
    private var firstStepToken: String? = null

    private val _uiState = MutableStateFlow(LoginUiState(
        username = savedStateHandle[KEY_USERNAME] ?: "",
        password = savedStateHandle[KEY_PASSWORD] ?: "",
        tempAccountName = if (AccountUtil.isTemporaryAccount) AccountUtil.userName else null
    ))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _focusRequests = Channel<FocusTarget>(Channel.BUFFERED)
    val focusRequests = _focusRequests.receiveAsFlow()

    val loginCallback: LoginClient.LoginCallback = object : LoginClient.LoginCallback {
        override fun success(result: LoginResult) {
            setLoading(false)
            if (result.pass()) {
                send(LoginEvent.LoginPassed(result))
            } else if (result.fail()) {
                val message = result.message.orEmpty()
                send(LoginEvent.Interaction("error", actionContext = mapOf("code" to result.messageCode.orEmpty())))
                send(LoginEvent.LoginFailed(message))
                L.w("Login failed with result $message")
            }
        }

        override fun uiPrompt(result: LoginResult, caught: Throwable, captchaId: String?, token: String?) {
            setLoading(false)
            firstStepToken = token
            if (captchaId != null) {
                if (captchaResult != null) {
                    send(LoginEvent.ShowError(caught))
                }
                captchaResult = CaptchaResult(captchaId)
                showCaptcha()
            }
            if (result is LoginEmailAuthResult || result is LoginOATHResult) {
                send(LoginEvent.Interaction("impression", elementId = if (result is LoginOATHResult) "2fa_oath" else "2fa_email"))
                uiPromptResult = result
                _uiState.update {
                    it.copy(twoFactorHint = if (result is LoginEmailAuthResult) R.string.login_email_auth_hint else R.string.login_2fa_hint,
                        twoFactorCode = "")
                }
                requestFocus(FocusTarget.TWO_FACTOR)
                send(LoginEvent.ShowError(caught))
            }
            send(LoginEvent.HideKeyboard)
        }

        override fun hCaptchaPrompt(result: LoginResult, caught: Throwable, siteKey: String, token: String?) {
            setLoading(true)
            firstStepToken = token
            send(LoginEvent.Interaction("hcaptcha_load"))
            send(LoginEvent.ShowHCaptcha(siteKey))
        }

        override fun passwordResetPrompt(token: String?) {
            send(LoginEvent.LaunchResetPassword(_uiState.value.username, token))
        }

        override fun error(caught: Throwable) {
            setLoading(false)
            resetAuthState()
            send(LoginEvent.HideKeyboard)
            if (caught is LoginFailedException) {
                send(LoginEvent.ShowError(caught))
            } else {
                send(LoginEvent.Interaction("error", actionContext = caught.getInstrumentActionContext()))
                _uiState.update { it.copy(error = caught) }
            }
        }
    }

    init {
        resetAuthState()
    }

    fun onUsernameChange(value: String) {
        savedStateHandle[KEY_USERNAME] = value
        _uiState.update { it.copy(username = value) }
        logFirstKeystroke(value, "username")
    }

    fun onPasswordChange(value: String) {
        savedStateHandle[KEY_PASSWORD] = value
        _uiState.update { it.copy(password = value) }
        logFirstKeystroke(value, "password")
    }

    fun onTwoFactorChange(value: String) {
        _uiState.update { it.copy(twoFactorCode = value) }
        logFirstKeystroke(value, "2fa")
    }

    fun onCaptchaWordChange(value: String) {
        _uiState.update { state -> state.copy(captcha = state.captcha?.copy(word = value, showError = false)) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onAccountCreated(username: String?, password: String?) {
        onUsernameChange(username.orEmpty())
        onPasswordChange(password.orEmpty())
        doLogin()
    }

    fun validateThenLogin() {
        clearErrors()
        val state = _uiState.value
        if (!CreateAccountActivity.USERNAME_PATTERN.matcher(state.username).matches()) {
            send(LoginEvent.Interaction("error", actionContext = mapOf("validation_error" to "username_invalid")))
            requestFocus(FocusTarget.USERNAME)
            _uiState.update { it.copy(showUsernameError = true) }
            return
        }
        if (state.captcha != null && state.captcha.word.isEmpty()) {
            send(LoginEvent.Interaction("error", actionContext = mapOf("validation_error" to "fancy_captcha_empty")))
            _uiState.update { it.copy(captcha = it.captcha?.copy(showError = true)) }
            requestFocus(FocusTarget.CAPTCHA)
            return
        }
        doLogin()
    }

    fun doLogin() {
        val state = _uiState.value
        setLoading(true)
        if (uiPromptResult == null && captchaResult == null && hCaptchaToken == null) {
            loginClient.login(viewModelScope, wiki, state.username, state.password, cb = loginCallback)
        } else {
            loginClient.login(viewModelScope, wiki, state.username, state.password, token = firstStepToken,
                captchaId = captchaResult?.captchaId,
                captchaWord = hCaptchaToken ?: if (captchaResult != null) state.captcha?.word else null,
                twoFactorCode = if (uiPromptResult is LoginOATHResult) state.twoFactorCode else null,
                emailAuthCode = if (uiPromptResult is LoginEmailAuthResult) state.twoFactorCode else null,
                isContinuation = uiPromptResult != null,
                cb = loginCallback)
        }
    }

    fun onHCaptchaSuccess(token: String) {
        hCaptchaToken = token
        doLogin()
    }

    fun onHCaptchaError(message: String) {
        setLoading(false)
        send(LoginEvent.ShowMessage(message))
    }

    fun requestNewCaptcha() {
        send(LoginEvent.Interaction("fancy_captcha_request_new"))
        _uiState.update { state -> state.copy(captcha = state.captcha?.copy(isImageLoading = true)) }
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            captchaResult = null
            _uiState.update { it.copy(captcha = null) }
            send(LoginEvent.ShowError(throwable))
        }) {
            val response = ServiceFactory.get(wiki).getNewCaptcha()
            captchaResult = CaptchaResult(response.captchaId())
            showCaptcha(isReload = true)
        }
    }

    private fun showCaptcha(isReload: Boolean = false) {
        val result = captchaResult ?: return
        send(LoginEvent.Interaction("fancy_captcha_show"))
        send(LoginEvent.HideKeyboard)
        _uiState.update { it.copy(captcha = CaptchaUiState(imageUrl = result.getCaptchaUrl(wiki))) }
        if (!isReload) {
            requestFocus(FocusTarget.CAPTCHA)
        }
    }

    fun onCaptchaImageLoaded() {
        _uiState.update { state -> state.copy(captcha = state.captcha?.copy(isImageLoading = false)) }
    }

    private fun resetAuthState() {
        firstStepToken = null
        uiPromptResult = null
        captchaResult = null
        hCaptchaToken = null
        _uiState.update { it.copy(twoFactorHint = null, twoFactorCode = "", captcha = null) }
    }

    private fun clearErrors() {
        _uiState.update { it.copy(showUsernameError = false, captcha = it.captcha?.copy(showError = false)) }
    }

    private fun setLoading(loading: Boolean) {
        _uiState.update { it.copy(isLoading = loading) }
    }

    private fun logFirstKeystroke(value: String, elementId: String) {
        if (value.isNotEmpty() && typedFields.add(elementId)) {
            send(LoginEvent.Interaction("type", elementId = elementId))
        }
    }

    private fun requestFocus(target: FocusTarget) {
        _focusRequests.trySend(target)
    }

    private fun send(event: LoginEvent) {
        _events.trySend(event)
    }

    companion object {
        private const val KEY_USERNAME = "loginUsername"
        private const val KEY_PASSWORD = "loginPassword"
    }
}
