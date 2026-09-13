package com.ahwotel.oem

enum class OemCategory(val seconds: Int) { DEVICE(86400), SECURITY(900), INVENTORY(86400), NETWORK(300), BATTERY(60), RESOURCES(60), POLICY(900) }
enum class OemStatus { AVAILABLE, UNAVAILABLE, UNSUPPORTED, PERMISSION_DENIED, NOT_CONFIGURED, LICENSE_REQUIRED, LICENSE_ERROR, POLICY_RESTRICTED, ERROR, DISABLED }
enum class ProviderState { UNAVAILABLE, AVAILABLE, NOT_CONFIGURED, PERMISSION_DENIED, LICENSE_REQUIRED, LICENSE_ERROR, API_NOT_SUPPORTED, POLICY_RESTRICTED, READY, DEGRADED, ERROR }
enum class OemSource { ANDROID_STANDARD, ANDROID_ENTERPRISE, OEM, DERIVED }
enum class OemScope { DEVICE, AGENT_PROCESS, AGENT_UID, CURRENT_USER, APP_STORAGE }
enum class Quality { AUTHORITATIVE, ESTIMATED, DERIVED, INCOMPLETE }
enum class OemReason { NONE, DISABLED, LIBRARY_MISSING, METHOD_MISSING, API_LEVEL, PERMISSION, ROLE_REQUIRED, LICENSE_REQUIRED, LICENSE_ERROR, READ_FAILED, TIMEOUT, BUSY, MISSING_VALUE, BASELINE, COUNTER_RESET, PARTIAL_VISIBILITY, NO_OFFICIAL_API, POLICY, NOT_CHECKED }
enum class ValueKind { NUMBER, BOOLEAN, TEXT, STATE }

enum class OemMetric(val wire: String, val category: OemCategory, val unit: String = "1", val kind: ValueKind = ValueKind.TEXT) {
    MANUFACTURER("device.manufacturer", OemCategory.DEVICE),
    MODEL("device.model", OemCategory.DEVICE),
    PRODUCT("device.product", OemCategory.DEVICE),
    HARDWARE("device.hardware", OemCategory.DEVICE),
    FIRMWARE("device.firmware", OemCategory.DEVICE),
    BUILD("device.build", OemCategory.DEVICE),
    SECURITY_PATCH("device.security.patch", OemCategory.DEVICE),
    ANDROID_VERSION("device.android.version", OemCategory.DEVICE),
    ANDROID_SDK("device.android.sdk", OemCategory.DEVICE, kind = ValueKind.NUMBER),
    SERIAL_AVAILABLE("device.serial.available", OemCategory.DEVICE, kind = ValueKind.BOOLEAN),
    DEVICE_MANAGED("device.enterprise.managed", OemCategory.DEVICE, kind = ValueKind.BOOLEAN),
    AGENT_OWNER("agent.enterprise.owner", OemCategory.DEVICE, kind = ValueKind.STATE),
    KNOX_API("device.oem.api_level", OemCategory.DEVICE, kind = ValueKind.NUMBER),
    KNOX_VERSION("device.oem.platform_version", OemCategory.DEVICE),
    KSP_AVAILABLE("device.oem.ksp_available", OemCategory.DEVICE, kind = ValueKind.BOOLEAN),
    DEVELOPER_MODE("device.security.developer_mode", OemCategory.SECURITY, kind = ValueKind.BOOLEAN),
    USB_DEBUGGING("device.security.usb_debugging", OemCategory.SECURITY, kind = ValueKind.BOOLEAN),
    SCREEN_LOCK("device.security.screen_lock", OemCategory.SECURITY, kind = ValueKind.BOOLEAN),
    ENCRYPTION("device.security.encryption", OemCategory.SECURITY, kind = ValueKind.STATE),
    COMPLIANCE("device.security.compliance", OemCategory.SECURITY, kind = ValueKind.STATE),
    INTEGRITY("device.security.integrity", OemCategory.SECURITY, kind = ValueKind.STATE),
    PACKAGE_COUNT("device.applications.count", OemCategory.INVENTORY, "count", ValueKind.NUMBER),
    SYSTEM_PACKAGE_COUNT("device.applications.system_count", OemCategory.INVENTORY, "count", ValueKind.NUMBER),
    MANAGED_PACKAGE_COUNT("device.applications.managed_count", OemCategory.INVENTORY, "count", ValueKind.NUMBER),
    NETWORK_TYPE("device.network.type", OemCategory.NETWORK, kind = ValueKind.STATE),
    CONNECTED("device.network.connected", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    WIFI_ENABLED("device.network.wifi_enabled", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    CELLULAR_AVAILABLE("device.network.cellular_available", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    ETHERNET_AVAILABLE("device.network.ethernet_available", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    VPN_ACTIVE("device.network.vpn_active", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    AIRPLANE_MODE("device.network.airplane_mode", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    AGENT_TX("agent.network.tx_bytes", OemCategory.NETWORK, "By", ValueKind.NUMBER),
    AGENT_RX("agent.network.rx_bytes", OemCategory.NETWORK, "By", ValueKind.NUMBER),
    PROXY_CONFIGURED("device.network.proxy_configured", OemCategory.NETWORK, kind = ValueKind.BOOLEAN),
    BATTERY_HEALTH("device.battery.health", OemCategory.BATTERY, kind = ValueKind.STATE),
    BATTERY_STATUS("device.battery.status", OemCategory.BATTERY, kind = ValueKind.STATE),
    BATTERY_VOLTAGE("device.battery.voltage", OemCategory.BATTERY, "V", ValueKind.NUMBER),
    BATTERY_CURRENT("device.battery.current", OemCategory.BATTERY, "mA", ValueKind.NUMBER),
    BATTERY_CHARGE("device.battery.charge_counter", OemCategory.BATTERY, "mAh", ValueKind.NUMBER),
    POWER_SAVE("device.battery.power_save", OemCategory.BATTERY, kind = ValueKind.BOOLEAN),
    CPU_TIME("agent.cpu.time", OemCategory.RESOURCES, "ms", ValueKind.NUMBER),
    CPU_DELTA("agent.cpu.delta", OemCategory.RESOURCES, "ms", ValueKind.NUMBER),
    CPU_ESTIMATE("agent.cpu.percent_estimate", OemCategory.RESOURCES, "%", ValueKind.NUMBER),
    MEMORY_PSS("agent.memory.pss", OemCategory.RESOURCES, "By", ValueKind.NUMBER),
    MEMORY_RSS("agent.memory.rss", OemCategory.RESOURCES, "By", ValueKind.NUMBER),
    JAVA_HEAP("agent.memory.java_heap", OemCategory.RESOURCES, "By", ValueKind.NUMBER),
    NATIVE_HEAP("agent.memory.native_heap", OemCategory.RESOURCES, "By", ValueKind.NUMBER),
    OEM_SYSTEM_CPU("system.cpu.utilization", OemCategory.RESOURCES, "%", ValueKind.NUMBER),
    OEM_THERMAL("thermal.oem.sensor", OemCategory.RESOURCES, "Cel", ValueKind.NUMBER),
    CAMERA_POLICY("device.policy.camera", OemCategory.POLICY, kind = ValueKind.STATE),
    USB_POLICY("device.policy.usb_storage", OemCategory.POLICY, kind = ValueKind.STATE),
    DEBUGGING_POLICY("device.policy.debugging", OemCategory.POLICY, kind = ValueKind.STATE),
    BLUETOOTH_POLICY("device.policy.bluetooth", OemCategory.POLICY, kind = ValueKind.STATE),
    WIFI_POLICY("device.policy.wifi", OemCategory.POLICY, kind = ValueKind.STATE),
    INSTALLATION_POLICY("device.policy.app_installation", OemCategory.POLICY, kind = ValueKind.STATE),
    FACTORY_RESET_POLICY("device.policy.factory_reset", OemCategory.POLICY, kind = ValueKind.STATE),
    SCREEN_LOCK_POLICY("device.policy.screen_lock", OemCategory.POLICY, kind = ValueKind.STATE),
    PASSWORD_POLICY("device.policy.password", OemCategory.POLICY, kind = ValueKind.STATE),
    EXTERNAL_STORAGE("device.storage.external_state", OemCategory.POLICY, kind = ValueKind.STATE),
    VPN_POLICY("device.policy.vpn", OemCategory.POLICY, kind = ValueKind.STATE);
    val key get() = name.lowercase()
}

data class OemSettings(val enabled: Boolean = false, val knox: Boolean = true,
    val categories: Set<OemCategory> = OemCategory.entries.toSet(), val profile: String = "balanced",
    val intervals: Map<OemCategory, Int> = OemCategory.entries.associateWith { it.seconds }) {
    fun seconds(category: OemCategory) = if (profile == "balanced") category.seconds else intervals.getValue(category)
    fun valid() = profile in setOf("balanced", "custom") && intervals.keys == OemCategory.entries.toSet() &&
        intervals.all { (c, s) -> s in (if (c in setOf(OemCategory.DEVICE, OemCategory.INVENTORY)) 3600 else 15)..86400 }
}

data class OemObservation(val metric: OemMetric, val provider: String, val source: OemSource,
    val scope: OemScope, val number: Double? = null, val text: String? = null,
    val status: OemStatus = OemStatus.AVAILABLE, val reason: OemReason = OemReason.NONE,
    val quality: Quality = Quality.AUTHORITATIVE, val time: Long = System.currentTimeMillis()) {
    init {
        require(provider in setOf("android_standard", "android_enterprise", "samsung_knox"))
        require(number == null || number.isFinite())
        require(if (status == OemStatus.AVAILABLE) (number != null) xor (text != null) else number == null && text == null)
    }
}

data class InventoryEntry(val packageName: String, val versionName: String?, val versionCode: Long?,
    val enabled: Boolean?, val system: Boolean?, val managed: Boolean? = null)
data class InventorySnapshot(val provider: String, val entries: List<InventoryEntry>, val complete: Boolean,
    val time: Long = System.currentTimeMillis(), val status: OemStatus = OemStatus.AVAILABLE)
data class ProviderBatch(val observations: List<OemObservation>, val inventory: InventorySnapshot? = null)

interface OemTelemetryProvider {
    val id: String
    fun collect(category: OemCategory): ProviderBatch
    fun reset() {}
}

data class OemProviderProfile(val provider: String, val state: ProviderState, val checkedAt: Long,
    val capabilities: Map<OemCategory, ProviderState>, val license: String = "UNKNOWN")

fun standardScope(m: OemMetric): OemScope = when {
    m in setOf(OemMetric.AGENT_TX, OemMetric.AGENT_RX, OemMetric.NETWORK_TYPE, OemMetric.CONNECTED) -> OemScope.AGENT_UID
    m.category == OemCategory.RESOURCES && m !in setOf(OemMetric.OEM_SYSTEM_CPU, OemMetric.OEM_THERMAL) -> OemScope.AGENT_PROCESS
    m.category in setOf(OemCategory.POLICY, OemCategory.INVENTORY) || m in setOf(OemMetric.AGENT_OWNER, OemMetric.DEVICE_MANAGED,
        OemMetric.KSP_AVAILABLE, OemMetric.SCREEN_LOCK, OemMetric.CELLULAR_AVAILABLE, OemMetric.ETHERNET_AVAILABLE,
        OemMetric.VPN_ACTIVE, OemMetric.PROXY_CONFIGURED) -> OemScope.CURRENT_USER
    else -> OemScope.DEVICE
}

fun providerState(statuses: Collection<OemStatus>): ProviderState = when {
    statuses.isEmpty() -> ProviderState.NOT_CONFIGURED
    statuses.all { it == OemStatus.DISABLED } -> ProviderState.NOT_CONFIGURED
    statuses.all { it == OemStatus.AVAILABLE } -> ProviderState.READY
    statuses.any { it == OemStatus.AVAILABLE } -> ProviderState.DEGRADED
    statuses.any { it == OemStatus.ERROR } -> ProviderState.ERROR
    statuses.any { it == OemStatus.LICENSE_ERROR } -> ProviderState.LICENSE_ERROR
    statuses.any { it == OemStatus.LICENSE_REQUIRED } -> ProviderState.LICENSE_REQUIRED
    statuses.any { it == OemStatus.PERMISSION_DENIED } -> ProviderState.PERMISSION_DENIED
    statuses.any { it == OemStatus.POLICY_RESTRICTED } -> ProviderState.POLICY_RESTRICTED
    statuses.any { it == OemStatus.NOT_CONFIGURED } -> ProviderState.NOT_CONFIGURED
    statuses.any { it == OemStatus.UNSUPPORTED } -> ProviderState.API_NOT_SUPPORTED
    else -> ProviderState.UNAVAILABLE
}
