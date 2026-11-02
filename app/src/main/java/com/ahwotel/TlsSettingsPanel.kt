package com.ahwotel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun activity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> activity(context.baseContext)
    else -> null
}

@Composable fun TlsSettingsPanel(app: MonitorApp, draft: Settings, managed: Boolean, update: (Settings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    val currentManaged by rememberUpdatedState(managed)
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var certificate by remember { mutableStateOf<Pair<String, String>?>(null) }
    val requestKey = "${draft.tls}:${draft.endpoint}:${draft.allowHttp}"
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val ref = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(65537); var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        buffer.copyOf(count)
                    } ?: error("input")
                    TlsTrustStore.install(context, bytes)
                }
                if (!currentManaged && currentDraft.tls.mode == "mtls")
                    update(currentDraft.copy(tls = currentDraft.tls.copy(trust = "custom", trustRef = ref)))
            } catch (_: Exception) { result = requestKey to "tls_ca_invalid" }
        }
    }
    Choice(draft.tls.mode, listOf("none" to "None", "mtls" to "mTLS")) { update(draft.copy(tls = draft.tls.copy(mode = it))) }
    if (draft.tls.mode == "mtls") {
        TlsRuleEditor(app, draft, managed, update)
        Field(stringResource(R.string.tls_alias), draft.tls.alias) { update(draft.copy(tls = draft.tls.copy(alias = it))) }
        OutlinedButton(enabled = !managed, onClick = {
            activity(context)?.let { host -> KeyChain.choosePrivateKeyAlias(host, { alias ->
                if (alias != null) scope.launch {
                    if (!currentManaged && currentDraft.tls.mode == "mtls") update(currentDraft.copy(tls = currentDraft.tls.copy(alias = alias)))
                }
            }, arrayOf("RSA", "EC"), null, runCatching { java.net.URI(draft.endpoint).host }.getOrNull(),
                runCatching { java.net.URI(draft.endpoint).port.takeIf { it > 0 } ?: 443 }.getOrDefault(443), draft.tls.alias.ifBlank { null }) }
        }) { Text(stringResource(R.string.tls_choose)) }
        Choice(draft.tls.trust, listOf("system" to stringResource(R.string.tls_system), "custom" to stringResource(R.string.tls_custom))) {
            update(draft.copy(tls = draft.tls.copy(trust = it)))
        }
        if (draft.tls.trust == "custom") {
            Field(stringResource(R.string.tls_ref), draft.tls.trustRef) { update(draft.copy(tls = draft.tls.copy(trustRef = it))) }
            OutlinedButton(enabled = !managed, onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.tls_import)) }
        }
    }
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        certificate = null
        scope.launch {
            val target = draft
            val key = requestKey
            val value = withContext(Dispatchers.IO) {
                try {
                    if (target.tls.mode == "mtls" && !target.endpoint.startsWith("https://")) throw TlsFailure("tls_https_required")
                    require(Settings.validEndpoint(target.endpoint, target.allowHttp))
                    if (target.tls.mode == "mtls") {
                        val resolved = if (!managed && target.tls.selection == "rule") target.tls.alias else resolveClientAlias(app, target.tls)
                        val chain = try { KeyChain.getCertificateChain(app, resolved) } catch (_: Exception) { null }
                            ?: throw TlsFailure("tls_certificate_missing")
                        val leaf = chain.firstOrNull() ?: throw TlsFailure("tls_certificate_missing")
                        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(target.language))
                        val details = context.getString(R.string.tls_certificate_details, leaf.subjectX500Principal.name,
                            leaf.issuerX500Principal.name, dates.format(leaf.notBefore), dates.format(leaf.notAfter))
                        withContext(Dispatchers.Main) { certificate = key to details }
                    }
                    val sender = if (!managed && target.tls.selection == "rule") OtlpSender(identity = { _, tls -> androidMtlsPreviewContext(app, tls) }) else app.sender
                    val code = sender.send(OutboxRow(createdAt = System.currentTimeMillis(), endpoint = target.endpoint, payload = byteArrayOf()), target.allowHttp, app, target.tls)
                    if (code in 200..298) "tls_check_ok" else "tls_check_failed"
                } catch (e: TlsFailure) { e.code } catch (_: Exception) { "tls_check_failed" }
            }
            result = key to value; busy = false
        }
    }) { Text(stringResource(R.string.tls_check)) }
    result?.takeIf { it.first == requestKey }?.let { Text(tlsMessage(it.second)) }
    certificate?.takeIf { it.first == requestKey && draft.tls.mode == "mtls" }?.let { Text(it.second, style = MaterialTheme.typography.bodySmall) }
    Text(stringResource(if(draft.tls.selection == "rule") R.string.tls_rule_queue_warning else R.string.tls_queue_warning), style = MaterialTheme.typography.bodySmall)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        CompactInfoButton(stringResource(R.string.tls_title), "tls_help") { help = true }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.tls_title)) },
        text = { Text(stringResource(R.string.tls_help), Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ help = false }) { Text(stringResource(R.string.help_close)) } })
}

@Composable internal fun tlsMessage(code: String) = stringResource(when (code) {
    "tls_binding_missing" -> R.string.tls_binding_missing
    "tls_delegation_missing" -> R.string.tls_delegation_missing
    "tls_rule_mismatch" -> R.string.tls_rule_mismatch
    "tls_rule_ok" -> R.string.tls_rule_ok
    "tls_check_ok" -> R.string.tls_check_ok
    "tls_key_missing", "tls_key_access_denied", "tls_certificate_missing" -> R.string.tls_key_denied
    "tls_ca_missing", "tls_ca_invalid" -> R.string.tls_ca_invalid
    "tls_certificate_expired" -> R.string.tls_expired
    "tls_certificate_usage", "tls_key_algorithm" -> R.string.tls_usage
    "tls_https_required" -> R.string.tls_https
    "tls_hostname_failed" -> R.string.tls_hostname
    "tls_handshake_failed" -> R.string.tls_handshake
    else -> R.string.tls_check_failed
})
