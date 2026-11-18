package com.ahwotel

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ahwotel.oem.PublicKnoxBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun validKnoxKey(value: String) = value.length in 1..4096 && value.none(Char::isISOControl)
enum class KnoxState { NOT_CHECKED, UNAVAILABLE, NOT_CONFIGURED, WAITING_KEY, READY, PENDING, ACTIVE, RECORDED, ERROR, TIMED_OUT }
data class KnoxStateView(val state: KnoxState=KnoxState.NOT_CHECKED, val api: Int?=null, val hasKey: Boolean=false,
    val error: Int?=null, val granted: List<String> = emptyList(), val managedKey: Boolean=false)

/** Separate from Settings/portable profiles: no secret can enter their serializers. */
class KnoxController(private val app: MonitorApp,
    private val apiProbe: () -> Int? = { if(Build.MANUFACTURER.equals("samsung",true)) PublicKnoxBridge(app).apiLevel() else null },
    private val recordProbe: () -> Boolean = { PublicKnoxBridge(app).licenseState()=="RECORDED" },
    private val activator: (String,Boolean,(Boolean,Int,List<String>)->Unit)->Unit = { value,callback,result -> KnoxSdkClient.activate(app,value,callback,result) }) {
    private val prefs = app.getSharedPreferences("knox_private", Context.MODE_PRIVATE)
    val view = MutableStateFlow(KnoxStateView(hasKey=prefs.contains("cipher"), managedKey=ownsManagedKey()))
    private enum class Phase { IN_FLIGHT, RECOVERY_QUEUED, COMPLETE }
    private class Request(val id: Long, val secret: String, val callback: Boolean, val managed: Boolean)
    private var timeout: Job?=null
    private var operation=0L
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("ahwotel.knox",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ahwotel.knox",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun ownsManagedKey() = prefs.getBoolean("managedKey", prefs.getBoolean("managedRequested", false))
    private fun requested() = prefs.getBoolean("managedRequested", false)

    private fun resetAttempt(editor: android.content.SharedPreferences.Editor) =
        editor.remove("attempt").remove("phase").remove("recoveryUsed").remove("recordBefore")
            .remove("resultState").remove("resultCode")

    @Synchronized fun importLocalKey(value: String) {
        check(!ownsManagedKey() && !requested()) { "knox_managed_key" }
        storeKey(value, managed = false)
    }

    private fun storeKey(value: String, managed: Boolean) {
        require(validKnoxKey(value)) { "knox_key_invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(resetAttempt(prefs.edit()).putString("cipher", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putBoolean("managedKey", managed).commit())
        invalidate()
        view.value = KnoxStateView(KnoxState.READY, view.value.api, true, managedKey = managed)
        app.logs.event("knox_key_imported", diagnostic = true)
    }

    private fun readKey(): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(prefs.getString("cipher", null), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun recorded() = runCatching { recordProbe() }.getOrDefault(false)

    private fun persistedResult() = prefs.getString("resultState", null)?.let(KnoxState::valueOf)
    private fun phase() = prefs.getString("phase", null)?.let(Phase::valueOf)

    @Synchronized fun refresh() {
        val api = runCatching { apiProbe() }.getOrNull()
        val hasKey = prefs.contains("cipher")
        val result = if (requested()) persistedResult() else null
        val state = when {
            requested() && !hasKey -> KnoxState.WAITING_KEY
            api == null -> KnoxState.UNAVAILABLE
            view.value.state in setOf(KnoxState.PENDING, KnoxState.ACTIVE, KnoxState.ERROR, KnoxState.TIMED_OUT) -> view.value.state
            result != null -> result
            recorded() -> KnoxState.RECORDED
            hasKey -> KnoxState.READY
            else -> KnoxState.NOT_CONFIGURED
        }
        val code = if (result == state && prefs.contains("resultCode")) prefs.getInt("resultCode", 0) else view.value.error
        view.value = view.value.copy(state = state, api = api, hasKey = hasKey, error = code,
            managedKey = ownsManagedKey() || requested())
    }

    @Synchronized fun broadcastHint() {
        if (view.value.api == null) refresh()
        val api = view.value.api ?: return
        if (api >= 35 || !recorded()) return
        if (view.value.state == KnoxState.PENDING) {
            finish(operation, KnoxState.RECORDED, managed = requested())
        } else refresh()
    }

    @Synchronized fun configurationFailed() {
        view.value = view.value.copy(state = KnoxState.ERROR, error = null)
    }

    /** Stage the restriction only. SDK dispatch runs after the monitoring policy has been applied. */
    @Synchronized internal fun stageManaged(value: String?, activate: Boolean): Long? {
        if (value != null) {
            val same = ownsManagedKey() && runCatching { readKey() == value }.getOrDefault(false)
            if (!same) storeKey(value, managed = true)
        }
        if (requested() != activate) {
            check(resetAttempt(prefs.edit()).putBoolean("managedRequested", activate).commit())
            invalidate()
            view.value = view.value.copy(state = KnoxState.NOT_CHECKED, error = null, granted = emptyList())
        }
        if (!activate) {
            view.value = view.value.copy(managedKey = ownsManagedKey())
            return null
        }
        if (!prefs.contains("cipher")) {
            view.value = view.value.copy(state = KnoxState.WAITING_KEY, managedKey = true)
            return null
        }
        return operation
    }

    fun managed(value: String?, activate: Boolean) {
        stageManaged(value, activate)?.let(::dispatchManaged)
    }

    internal fun dispatchManaged(ticket: Long) = activate(managedTicket = ticket)

    private fun invalidate() {
        operation++
        timeout?.cancel()
        timeout = null
    }

    fun activate() = activate(managedTicket = null)

    private fun activate(managedTicket: Long?, recovery: Boolean = false) {
        val managed = managedTicket != null
        val request = synchronized(this) {
            if (managed && (managedTicket != operation || !requested())) return
            if (!managed && (ownsManagedKey() || requested() || app.managed.active.value)) return
            if (view.value.state == KnoxState.PENDING && !recovery) return
            val api = runCatching { apiProbe() }.getOrNull()
            if (api == null) {
                view.value = view.value.copy(api = null, state = KnoxState.UNAVAILABLE)
                return
            }
            view.value = view.value.copy(api = api)
            val secret = try { readKey() } catch (_: Exception) {
                configurationFailed()
                app.logs.event("knox_key_read_failed", error = true)
                return
            }
            val fingerprint = if (managed) MessageDigest.getInstance("SHA-256")
                .digest((app.packageName + secret).toByteArray()).joinToString("") { "%02x".format(it) } else ""
            if (managed && prefs.getString("attempt", null) == fingerprint) {
                when (phase()) {
                    Phase.COMPLETE -> { refresh(); return }
                    // Pre-code40 state did not distinguish dispatch from completion.
                    null -> { if (recorded()) { finish(operation, KnoxState.RECORDED, managed = true); return } }
                    Phase.IN_FLIGHT, Phase.RECOVERY_QUEUED -> {
                        if (!recovery) { queueRecovery(operation); return }
                    }
                }
            }
            if (managed) {
                check(prefs.edit().putString("attempt", fingerprint).putString("phase", Phase.IN_FLIGHT.name)
                    .putBoolean("recoveryUsed", recovery).putBoolean("recordBefore", recorded()).commit())
            }
            invalidate()
            val id = operation
            view.value = view.value.copy(state = KnoxState.PENDING, error = null, granted = emptyList())
            timeout = app.scope.launch {
                delay(120_000)
                synchronized(this@KnoxController) { finish(id, KnoxState.TIMED_OUT, managed = managed) }
            }
            Request(id, secret, api >= 35, managed)
        }
        try {
            activator(request.secret, request.callback) { success, code, permissions ->
                synchronized(this) {
                    if (finish(request.id, if (success) KnoxState.ACTIVE else KnoxState.ERROR, request.managed, code, permissions)) {
                        app.logs.event("knox_activation_result", error = !success, number = code.toLong())
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(this) {
                if (request.id == operation) {
                    if (managed) queueRecovery(request.id) else finish(request.id, KnoxState.ERROR, managed = false)
                }
            }
            app.logs.event("knox_activation_failed", error = true)
        } catch (_: LinkageError) {
            synchronized(this) { finish(request.id, KnoxState.UNAVAILABLE, request.managed) }
            app.logs.event("knox_activation_unavailable", error = true)
        }
    }

    /** Called under the state lock. One delayed retry can survive process loss while queued. */
    private fun queueRecovery(id: Long) {
        if (id != operation) return
        if (recorded() && !prefs.getBoolean("recordBefore", true)) {
            finish(id, KnoxState.RECORDED, managed = true)
            return
        }
        if (prefs.getBoolean("recoveryUsed", false)) {
            finish(id, KnoxState.ERROR, managed = true)
            return
        }
        check(prefs.edit().putString("phase", Phase.RECOVERY_QUEUED.name).commit())
        invalidate()
        val ticket = operation
        view.value = view.value.copy(state = KnoxState.PENDING)
        timeout = app.scope.launch {
            delay(5_000)
            synchronized(this@KnoxController) { if (operation == ticket) timeout = null }
            runCatching { activate(managedTicket = ticket, recovery = true) }.onFailure {
                synchronized(this@KnoxController) { if (operation == ticket) configurationFailed() }
                app.logs.event("knox_managed_configuration_failed", error = true)
            }
        }
    }

    private fun finish(id: Long, state: KnoxState, managed: Boolean, code: Int? = null,
        permissions: List<String> = emptyList()): Boolean {
        if (id != operation) return false
        invalidate()
        view.value = view.value.copy(state = state, error = code,
            granted = permissions.filter { it.startsWith("com.samsung.android.knox.permission.") }.take(128))
        if (managed) {
            val editor = prefs.edit().putString("phase", Phase.COMPLETE.name).putString("resultState", state.name)
            if (code == null) editor.remove("resultCode") else editor.putInt("resultCode", code)
            if (!editor.commit()) app.logs.event("knox_activation_result_save_failed", error = true)
        }
        return true
    }

}

/** Referenced only after Samsung/library checks; SDK stubs are never packaged in the APK. */
private object KnoxSdkClient {
    fun activate(context: Context, value: String, callback: Boolean, result: (Boolean,Int,List<String>)->Unit) {
        val manager=com.samsung.android.knox.license.KnoxEnterpriseLicenseManager.getInstance(context)
        if(callback) manager.activateLicense(value, object: com.samsung.android.knox.license.LicenseResultCallback {
            override fun onLicenseResult(value: com.samsung.android.knox.license.LicenseResult) {
                if(value.packageName==context.packageName && value.isActivation) result(value.isSuccess,value.errorCode,value.grantedPermissions.orEmpty())
            }
        }) else manager.activateLicense(value)
    }
}

class KnoxLicenseReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context: Context,intent: android.content.Intent) {
        if(intent.action!="com.samsung.android.knox.intent.action.KNOX_LICENSE_STATUS") return
        // Broadcast is only a hint, never authority for success or permissions.
        val app=context.applicationContext as MonitorApp
        app.scope.launch { app.knox.broadcastHint() }
    }
}
