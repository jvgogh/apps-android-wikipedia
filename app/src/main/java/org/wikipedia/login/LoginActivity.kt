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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
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
    private val wiki = WikipediaApp.instance.wikiSite
    private lateinit var loginSource: String

    private val createAccountLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        when (it.resultCode) {
            CreateAccountActivity.RESULT_ACCOUNT_CREATED -> {
                FeedbackUtil.showMessage(this, R.string.create_account_account_created_toast)
                viewModel.onAccountCreated(
                    it.data!!.getStringExtra(CreateAccountActivity.CREATE_ACCOUNT_RESULT_USERNAME),
                    it.data?.getStringExtra(CreateAccountActivity.CREATE_ACCOUNT_RESULT_PASSWORD)
                )
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
            viewModel.onHCaptchaError(e.message.orEmpty())
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        _instrument = TestKitchenAdapter.client.getInstrument("apps-authentication")
            .setDefaultActionSource("login_form")
            .startFunnel("login_account")

        onBackPressedDispatcher.addCallback(this) {
            instrument?.submitInteraction("click", elementId = "back")
            finish()
        }

        loginSource = viewModel.loginSource
        if (loginSource.isNotEmpty() && loginSource == SOURCE_SUGGESTED_EDITS) {
            Prefs.isSuggestedEditsHighestPriorityEnabled = true
        }

        setContent {
            BaseTheme {
                val state by viewModel.uiState.collectAsState()
                val usernameFocusRequester = remember { FocusRequester() }
                val twoFactorFocusRequester = remember { FocusRequester() }
                val captchaFocusRequester = remember { FocusRequester() }

                LaunchedEffect(Unit) {
                    viewModel.focusRequests.collect {
                        when (it) {
                            LoginViewModel.FocusTarget.USERNAME -> usernameFocusRequester.requestFocus()
                            LoginViewModel.FocusTarget.TWO_FACTOR -> twoFactorFocusRequester.requestFocus()
                            LoginViewModel.FocusTarget.CAPTCHA -> captchaFocusRequester.requestFocus()
                        }
                    }
                }

                LoginScreen(
                    state = state,
                    usernameFocusRequester = usernameFocusRequester,
                    twoFactorFocusRequester = twoFactorFocusRequester,
                    captchaFocusRequester = captchaFocusRequester,
                    callbacks = LoginScreenCallbacks(
                        onBackClick = { onBackPressedDispatcher.onBackPressed() },
                        onUsernameChange = viewModel::onUsernameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onTwoFactorChange = viewModel::onTwoFactorChange,
                        onCaptchaWordChange = viewModel::onCaptchaWordChange,
                        onCaptchaImageClick = viewModel::requestNewCaptcha,
                        onCaptchaImageLoaded = viewModel::onCaptchaImageLoaded,
                        onLoginClick = {
                            instrument?.submitInteraction("click", elementId = "login_button")
                            viewModel.validateThenLogin()
                        },
                        onCreateAccountClick = {
                            instrument?.submitInteraction("click", elementId = "create_account_button")
                            startCreateAccountActivity()
                        },
                        onForgotPasswordClick = {
                            instrument?.submitInteraction("click", elementId = "forgot_password_link")
                            val forgotPasswordUrl = WikipediaApp.instance.getString(R.string.forget_password_link, wiki.languageCode)
                            visitInExternalBrowser(this, forgotPasswordUrl.toUri())
                        },
                        onPrivacyPolicyClick = {
                            instrument?.submitInteraction("click", elementId = "privacy_policy_link")
                            FeedbackUtil.showPrivacyPolicy(this)
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
                )
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { handleEvent(it) }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        hCaptchaHelper.cleanup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("loginShowing", true)
    }

    private fun handleEvent(event: LoginViewModel.LoginEvent) {
        when (event) {
            is LoginViewModel.LoginEvent.Interaction ->
                instrument?.submitInteraction(event.action, elementId = event.elementId, actionContext = event.actionContext)
            is LoginViewModel.LoginEvent.LoginPassed -> {
                val response = intent.parcelableExtra<AccountAuthenticatorResponse>(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
                updateAccount(response, event.result)
                onLoginSuccess()
            }
            is LoginViewModel.LoginEvent.LoginFailed -> FeedbackUtil.showMessage(this, event.message)
            is LoginViewModel.LoginEvent.ShowMessage -> FeedbackUtil.showMessage(this, event.message)
            is LoginViewModel.LoginEvent.ShowError -> FeedbackUtil.showError(this, event.caught)
            is LoginViewModel.LoginEvent.ShowHCaptcha -> {
                hCaptchaHelper.cleanup()
                hCaptchaHelper.show(event.siteKey)
            }
            is LoginViewModel.LoginEvent.LaunchResetPassword ->
                resetPasswordLauncher.launch(ResetPasswordActivity.newIntent(this, event.username, event.token))
            LoginViewModel.LoginEvent.HideKeyboard -> DeviceUtil.hideSoftKeyboard(this)
        }
    }

    private fun startCreateAccountActivity() {
        createAccountLauncher.launch(CreateAccountActivity.newIntent(this, loginSource))
    }

    private fun onLoginSuccess() {
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

        // Set reading list syncing to enabled (without the explicit setup instruction),
        // so that the sync adapter can run at least once and check whether syncing is enabled
        // on the server side.
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
