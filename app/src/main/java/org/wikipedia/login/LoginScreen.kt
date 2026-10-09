package org.wikipedia.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.wikipedia.R
import org.wikipedia.compose.components.AppButton
import org.wikipedia.compose.components.HtmlText
import org.wikipedia.compose.components.WikiTopAppBar
import org.wikipedia.compose.components.error.WikiErrorClickEvents
import org.wikipedia.compose.components.error.WikiErrorView
import org.wikipedia.compose.theme.BaseTheme
import org.wikipedia.compose.theme.WikipediaTheme
import org.wikipedia.theme.Theme

data class LoginScreenCallbacks(
    val onBackClick: () -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTwoFactorChange: (String) -> Unit = {},
    val onCaptchaWordChange: (String) -> Unit = {},
    val onCaptchaImageClick: () -> Unit = {},
    val onCaptchaImageLoaded: () -> Unit = {},
    val onLoginClick: () -> Unit = {},
    val onCreateAccountClick: () -> Unit = {},
    val onForgotPasswordClick: () -> Unit = {},
    val onPrivacyPolicyClick: () -> Unit = {},
    val onErrorBackClick: () -> Unit = {},
    val onErrorRetryClick: () -> Unit = {}
)

@Composable
fun LoginScreen(
    state: LoginViewModel.LoginUiState,
    usernameFocusRequester: FocusRequester,
    twoFactorFocusRequester: FocusRequester,
    captchaFocusRequester: FocusRequester,
    callbacks: LoginScreenCallbacks,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        containerColor = WikipediaTheme.colors.paperColor,
        topBar = {
            WikiTopAppBar(
                title = stringResource(R.string.login_activity_title),
                onNavigationClick = callbacks.onBackClick
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LoginForm(state, usernameFocusRequester, twoFactorFocusRequester, captchaFocusRequester, callbacks)

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = WikipediaTheme.colors.progressiveColor,
                    trackColor = WikipediaTheme.colors.borderColor
                )
            }

            if (state.error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WikipediaTheme.colors.paperColor),
                    contentAlignment = Alignment.Center
                ) {
                    WikiErrorView(
                        caught = state.error,
                        errorClickEvents = WikiErrorClickEvents(
                            backClickListener = callbacks.onErrorBackClick,
                            retryClickListener = callbacks.onErrorRetryClick
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginViewModel.LoginUiState,
    usernameFocusRequester: FocusRequester,
    twoFactorFocusRequester: FocusRequester,
    captchaFocusRequester: FocusRequester,
    callbacks: LoginScreenCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
    ) {
        LoginTextField(
            value = state.username,
            onValueChange = callbacks.onUsernameChange,
            label = stringResource(R.string.login_username_hint),
            errorText = if (state.showUsernameError) stringResource(R.string.create_account_username_error) else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            modifier = Modifier.focusRequester(usernameFocusRequester)
        )

        var passwordVisible by rememberSaveable { mutableStateOf(false) }
        LoginTextField(
            value = state.password,
            onValueChange = callbacks.onPasswordChange,
            label = stringResource(R.string.login_password_hint),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { callbacks.onLoginClick() }),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        painter = painterResource(if (passwordVisible) R.drawable.ic_visibility_off_24dp else R.drawable.ic_visibility_24),
                        tint = WikipediaTheme.colors.placeholderColor,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        )

        AnimatedVisibility(visible = state.isTwoFactorVisible) {
            LoginTextField(
                value = state.twoFactorCode,
                onValueChange = callbacks.onTwoFactorChange,
                label = stringResource(state.twoFactorHint ?: R.string.login_2fa_hint),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .focusRequester(twoFactorFocusRequester)
            )
        }

        AnimatedVisibility(visible = state.captcha != null) {
            state.captcha?.let {
                LoginCaptcha(
                    captcha = it,
                    focusRequester = captchaFocusRequester,
                    onWordChange = callbacks.onCaptchaWordChange,
                    onImageClick = callbacks.onCaptchaImageClick,
                    onImageLoaded = callbacks.onCaptchaImageLoaded,
                    onDone = callbacks.onLoginClick
                )
            }
        }

        AppButton(
            onClick = callbacks.onLoginClick,
            enabled = state.isLoginEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .alpha(if (state.isLoginEnabled) 1f else 0.5f)
        ) {
            Text(
                text = stringResource(if (state.isLoading) R.string.login_in_progress_dialog_message else R.string.menu_login),
                style = MaterialTheme.typography.labelLarge
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = WikipediaTheme.colors.borderColor
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.login_dont_have_account),
                style = MaterialTheme.typography.bodyMedium,
                color = WikipediaTheme.colors.secondaryColor
            )
            AppButton(
                onClick = callbacks.onCreateAccountClick,
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

        LoginFooter(
            tempAccountName = state.tempAccountName,
            onForgotPasswordClick = callbacks.onForgotPasswordClick,
            onPrivacyPolicyClick = callbacks.onPrivacyPolicyClick
        )
    }
}

@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.colors(
            focusedTextColor = WikipediaTheme.colors.primaryColor,
            unfocusedTextColor = WikipediaTheme.colors.primaryColor,
            errorTextColor = WikipediaTheme.colors.primaryColor,
            focusedContainerColor = WikipediaTheme.colors.paperColor,
            unfocusedContainerColor = WikipediaTheme.colors.paperColor,
            errorContainerColor = WikipediaTheme.colors.paperColor,
            focusedIndicatorColor = WikipediaTheme.colors.progressiveColor,
            unfocusedIndicatorColor = WikipediaTheme.colors.borderColor,
            errorIndicatorColor = WikipediaTheme.colors.destructiveColor,
            focusedLabelColor = WikipediaTheme.colors.progressiveColor,
            unfocusedLabelColor = WikipediaTheme.colors.placeholderColor,
            errorLabelColor = WikipediaTheme.colors.destructiveColor,
            errorSupportingTextColor = WikipediaTheme.colors.destructiveColor,
            cursorColor = WikipediaTheme.colors.progressiveColor,
            errorCursorColor = WikipediaTheme.colors.destructiveColor
        )
    )
}

@Composable
private fun LoginCaptcha(
    captcha: LoginViewModel.CaptchaUiState,
    focusRequester: FocusRequester,
    onWordChange: (String) -> Unit,
    onImageClick: () -> Unit,
    onImageLoaded: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.edit_section_captcha_message),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontFamily = FontFamily.SansSerif),
            color = WikipediaTheme.colors.primaryColor,
            textAlign = TextAlign.Center
        )
        Column(
            modifier = Modifier
                .width(240.dp)
                .padding(top = 16.dp, bottom = 8.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(captcha.imageUrl)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = stringResource(R.string.captcha_image),
                contentScale = ContentScale.Fit,
                onSuccess = { onImageLoaded() },
                onError = { onImageLoaded() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(WikipediaTheme.colors.paperColor)
                    .clickable(onClick = onImageClick)
            )
            if (captcha.isImageLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = WikipediaTheme.colors.progressiveColor,
                    trackColor = WikipediaTheme.colors.borderColor
                )
            }
        }
        Text(
            text = stringResource(R.string.edit_section_captcha_reload),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = WikipediaTheme.colors.primaryColor,
            textAlign = TextAlign.Center
        )
        LoginTextField(
            value = captcha.word,
            onValueChange = onWordChange,
            label = stringResource(R.string.edit_section_captcha_hint),
            errorText = if (captcha.showError) stringResource(R.string.edit_section_captcha_hint) else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .padding(top = 16.dp, bottom = 8.dp)
                .focusRequester(focusRequester)
        )
    }
}

@Composable
private fun LoginFooter(
    tempAccountName: String?,
    onForgotPasswordClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = WikipediaTheme.colors.borderColor
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FooterLink(text = stringResource(R.string.login_forgot_password), onClick = onForgotPasswordClick)
            Text(
                text = "•",
                modifier = Modifier.padding(horizontal = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = WikipediaTheme.colors.primaryColor
            )
            FooterLink(text = stringResource(R.string.privacy_policy_description), onClick = onPrivacyPolicyClick)
        }
        if (tempAccountName != null) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                HorizontalDivider(
                    modifier = Modifier.padding(bottom = 8.dp),
                    thickness = 0.5.dp,
                    color = WikipediaTheme.colors.borderColor
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_temp_account),
                        tint = WikipediaTheme.colors.secondaryColor,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp)
                            .size(24.dp)
                    )
                    HtmlText(
                        text = stringResource(R.string.temp_account_login_status, tempAccountName),
                        modifier = Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(color = WikipediaTheme.colors.secondaryColor),
                        color = WikipediaTheme.colors.secondaryColor
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(5.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = WikipediaTheme.colors.progressiveColor
    )
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    BaseTheme(currentTheme = Theme.LIGHT) {
        LoginScreen(
            state = LoginViewModel.LoginUiState(username = "Example", password = "secret", twoFactorHint = R.string.login_2fa_hint),
            usernameFocusRequester = remember { FocusRequester() },
            twoFactorFocusRequester = remember { FocusRequester() },
            captchaFocusRequester = remember { FocusRequester() },
            callbacks = LoginScreenCallbacks()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenDarkPreview() {
    BaseTheme(currentTheme = Theme.DARK) {
        LoginScreen(
            state = LoginViewModel.LoginUiState(
                username = "Example",
                showUsernameError = true,
                captcha = LoginViewModel.CaptchaUiState(imageUrl = "", showError = true)
            ),
            usernameFocusRequester = remember { FocusRequester() },
            twoFactorFocusRequester = remember { FocusRequester() },
            captchaFocusRequester = remember { FocusRequester() },
            callbacks = LoginScreenCallbacks()
        )
    }
}
