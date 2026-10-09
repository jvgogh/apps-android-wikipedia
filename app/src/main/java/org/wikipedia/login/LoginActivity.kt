package org.wikipedia.login

import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.BaseActivity
import org.wikipedia.analytics.testkitchen.TestKitchenAdapter
import org.wikipedia.auth.AccountUtil.updateAccount
import org.wikipedia.captcha.HCaptchaHelper
import org.wikipedia.compose.theme.BaseTheme
import org.wikipedia.concurrency.FlowEventBus
import org.wikipedia.createaccount.CreateAccountActivity
import org.wikipedia.events.LoggedInEvent
import org.wikipedia.extensions.getInstrumentActionContext
import org.wikipedia.extensions.instrument
import org.wikipedia.extensions.parcelableExtra
import org.wikipedia.notifications.PollNotificationWorker
import org.wikipedia.push.WikipediaFirebaseMessagingService.Companion.updateSubscription
import org.wikipedia.readinglist.sync.ReadingListSyncAdapter
import org.wikipedia.settings.Prefs
import org.wikipedia.util.DeviceUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.UriUtil.visitInExternalBrowser
import org.wikipedia.widgets.readingchallenge.ReadingChallengeWidgetRepository
import java.time.LocalDate

class LoginActivity : BaseActivity() {
    private val viewModel: LoginViewModel by viewModels()

    private val createAccountLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        when (it.resultCode) {
            CreateAccountActivity.RESULT_ACCOUNT_CREATED -> {
                val username = it.data!!.getStringExtra(CreateAccountActivity.CREATE_ACCOUNT_RESULT_USERNAME).orEmpty()
                val password = it.data?.getStringExtra(CreateAccountActivity.CREATE_ACCOUNT_RESULT_PASSWORD).orEmpty()
                viewModel.onAccountCreated(username, password)
                FeedbackUtil.showMessage(this, R.string.create_account_account_created_toast)
                viewModel.doLogin()
            }
            CreateAccountActivity.RESULT_ACCOUNT_NOT_CREATED -> finish()
        }
    }

    private val resetPasswordLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            onLoginSuccess()
        }
    }

    private val hCaptchaHelper = HCaptchaHelper(this, object : HCaptchaHelper.Callback {
        override fun onShow() {
            instrument?.submitInteraction("hcaptcha_show")
        }

        override fun onSuccess(token: String) {
            instrument?.submitInteraction("hcaptcha_success")
            viewModel.onHCaptchaSuccess(token)
        }

        override fun onError(e: Exception, code: Int) {
            instrument?.submitInteraction("hcaptcha_error", actionContext = e.getInstrumentActionContext())
            viewModel.onHCaptchaError()
            FeedbackUtil.showMessage(this@LoginActivity, e.message.orEmpty())
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceUtil.setEdgeToEdge(this)

        _instrument = TestKitchenAdapter.client.getInstrument("apps-authentication")
            .setDefaultActionSource("login_form")
            .startFunnel("login_account")

        viewModel.instrument = instrument

        onBackPressedDispatcher.addCallback(this) {
            instrument?.submitInteraction("click", elementId = "back")
            finish()
        }

        val loginSource = intent.getStringExtra(LOGIN_REQUEST_SOURCE).orEmpty()
        if (loginSource.isNotEmpty() && loginSource == SOURCE_SUGGESTED_EDITS) {
            Prefs.isSuggestedEditsHighestPriorityEnabled = true
        }

        // always go to account creation before logging in, unless we arrived here through the
        // system account creation workflow
        if (savedInstanceState == null && !intent.hasExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE) &&
                intent.getBooleanExtra(CREATE_ACCOUNT_FIRST, true)) {
            startCreateAccountActivity()
        }

        // Assume no login by default
        setResult(RESULT_LOGIN_FAIL)

        instrument?.submitInteraction("impression", actionContext = mapOf("invoke_source" to loginSource))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is LoginEvent.HideKeyboard -> {
                            DeviceUtil.hideSoftKeyboard(this@LoginActivity)
                        }
                        is LoginEvent.ShowMessage -> {
                            FeedbackUtil.showMessage(this@LoginActivity, event.message)
                        }
                        is LoginEvent.ShowError -> {
                            FeedbackUtil.showError(this@LoginActivity, event.caught)
                        }
                        is LoginEvent.ShowHCaptcha -> {
                            hCaptchaHelper.cleanup()
                            hCaptchaHelper.show(event.siteKey)
                        }
                        is LoginEvent.PasswordResetPrompt -> {
                            resetPasswordLauncher.launch(
                                ResetPasswordActivity.newIntent(
                                    this@LoginActivity,
                                    viewModel.uiState.value.username,
                                    event.token
                                )
                            )
                        }
                        is LoginEvent.LoginSuccess -> {
                            val response = intent.parcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
                            updateAccount(response, event.result)
                            onLoginSuccess()
                        }
                        else -> { }
                    }
                }
            }
        }

        setContent {
            BaseTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                LoginScreen(
                    uiState = uiState,
                    eventFlow = viewModel.eventFlow,
                    onBackClick = {
                        onBackPressedDispatcher.onBackPressed()
                    },
                    onUsernameChange = viewModel::onUsernameChanged,
                    onPasswordChange = viewModel::onPasswordChanged,
                    onTwoFactorCodeChange = viewModel::onTwoFactorCodeChanged,
                    onCaptchaWordChange = viewModel::onCaptchaWordChanged,
                    onLoginClick = {
                        instrument?.submitInteraction("click", elementId = "login_button")
                        viewModel.validateThenLogin(
                            usernameErrorMessage = getString(R.string.create_account_username_error),
                            captchaErrorMessage = getString(R.string.edit_section_captcha_hint)
                        )
                    },
                    onImeDone = {
                        viewModel.validateThenLogin(
                            usernameErrorMessage = getString(R.string.create_account_username_error),
                            captchaErrorMessage = getString(R.string.edit_section_captcha_hint)
                        )
                    },
                    onCreateAccountClick = {
                        instrument?.submitInteraction("click", elementId = "create_account_button")
                        startCreateAccountActivity()
                    },
                    onForgotPasswordClick = {
                        instrument?.submitInteraction("click", elementId = "forgot_password_link")
                        val forgotPasswordUrl = WikipediaApp.instance.getString(R.string.forget_password_link, viewModel.wikiSite.languageCode)
                        visitInExternalBrowser(this, forgotPasswordUrl.toUri())
                    },
                    onPrivacyPolicyClick = {
                        instrument?.submitInteraction("click", elementId = "privacy_policy_link")
                        FeedbackUtil.showPrivacyPolicy(this)
                    },
                    onReloadCaptcha = {
                        viewModel.requestNewCaptcha()
                    },
                    onErrorBackClick = {
                        instrument?.submitInteraction("click", elementId = "error_back_button")
                        onBackPressedDispatcher.onBackPressed()
                    },
                    onErrorRetryClick = {
                        instrument?.submitInteraction("click", elementId = "error_retry_button")
                        viewModel.dismissError()
                    }
                )
            }
        }
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        hCaptchaHelper.cleanup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("loginShowing", true)
    }

    private fun startCreateAccountActivity() {
        createAccountLauncher.launch(CreateAccountActivity.newIntent(this, viewModel.uiState.value.loginSource))
    }

    private fun onLoginSuccess() {
        val loginSource = viewModel.uiState.value.loginSource
        val isReadingChallenge = loginSource == SOURCE_READING_CHALLENGE
        instrument?.submitInteraction(action = "success", actionContext = if (isReadingChallenge) mapOf("invoke_source" to loginSource) else null)
        if (isReadingChallenge) {
            Prefs.readingChallengeEnrolled = true
            Prefs.readingChallengeEnrollmentDate = LocalDate.now().toString()
            lifecycleScope.launch {
                if (ReadingChallengeWidgetRepository.isWidgetInstalled()) {
                    ReadingChallengeWidgetRepository(this@LoginActivity).updateWidgetsAndSendAnalytics()
                }
            }
            intent.removeExtra(LOGIN_REQUEST_SOURCE)
        }
        DeviceUtil.hideSoftKeyboard(this@LoginActivity)
        setResult(RESULT_LOGIN_SUCCESS)

        Prefs.isReadingListSyncEnabled = true
        Prefs.readingListPagesDeletedIds = emptySet()
        Prefs.readingListsDeletedIds = emptySet()
        Prefs.tempAccountWelcomeShown = false
        Prefs.tempAccountCreateDay = 0L
        Prefs.lastBackgroundLoginDateTime = ""
        ReadingListSyncAdapter.manualSyncWithForce()
        PollNotificationWorker.schedulePollNotificationJob(this)
        updateSubscription()
        FlowEventBus.post(LoggedInEvent())
        finish()
    }

    companion object {
        const val RESULT_LOGIN_SUCCESS = 1
        const val RESULT_LOGIN_FAIL = 2
        const val LOGIN_REQUEST_SOURCE = "login_request_source"
        const val CREATE_ACCOUNT_FIRST = "create_account_first"
        const val SOURCE_NAV = "navigation"
        const val SOURCE_EDIT = "edit"
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_ONBOARDING = "onboarding"
        const val SOURCE_SETTINGS = "settings"
        const val SOURCE_SUBSCRIBE = "subscribe"
        const val SOURCE_LOGOUT_BACKGROUND = "logout_background"
        const val SOURCE_SUGGESTED_EDITS = "suggestededits"
        const val SOURCE_TALK = "talk"
        const val SOURCE_ACTIVITY_TAB = "activity_tab"
        const val SOURCE_YEAR_IN_REVIEW = "yir"
        const val SOURCE_ON_THIS_DAY_GAME_RESULT = "on_this_day_game_result"
        const val SOURCE_READING_CHALLENGE = "widget_challenge"
        const val SOURCE_ENCOURAGE = "encourage"

        fun newIntent(context: Context, source: String, createAccountFirst: Boolean = true): Intent {
            return Intent(context, LoginActivity::class.java)
                    .putExtra(LOGIN_REQUEST_SOURCE, source)
                    .putExtra(CREATE_ACCOUNT_FIRST, createAccountFirst)
        }
    }
}
