package com.ahwotel.oem

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException

/** Only documented public SDK methods. No private members, setAccessible, Binder or dex loading. */
enum class KnoxCall(val metric: OemMetric, val accessor: String, val method: String,
    val argument: Boolean? = null, val requiredPermission: String? = null, val licensed: Boolean = false,
    val minimumApi: Int = 24, val scope: OemScope = OemScope.CURRENT_USER) {
    CAMERA(OemMetric.CAMERA_POLICY, "getRestrictionPolicy", "isCameraEnabled", false),
    USB(OemMetric.USB_POLICY, "getRestrictionPolicy", "isUsbHostStorageAllowed", scope = OemScope.DEVICE),
    DEBUG(OemMetric.DEBUGGING_POLICY, "getRestrictionPolicy", "isUsbDebuggingEnabled", scope = OemScope.DEVICE),
    BLUETOOTH(OemMetric.BLUETOOTH_POLICY, "getRestrictionPolicy", "isBluetoothEnabled", false),
    WIFI(OemMetric.WIFI_POLICY, "getRestrictionPolicy", "isWiFiEnabled", false),
    INSTALL(OemMetric.INSTALLATION_POLICY, "getApplicationPolicy", "getApplicationInstallationMode"),
    RESET(OemMetric.FACTORY_RESET_POLICY, "getRestrictionPolicy", "isFactoryResetAllowed", scope = OemScope.DEVICE),
    VPN(OemMetric.VPN_POLICY, "getRestrictionPolicy", "isVpnAllowed", scope = OemScope.DEVICE),
    LOCK(OemMetric.SCREEN_LOCK, "getDeviceInventory", "isDeviceSecure"),
    PACKAGES(OemMetric.PACKAGE_COUNT, "getApplicationPolicy", "getInstalledApplicationsIDList", requiredPermission = "com.samsung.android.knox.permission.KNOX_APP_MGMT", licensed = true),
}

interface KnoxBridge {
    fun apiLevel(): Int
    fun call(call: KnoxCall): Any?
    fun licenseState(): String = "UNKNOWN"
}

class PublicKnoxBridge(private val context: Context) : KnoxBridge {
    private fun sdk() = Class.forName("com.samsung.android.knox.EnterpriseDeviceManager", false, context.classLoader)
    override fun apiLevel() = sdk().getMethod("getAPILevel").invoke(null) as Int
    override fun call(call: KnoxCall): Any? {
        val manager = sdk().getMethod("getInstance", Context::class.java).invoke(null, context) ?: return null
        val accessor = sdk().getMethod(call.accessor)
        val policy = accessor.invoke(manager) ?: return null
        return if (call.argument != null) accessor.returnType.getMethod(call.method, Boolean::class.javaPrimitiveType).invoke(policy, call.argument)
            else accessor.returnType.getMethod(call.method).invoke(policy)
    }
    override fun licenseState(): String = try {
        require(apiLevel() >= 33)
        val clazz = Class.forName("com.samsung.android.knox.license.KnoxEnterpriseLicenseManager", false, context.classLoader)
        val manager = clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
        if (clazz.getMethod("getLicenseActivationInfo").invoke(manager) == null) "NOT_RECORDED" else "RECORDED"
    } catch (_: Exception) { "UNKNOWN" } catch (_: LinkageError) { "UNKNOWN" }
}

class SamsungKnoxProvider(private val context: Context, private val bridge: KnoxBridge = PublicKnoxBridge(context)) : OemTelemetryProvider {
    override val id = "samsung_knox"
    @Volatile var license = "UNKNOWN"
        private set
    override fun collect(category: OemCategory): ProviderBatch {
        val calls = KnoxCall.entries.filter { it.metric.category == category }
        val metrics = (calls.map { it.metric } + when(category) {
            OemCategory.DEVICE -> listOf(OemMetric.KNOX_API, OemMetric.KNOX_VERSION)
            OemCategory.SECURITY -> listOf(OemMetric.INTEGRITY)
            OemCategory.RESOURCES -> listOf(OemMetric.OEM_SYSTEM_CPU, OemMetric.OEM_THERMAL)
            else -> emptyList()
        }).distinct()
        fun failure(m: OemMetric, status: OemStatus, reason: OemReason) = OemObservation(m, id, OemSource.OEM,
            calls.find { it.metric == m }?.scope ?: OemScope.DEVICE, status = status, reason = reason)
        val api = try { bridge.apiLevel() } catch (e: Throwable) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            val (status, reason) = classifyKnoxFailure(e)
            return ProviderBatch(metrics.map { failure(it, status, reason) })
        }
        if (api < 24) return ProviderBatch(metrics.map { failure(it, OemStatus.UNSUPPORTED, OemReason.API_LEVEL) })
        val rows = mutableListOf<OemObservation>()
        var inventory: InventorySnapshot? = null
        if (category == OemCategory.DEVICE) {
            license = bridge.licenseState()
            rows += OemObservation(OemMetric.KNOX_API, id, OemSource.OEM, OemScope.DEVICE, number = api.toDouble())
            val versions = listOf("3.0", "3.1", "3.2", "3.2.1", "3.3", "3.4", "3.4.1", "3.5", "3.6", "3.7", "3.7.1", "3.8", "3.9", "3.10", "3.11", "3.12", "3.13", "3.14")
            val version = versions.getOrNull(api - 24)
            rows += if (version == null) failure(OemMetric.KNOX_VERSION, OemStatus.UNAVAILABLE, OemReason.API_LEVEL)
                else OemObservation(OemMetric.KNOX_VERSION, id, OemSource.OEM, OemScope.DEVICE, text = version, quality = Quality.DERIVED)
        }
        for (call in calls) {
            if (api < call.minimumApi) { rows += failure(call.metric, OemStatus.UNSUPPORTED, OemReason.API_LEVEL); continue }
            try {
                val value = bridge.call(call)
                if (call == KnoxCall.PACKAGES) {
                    val names = (value as? Array<*>)?.filterIsInstance<String>()
                    if (names == null) { rows += failure(call.metric, OemStatus.UNAVAILABLE, OemReason.MISSING_VALUE); continue }
                    val entries = names.distinct().sorted().map { name ->
                        try {
                            val info = context.packageManager.getPackageInfo(name, 0)
                            InventoryEntry(name, info.versionName?.take(128), if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                                info.applicationInfo?.enabled, info.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 })
                        } catch (_: PackageManager.NameNotFoundException) { InventoryEntry(name, null, null, null, null) }
                    }
                    inventory = InventorySnapshot(id, entries, true)
                    rows += OemObservation(call.metric, id, OemSource.OEM, call.scope, number = entries.size.toDouble())
                    continue
                }
                val text = when {
                    call == KnoxCall.INSTALL && value is Number -> when(value.toInt()) { 0 -> "RESTRICTED"; 1 -> "ALLOWED"; else -> null }
                    value is Boolean && call.metric.kind == ValueKind.STATE -> if (value) "ALLOWED" else "RESTRICTED"
                    else -> null
                }
                rows += when {
                    text != null -> OemObservation(call.metric, id, OemSource.OEM, call.scope, text = text)
                    value is Boolean && call.metric.kind == ValueKind.BOOLEAN -> OemObservation(call.metric, id, OemSource.OEM, call.scope, number = if (value) 1.0 else 0.0)
                    else -> failure(call.metric, OemStatus.UNAVAILABLE, OemReason.MISSING_VALUE)
                }
            } catch (e: Throwable) {
                if (e is VirtualMachineError || e is ThreadDeath) throw e
                val (status, reason) = classifyKnoxFailure(e)
                // A denied permission is not proof that a license is missing or that the phone lacks Knox.
                rows += failure(call.metric, status, reason)
            }
        }
        metrics.filter { m -> rows.none { it.metric == m } }.forEach { rows += failure(it, OemStatus.UNSUPPORTED, OemReason.NO_OFFICIAL_API) }
        return ProviderBatch(rows, inventory)
    }
}

fun classifyKnoxFailure(error: Throwable): Pair<OemStatus, OemReason> {
    val cause = if (error is InvocationTargetException) error.targetException else error
    return when (cause) {
        is ClassNotFoundException, is NoClassDefFoundError -> OemStatus.UNAVAILABLE to OemReason.LIBRARY_MISSING
        is NoSuchMethodException, is NoSuchMethodError -> OemStatus.UNSUPPORTED to OemReason.METHOD_MISSING
        is SecurityException, is IllegalAccessException -> OemStatus.PERMISSION_DENIED to OemReason.PERMISSION
        else -> OemStatus.ERROR to OemReason.READ_FAILED
    }
}
