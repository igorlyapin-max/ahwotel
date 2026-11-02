package com.ahwotel

import android.security.KeyChain
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun TlsRuleEditor(app: MonitorApp, draft: Settings, managed: Boolean, update: (Settings) -> Unit) {
    val tls = draft.tls
    val scope = rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    val currentManaged by rememberUpdatedState(managed)
    val currentUpdate by rememberUpdatedState(update)
    var extraction by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tls.mode, tls.selection, tls.alias, tls.rule, managed) { extraction++ }
    var status by remember(tls) { mutableStateOf<String?>(null) }
    var checks by remember(tls) { mutableStateOf<Map<String,Boolean>>(emptyMap()) }
    val runtimeStatus by app.tlsStatus.collectAsStateWithLifecycle()
    Choice(tls.selection, listOf("manual" to stringResource(R.string.tls_manual), "rule" to stringResource(R.string.tls_rule))) {
        update(draft.copy(tls = tls.copy(selection = it)))
    }
    if (tls.selection != "rule") return
    if (managed && runtimeStatus.isNotEmpty()) Text(tlsMessage(runtimeStatus))
    val r = tls.rule
    fun change(rule: CertificateRule) = update(draft.copy(tls = tls.copy(rule = rule)))
    Field(stringResource(R.string.tls_template), r.templateOid) { change(r.copy(templateOid = it.trim())) }
    Field(stringResource(R.string.tls_policy), r.policyOid) { change(r.copy(policyOid = it.trim())) }
    Field(stringResource(R.string.tls_san), r.sanUri) { change(r.copy(sanUri = it.trim())) }
    Field(stringResource(R.string.tls_issuer), r.issuer) { change(r.copy(issuer = it.trim())) }
    Field(stringResource(R.string.tls_ou), r.ou) { change(r.copy(ou = it)) }
    Toggle(stringResource(R.string.tls_delegate), tls.delegated) { update(draft.copy(tls = tls.copy(delegated = it))) }
    OutlinedButton(enabled = !managed, onClick = {
        val operation = ++extraction
        val original = tls
        scope.launch {
        try {
            val rule = withContext(Dispatchers.IO) {
                val cert = KeyChain.getCertificateChain(app, tls.alias)?.firstOrNull() ?: throw TlsFailure("tls_key_missing")
                val template = certificateTemplate(cert).orEmpty()
                CertificateRule(templateOid = template, policyOid = if (template.isEmpty()) certificatePolicies(cert).firstOrNull().orEmpty() else "")
            }
            if (operation == extraction && !currentManaged) {
                applyExtractedRule(currentDraft, original, rule)?.let(currentUpdate)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (operation == extraction && !currentManaged && currentDraft.tls == original) status = "tls_key_missing" }
    } }) { Text(stringResource(R.string.tls_create_rule)) }
    OutlinedButton(onClick = { scope.launch {
        status = withContext(Dispatchers.IO) {
            try {
                val alias = if (managed) resolveClientAlias(app, tls) else tls.alias
                val chain = KeyChain.getCertificateChain(app, alias) ?: throw TlsFailure("tls_key_missing")
                val results = r.checks(chain.first())
                withContext(Dispatchers.Main) { checks = results }
                validateClientCertificate(chain); r.verify(chain.first()); "tls_rule_ok"
            } catch (e: TlsFailure) { e.code } catch (_: Exception) { "tls_rule_mismatch" }
        }
    } }) { Text(stringResource(R.string.tls_test_rule)) }
    status?.let { Text(tlsMessage(it)) }
    checks.forEach { (name, ok) ->
        val title = when(name) { "template" -> R.string.tls_template; "policy" -> R.string.tls_policy; "san" -> R.string.tls_san;
            "issuer" -> R.string.tls_issuer; else -> R.string.tls_ou }
        Text(stringResource(title) + ": " + if(ok) "✓" else "✗")
    }
    Text(stringResource(R.string.tls_rule_help), style = MaterialTheme.typography.bodySmall)
    if (!managed) Text(stringResource(R.string.tls_preview_help), style = MaterialTheme.typography.bodySmall)
}

internal fun applyExtractedRule(current: Settings, original: TlsSettings, rule: CertificateRule): Settings? {
    val now = current.tls
    if (now.mode != original.mode || now.selection != original.selection || now.alias != original.alias || now.rule != original.rule) return null
    return current.copy(tls = now.copy(rule = rule))
}
