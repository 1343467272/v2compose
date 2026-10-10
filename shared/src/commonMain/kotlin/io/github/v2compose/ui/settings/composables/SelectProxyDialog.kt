package io.github.v2compose.ui.settings.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.singbox.SingBoxConfig
import io.github.v2compose.ui.settings.SingBoxImportState
import io.github.v2compose.usecase.RefreshSingBoxSubscriptionUseCase
import io.github.v2compose.util.InetValidator
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import v2compose.shared.generated.resources.Res
import v2compose.shared.generated.resources.ok
import v2compose.shared.generated.resources.settings_proxy
import v2compose.shared.generated.resources.settings_proxy_direct
import v2compose.shared.generated.resources.settings_proxy_hostOrIp
import v2compose.shared.generated.resources.settings_proxy_hostOrIp_empty
import v2compose.shared.generated.resources.settings_proxy_hostOrIp_format_error
import v2compose.shared.generated.resources.settings_proxy_http
import v2compose.shared.generated.resources.settings_proxy_port
import v2compose.shared.generated.resources.settings_proxy_port_empty
import v2compose.shared.generated.resources.settings_proxy_port_error
import v2compose.shared.generated.resources.settings_proxy_singbox
import v2compose.shared.generated.resources.settings_proxy_socks
import v2compose.shared.generated.resources.settings_proxy_system
import v2compose.shared.generated.resources.settings_singbox_config
import v2compose.shared.generated.resources.settings_singbox_config_empty
import v2compose.shared.generated.resources.settings_singbox_config_hint
import v2compose.shared.generated.resources.settings_singbox_invalid_json
import v2compose.shared.generated.resources.settings_singbox_import
import v2compose.shared.generated.resources.settings_singbox_import_empty_content
import v2compose.shared.generated.resources.settings_singbox_import_empty_url
import v2compose.shared.generated.resources.settings_singbox_import_network_failed
import v2compose.shared.generated.resources.settings_singbox_import_no_nodes
import v2compose.shared.generated.resources.settings_singbox_import_success
import v2compose.shared.generated.resources.settings_singbox_import_unrecognized
import v2compose.shared.generated.resources.settings_singbox_importing
import v2compose.shared.generated.resources.settings_singbox_no_outbound
import v2compose.shared.generated.resources.settings_singbox_not_object
import v2compose.shared.generated.resources.settings_singbox_subscription
import v2compose.shared.generated.resources.settings_singbox_subscription_hint

@Composable
fun SelectProxyDialog(
    proxyInfo: ProxyInfo,
    importState: SingBoxImportState,
    onImportSubscription: (String) -> Unit,
    onDismiss: () -> Unit,
    onProxySelected: (ProxyInfo) -> Unit,
) {

    var proxyType by remember(proxyInfo) { mutableStateOf(proxyInfo.type) }
    var proxyAddress by remember(proxyInfo) { mutableStateOf(proxyInfo.address) }
    var proxyPort by remember(proxyInfo) { mutableStateOf(proxyInfo.port.toString()) }
    var singboxConfig by remember(proxyInfo) { mutableStateOf(proxyInfo.singboxConfig) }
    var subscriptionUrl by remember(proxyInfo) {
        mutableStateOf(proxyInfo.singboxSubscriptionUrl)
    }

    val inputEnabled =
        remember(proxyType) { proxyType != ProxyType.System && proxyType != ProxyType.Direct }

    var proxyAddressError by remember { mutableStateOf<StringResource?>(null) }
    var proxyPortError by remember { mutableStateOf<StringResource?>(null) }
    var singboxError by remember { mutableStateOf<SingBoxConfig.Error?>(null) }

    val adressFocusRequester = remember { FocusRequester() }
    val portFocusRequester = remember { FocusRequester() }

    val checkProxyInfo = {
        while (true) {
            if (proxyType == ProxyType.Direct || proxyType == ProxyType.System) {
                onProxySelected(ProxyInfo(proxyType))
                break
            }

            if (proxyType == ProxyType.SingBox) {
                val error = SingBoxConfig.validate(singboxConfig)
                if (error != null) {
                    singboxError = error
                    break
                }
                val url = subscriptionUrl.trim()
                onProxySelected(
                    ProxyInfo(
                        type = ProxyType.SingBox,
                        singboxConfig = singboxConfig.trim(),
                        singboxSubscriptionUrl = url,
                        // 换了订阅链接就重置更新时间，下次启动会立即拉取
                        singboxSubscriptionUpdatedAt = if (url == proxyInfo.singboxSubscriptionUrl) {
                            proxyInfo.singboxSubscriptionUpdatedAt
                        } else {
                            0
                        },
                    )
                )
                break
            }

            val address = proxyAddress.trim()
            if (address.isEmpty()) {
                proxyAddressError = Res.string.settings_proxy_hostOrIp_empty
                break
            }
            if (!InetValidator.isValidHostOrIp(address)) {
                proxyAddressError = Res.string.settings_proxy_hostOrIp_format_error
                break
            }

            val port = proxyPort.trim()
            val portInt = port.toIntOrNull() ?: -1
            if (port.isEmpty()) {
                proxyPortError = Res.string.settings_proxy_port_empty
                break
            }
            if (!InetValidator.isValidInetPort(portInt)) {
                proxyPortError = Res.string.settings_proxy_port_error
                break
            }
            onProxySelected(ProxyInfo(proxyType, address, portInt))
            break
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_proxy)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SelectProxyType(proxyType, onProxyTypeSelected = { proxyType = it })
                Spacer(modifier = Modifier.height(4.dp))
                if (proxyType == ProxyType.SingBox) {
                    OutlinedTextField(
                        value = TextFieldValue(subscriptionUrl, TextRange(subscriptionUrl.length)),
                        onValueChange = { subscriptionUrl = it.text },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.settings_singbox_subscription)) },
                        supportingText = {
                            Text(stringResource(Res.string.settings_singbox_subscription_hint))
                        },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onImportSubscription(subscriptionUrl) },
                            enabled = importState != SingBoxImportState.Loading,
                        ) {
                            Text(stringResource(Res.string.settings_singbox_import))
                        }
                        ImportStateText(importState)
                    }
                    OutlinedTextField(
                        value = TextFieldValue(singboxConfig, TextRange(singboxConfig.length)),
                        onValueChange = {
                            singboxConfig = it.text
                            singboxError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        label = { Text(stringResource(Res.string.settings_singbox_config)) },
                        supportingText = {
                            val error = singboxError
                            if (error != null) {
                                Text(singBoxErrorText(error))
                            } else {
                                Text(stringResource(Res.string.settings_singbox_config_hint))
                            }
                        },
                        isError = singboxError != null,
                        singleLine = false,
                        enabled = inputEnabled,
                    )
                } else {
                    OutlinedTextField(
                        value = TextFieldValue(proxyAddress, TextRange(proxyAddress.length)),
                        onValueChange = {
                            proxyAddress = it.text
                            proxyAddressError = null
                        },
                        modifier = Modifier.focusRequester(adressFocusRequester),
                        label = { Text(stringResource(Res.string.settings_proxy_hostOrIp)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { portFocusRequester.requestFocus() }),
                        isError = proxyAddressError != null,
                        supportingText = { proxyAddressError?.let { Text(stringResource(it)) } },
                        singleLine = true,
                        enabled = inputEnabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = TextFieldValue(proxyPort, TextRange(proxyPort.length)),
                        onValueChange = {
                            proxyPort = it.text
                            proxyPortError = null
                        },
                        modifier = Modifier.focusRequester(portFocusRequester),
                        label = { Text(stringResource(Res.string.settings_proxy_port)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { checkProxyInfo() }),
                        isError = proxyPortError != null,
                        supportingText = { proxyPortError?.let { Text(stringResource(it)) } },
                        singleLine = true,
                        enabled = inputEnabled,
                    )

                    LaunchedEffect(true) {
                        adressFocusRequester.requestFocus()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = checkProxyInfo) {
                Text(stringResource(Res.string.ok))
            }
        })
}

@Composable
private fun ImportStateText(state: SingBoxImportState) {
    val text = when (state) {
        SingBoxImportState.Idle -> null
        SingBoxImportState.Loading -> stringResource(Res.string.settings_singbox_importing)
        is SingBoxImportState.Success ->
            stringResource(Res.string.settings_singbox_import_success) + " · " + state.nodeCount

        is SingBoxImportState.Failed -> importFailedText(state)
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is SingBoxImportState.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun importFailedText(state: SingBoxImportState.Failed): String {
    val reason = when (state.reason) {
        RefreshSingBoxSubscriptionUseCase.FailureReason.EMPTY_URL ->
            stringResource(Res.string.settings_singbox_import_empty_url)

        RefreshSingBoxSubscriptionUseCase.FailureReason.NETWORK ->
            stringResource(Res.string.settings_singbox_import_network_failed)

        RefreshSingBoxSubscriptionUseCase.FailureReason.EMPTY_CONTENT ->
            stringResource(Res.string.settings_singbox_import_empty_content)

        RefreshSingBoxSubscriptionUseCase.FailureReason.NO_NODES ->
            stringResource(Res.string.settings_singbox_import_no_nodes)

        RefreshSingBoxSubscriptionUseCase.FailureReason.UNRECOGNIZED ->
            stringResource(Res.string.settings_singbox_import_unrecognized)
    }
    val message = state.message?.takeIf { it.isNotBlank() } ?: return reason
    return reason + ": " + message
}

@Composable
private fun singBoxErrorText(error: SingBoxConfig.Error): String = when (error) {
    SingBoxConfig.Error.EMPTY -> stringResource(Res.string.settings_singbox_config_empty)
    SingBoxConfig.Error.INVALID_JSON -> stringResource(Res.string.settings_singbox_invalid_json)
    SingBoxConfig.Error.NOT_OBJECT -> stringResource(Res.string.settings_singbox_not_object)
    SingBoxConfig.Error.NO_OUTBOUND -> stringResource(Res.string.settings_singbox_no_outbound)
}

@Composable
private fun SelectProxyType(proxyType: ProxyType, onProxyTypeSelected: (ProxyType) -> Unit) {
    var showProxyTypeDropdown by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showProxyTypeDropdown = true }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(proxyType.titleRes))
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { showProxyTypeDropdown = true }) {
            Icon(Icons.Rounded.ArrowDropDown, "drop down")
        }

        DropdownMenu(
            expanded = showProxyTypeDropdown,
            onDismissRequest = { showProxyTypeDropdown = false },
            properties = PopupProperties(focusable = false),
        ) {
            listOf(ProxyType.Direct, ProxyType.Http, ProxyType.Socks, ProxyType.SingBox).forEach {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(it.titleRes))
                    },
                    onClick = {
                        showProxyTypeDropdown = false
                        onProxyTypeSelected(it)
                    },
                )
            }
        }
    }
}

val ProxyType.titleRes: StringResource
    get() = when (this) {
        ProxyType.System -> Res.string.settings_proxy_system
        ProxyType.Direct -> Res.string.settings_proxy_direct
        ProxyType.Http -> Res.string.settings_proxy_http
        ProxyType.Socks -> Res.string.settings_proxy_socks
        ProxyType.SingBox -> Res.string.settings_proxy_singbox
    }
