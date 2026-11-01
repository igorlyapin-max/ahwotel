package com.ahwotel.oem

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings

class AndroidStandardProvider(private val context: Context) : OemTelemetryProvider {
    override val id = "android_standard"
    private data class CpuBaseline(val elapsed: Long, val cpu: Long, val generation: Long)
    @Volatile private var cpuBaseline: CpuBaseline? = null
    private val resetGeneration = java.util.concurrent.atomic.AtomicLong(0)
    override fun reset() { resetGeneration.incrementAndGet(); cpuBaseline = null }

    override fun collect(category: OemCategory): ProviderBatch {
        val rows = mutableListOf<OemObservation>()
        fun missing(m: OemMetric, reason: OemReason = OemReason.NO_OFFICIAL_API, status: OemStatus = OemStatus.UNSUPPORTED) {
            rows += OemObservation(m, id, OemSource.ANDROID_STANDARD, scope(m), status = status, reason = reason)
        }
        fun read(m: OemMetric, source: OemSource = OemSource.ANDROID_STANDARD, quality: Quality = Quality.AUTHORITATIVE, block: () -> Any?) {
            try {
                val v = block()
                if (v == null || v is String && (v.isBlank() || v == Build.UNKNOWN)) missing(m, OemReason.MISSING_VALUE, OemStatus.UNAVAILABLE)
                else rows += OemObservation(m, id, source, scope(m),
                    number = when(v) { is Boolean -> if (v) 1.0 else 0.0; is Number -> v.toDouble(); else -> null },
                    text = if (v is String) v.take(256) else null, quality = quality)
            } catch (_: SecurityException) { missing(m, OemReason.PERMISSION, OemStatus.PERMISSION_DENIED) }
            catch (_: UnsupportedOperationException) { missing(m) }
            catch (_: Exception) { missing(m, OemReason.READ_FAILED, OemStatus.ERROR) }
        }
        when (category) {
            OemCategory.DEVICE -> {
                read(OemMetric.MANUFACTURER) { Build.MANUFACTURER }; read(OemMetric.MODEL) { Build.MODEL }
                read(OemMetric.PRODUCT) { Build.PRODUCT }; read(OemMetric.HARDWARE) { Build.HARDWARE }
                read(OemMetric.FIRMWARE) { Build.VERSION.INCREMENTAL }; read(OemMetric.BUILD) { Build.DISPLAY }
                read(OemMetric.SECURITY_PATCH) { Build.VERSION.SECURITY_PATCH }; read(OemMetric.ANDROID_VERSION) { Build.VERSION.RELEASE }
                read(OemMetric.ANDROID_SDK) { Build.VERSION.SDK_INT }
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val owner = when { dpm.isDeviceOwnerApp(context.packageName) -> "DEVICE_OWNER"; dpm.isProfileOwnerApp(context.packageName) -> "PROFILE_OWNER"; else -> "NONE" }
                read(OemMetric.AGENT_OWNER, OemSource.ANDROID_ENTERPRISE) { owner }
                if (owner != "NONE" || (Build.VERSION.SDK_INT >= 30 && context.getSystemService(UserManager::class.java).isManagedProfile))
                    read(OemMetric.DEVICE_MANAGED, OemSource.ANDROID_ENTERPRISE) { true }
                else missing(OemMetric.DEVICE_MANAGED, OemReason.ROLE_REQUIRED, OemStatus.UNAVAILABLE)
                read(OemMetric.KSP_AVAILABLE) {
                    try { context.packageManager.getApplicationInfo("com.samsung.android.knox.kpu", 0); true }
                    catch (_: PackageManager.NameNotFoundException) { false }
                }
            }
            OemCategory.SECURITY, OemCategory.INVENTORY -> return ProviderBatch(emptyList())
            OemCategory.NETWORK -> {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val active = cm.activeNetwork?.let(cm::getNetworkCapabilities)
                val all = cm.allNetworks.mapNotNull(cm::getNetworkCapabilities)
                read(OemMetric.NETWORK_TYPE) {
                    when { active == null -> "NONE"; active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN";
                        active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"; active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR";
                        active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"; else -> "OTHER" }
                }
                read(OemMetric.CONNECTED) { active != null }
                read(OemMetric.WIFI_ENABLED) { context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }
                read(OemMetric.CELLULAR_AVAILABLE) { all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } }
                read(OemMetric.ETHERNET_AVAILABLE) { all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } }
                read(OemMetric.VPN_ACTIVE) { all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } }
                read(OemMetric.AIRPLANE_MODE) { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0 }
                read(OemMetric.AGENT_TX) { TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it >= 0 } }
                read(OemMetric.AGENT_RX) { TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0 } }
                read(OemMetric.PROXY_CONFIGURED) { cm.defaultProxy != null }
            }
            OemCategory.BATTERY -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                read(OemMetric.BATTERY_HEALTH) { when(battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    2 -> "GOOD"; 3 -> "OVERHEAT"; 4 -> "DEAD"; 5 -> "OVER_VOLTAGE"; 6 -> "FAILURE"; 7 -> "COLD"; else -> null } }
                read(OemMetric.BATTERY_STATUS) { when(battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    2 -> "CHARGING"; 3 -> "DISCHARGING"; 4 -> "NOT_CHARGING"; 5 -> "FULL"; else -> null } }
                read(OemMetric.BATTERY_VOLTAGE) { battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.div(1000.0) }
                val manager = context.getSystemService(BatteryManager::class.java)
                read(OemMetric.BATTERY_CURRENT) { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.div(1000.0) }
                read(OemMetric.BATTERY_CHARGE) { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it >= 0 }?.div(1000.0) }
                read(OemMetric.POWER_SAVE) { context.getSystemService(PowerManager::class.java).isPowerSaveMode }
            }
            OemCategory.RESOURCES -> {
                val generation = resetGeneration.get()
                val now = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
                val old = cpuBaseline?.takeIf { it.generation == generation }
                read(OemMetric.CPU_TIME) { cpu }
                if (old != null && now > old.elapsed && cpu >= old.cpu) {
                    read(OemMetric.CPU_DELTA, OemSource.DERIVED, Quality.DERIVED) { cpu - old.cpu }
                    read(OemMetric.CPU_ESTIMATE, OemSource.DERIVED, Quality.ESTIMATED) { 100.0 * (cpu - old.cpu) / (now - old.elapsed) }
                } else {
                    missing(OemMetric.CPU_DELTA, OemReason.BASELINE, OemStatus.UNAVAILABLE)
                    missing(OemMetric.CPU_ESTIMATE, OemReason.BASELINE, OemStatus.UNAVAILABLE)
                }
                if (generation == resetGeneration.get()) cpuBaseline = CpuBaseline(now, cpu, generation)
                read(OemMetric.MEMORY_PSS) { Debug.getPss() * 1024 }
                missing(OemMetric.MEMORY_RSS)
                read(OemMetric.JAVA_HEAP) { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
                read(OemMetric.NATIVE_HEAP) { Debug.getNativeHeapAllocatedSize() }
            }
            OemCategory.POLICY -> return ProviderBatch(emptyList())
        }
        return ProviderBatch(rows.filter { it.metric.active })
    }

    private fun scope(m: OemMetric) = standardScope(m)
}
