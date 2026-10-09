package org.wikipedia.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharedFlow
import org.wikipedia.R
import org.wikipedia.compose.components.AppButton
import org.wikipedia.compose.components.HtmlText
import org.wikipedia.compose.components.WikiTopAppBar
import org.wikipedia.compose.components.error.WikiErrorClickEvents
import org.wikipedia.compose.components.error.WikiErrorView
import org.wikipedia.compose.theme.WikipediaTheme

@Composable
fun LoginScreen(
    uiState: LoginUiState,
    eventFlow: SharedFlow<LoginEvent>,
    onBackClick: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTwoFactorCodeChange: (String) -> Unit,
    onCaptchaWordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onImeDone: () -> Unit,
    onCreateAccountClick: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onReloadCaptcha: () -> Unit,
    onErrorBackClick: () -> Unit,
    onErrorRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    usernameFocusRequester: FocusRequester = remember { FocusRequester() },
    passwordFocusRequester: FocusRequester = remember { FocusRequester() },
    twoFactorFocusRequester: FocusRequester = remember { FocusRequester() },
    captchaFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val isLoginButtonEnabled = !uiState.isLoading &&
        uiState.username.isNotEmpty() &&
        uiState.password.isNotEmpty() &&
        (!uiState.is2faVisible || uiState.twoFactorCode.isNotEmpty())

    LaunchedEffect(eventFlow) {
        eventFlow.collect { event ->
            when (event) {
                is LoginEvent.FocusUsername -> {
                    runCatching { usernameFocusRequester.requestFocus() }
                }
                is LoginEvent.FocusCaptcha -> {
                    runCatching { captchaFocusRequester.requestFocus() }
                }
                is LoginEvent.Focus2fa -> {
                    runCatching { twoFactorFocusRequester.requestFocus() }
                }
                else -> { }
            }
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = WikipediaTheme.colors.primaryColor,
        unfocusedTextColor = WikipediaTheme.colors.primaryColor,
        focusedContainerColor = WikipediaTheme.colors.paperColor,
        unfocusedContainerColor = WikipediaTheme.colors.paperColor,
        focusedBorderColor = WikipediaTheme.colors.progressiveColor,
        unfocusedBorderColor = WikipediaTheme.colors.borderColor,
        focusedLabelColor = WikipediaTheme.colors.progressiveColor,
        unfocusedLabelColor = WikipediaTheme.colors.secondaryColor,
        errorBorderColor = WikipediaTheme.colors.destructiveColor,
        errorLabelColor = WikipediaTheme.colors.destructiveColor,
        errorSupportingTextColor = WikipediaTheme.colors.destructiveColor,
        cursorColor = WikipediaTheme.colors.progressiveColor
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            WikiTopAppBar(
                title = stringResource(R.string.login_activity_title),
                onNavigationClick = onBackClick
            )
        },
        containerColor = WikipediaTheme.colors.paperColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(usernameFocusRequester),
                    label = {
                        Text(text = stringResource(R.string.login_username_hint))
                    },
                    isError = uiState.usernameError != null,
                    supportingText = uiState.usernameError?.let {
                        {
                            Text(
                                text = it,
                                color = WikipediaTheme.colors.destructiveColor
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester),
                    label = {
                        Text(text = stringResource(R.string.login_password_hint))
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter = painterResource(
                                    if (passwordVisible) R.drawable.ic_visibility_24 else R.drawable.ic_visibility_off_24dp
                                ),
                                contentDescription = null,
                                tint = WikipediaTheme.colors.secondaryColor
                            )
                        }
                    },
                    isError = uiState.passwordError != null,
                    supportingText = uiState.passwordError?.let {
                        {
                            Text(
                                text = it,
                                color = WikipediaTheme.colors.destructiveColor
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (uiState.is2faVisible || uiState.captchaActive) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onImeDone() }
                    ),
                    colors = textFieldColors
                )

                if (uiState.is2faVisible) {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.twoFactorCode,
                        onValueChange = onTwoFactorCodeChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(twoFactorFocusRequester),
                        label = {
                            Text(
                                text = stringResource(
                                    if (uiState.isEmailAuth) R.string.login_email_auth_hint else R.string.login_2fa_hint
                                )
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = if (uiState.captchaActive) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onImeDone() }
                        ),
                        colors = textFieldColors
                    )
                }

                if (uiState.captchaActive) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.edit_section_captcha_message),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = WikipediaTheme.colors.primaryColor,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .width(240.dp)
                                .height(80.dp)
                                .background(WikipediaTheme.colors.paperColor)
                                .clickable { onReloadCaptcha() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (uiState.captchaUrl != null) {
                                AsyncImage(
                                    model = uiState.captchaUrl,
                                    contentDescription = stringResource(R.string.captcha_image),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (uiState.isLoadingCaptcha) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WikipediaTheme.colors.progressiveColor,
                                    trackColor = WikipediaTheme.colors.borderColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.edit_section_captcha_reload),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = WikipediaTheme.colors.secondaryColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onReloadCaptcha() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = uiState.captchaWord,
                            onValueChange = onCaptchaWordChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(captchaFocusRequester),
                            label = {
                                Text(text = stringResource(R.string.edit_section_captcha_hint))
                            },
                            isError = uiState.captchaError != null,
                            supportingText = uiState.captchaError?.let {
                                {
                                    Text(
                                        text = it,
                                        color = WikipediaTheme.colors.destructiveColor
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { onImeDone() }
                            ),
                            colors = textFieldColors
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                AppButton(
                    onClick = onLoginClick,
                    enabled = isLoginButtonEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (uiState.isLoading) R.string.login_in_progress_dialog_message else R.string.menu_login
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = WikipediaTheme.colors.borderColor,
                    thickness = 0.5.dp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.login_dont_have_account),
                        color = WikipediaTheme.colors.secondaryColor,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AppButton(
                        onClick = onCreateAccountClick,
                        backgroundColor = WikipediaTheme.colors.backgroundColor,
                        contentColor = WikipediaTheme.colors.progressiveColor,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.login_join_wikipedia),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = WikipediaTheme.colors.borderColor,
                    thickness = 0.5.dp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.login_forgot_password),
                        color = WikipediaTheme.colors.progressiveColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onForgotPasswordClick() }
                            .padding(5.dp)
                    )

                    Text(
                        text = "•",
                        modifier = Modifier.padding(horizontal = 3.dp),
                        color = WikipediaTheme.colors.secondaryColor
                    )

                    Text(
                        text = stringResource(R.string.privacy_policy_description),
                        color = WikipediaTheme.colors.progressiveColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onPrivacyPolicyClick() }
                            .padding(5.dp)
                    )
                }

                if (uiState.isTemporaryAccount) {
                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = WikipediaTheme.colors.borderColor,
                        thickness = 0.5.dp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_temp_account),
                            contentDescription = null,
                            tint = WikipediaTheme.colors.secondaryColor,
                            modifier = Modifier
                                .padding(start = 12.dp, end = 12.dp)
                                .size(24.dp)
                        )

                        HtmlText(
                            text = stringResource(
                                R.string.temp_account_login_status,
                                uiState.tempAccountUserName.orEmpty()
                            ),
                            color = WikipediaTheme.colors.secondaryColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = WikipediaTheme.colors.progressiveColor,
                    trackColor = WikipediaTheme.colors.borderColor
                )
            }

            if (uiState.errorState != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WikipediaTheme.colors.paperColor),
                    contentAlignment = Alignment.Center
                ) {
                    WikiErrorView(
                        modifier = Modifier.fillMaxWidth(),
                        caught = uiState.errorState,
                        errorClickEvents = WikiErrorClickEvents(
                            backClickListener = onErrorBackClick,
                            retryClickListener = onErrorRetryClick
                        )
                    )
                }
            }
        }
    }
}
