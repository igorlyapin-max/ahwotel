# Knox API matrix / Матрица Knox API

Inspected local SDK archives on 2026-11-18. A method in a stub is not proof of runtime availability. Compilation is pinned to 3.13/API 40; 3.14 is not a required device version. All runtime calls require the optional Samsung library. Android min/target remain 26/36.

| Archive | Four capacity methods in DeviceInventory | SHA-256 |
|---|---|---|
| Knox-SDK-3.11-(API-level-38).zip | 4/4 | 979f59a76753ccf991ea452a992617dd4be3bce1f59d5e5f667defd50b9315dc |
| Knox-SDK-3.12-(API-level-39).zip | 4/4 | 0142770869b0ce3cbc3ce4f287a45c583d9b9fd6380a57ff603532637339939b |
| Knox-SDK-3.13-(API-level-40).zip | 4/4 | 7d3994e76825a916195f5fff0deb4b56a0e9ece510960e159a2023c069f2ab40 |
| Knox-SDK-3.14-(API-level-41).zip | 4/4 | b0616db975eaa1bbbfec7ff8e5970f54b0f2f678d551a109e71b3046a7ecc508 |
| Knox-SDK-3.5-(API-level-31).zip | 4/4 | d2db3714932068a672bb92dca05195cc7c8404ba40dcdee15c7bf37b1025b55b |

| Metric suffix | Documented method | Unit/scope | API and rights |
|---|---|---|---|
| internal_total | getTotalCapacityInternal | bytes, device | Since Knox API 2; deprecated 35 |
| internal_available | getAvailableCapacityInternal | bytes, device | Since Knox API 2; deprecated 35 |
| external_total | getTotalCapacityExternal | bytes, external SD card | Since Knox API 2; deprecated 35 |
| external_available | getAvailableCapacityExternal | bytes, external SD card | Since Knox API 2; deprecated 35 |

Wires use device.storage.knox.*; default collection interval is daily (Device category), first observation at session start. These four method descriptions do not declare an individual permission/license requirement. Do not infer a grant: actual success, SecurityException and missing APIs are recorded independently. No broad inventory/app-management permission is requested speculatively. If device firmware requires an administrative role, that device remains PERMISSION_DENIED until its documented role can be provided safely; AHWOTel does not become Device Owner.

getApplicationCpuUsage/getApplicationMemoryUsage describe a specified application, not system CPU/RAM; they require KNOX_APP_MGMT and are excluded. getBatteryLevelColourItem controls/describes icon colouring, not battery health; excluded. No supported system CPU utilization, raw thermal sensor, wear/cycle counter or storage throughput getter was confirmed in this audit; those existing placeholders remain UNSUPPORTED/NO_OFFICIAL_API. Capacity is not I/O throughput or a Performance State input. Standard Android battery/CPU/RAM sources continue independently.

Public official contracts: [DeviceInventory](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/deviceinfo/DeviceInventory.html), [ApplicationPolicy](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/application/ApplicationPolicy.html), [SystemManager](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/custom/SystemManager.html), [API mapping](https://docs.samsungknox.com/dev/fundamentals/sdk-licenses/overview/knox-version-mapping/).

Physical Samsung SM-T225 / Android 14 / Knox API 37: local real-key activation callback SUCCESS, code 0, no additional permission grants; internal total/available AVAILABLE, external total/available UNAVAILABLE/MISSING_VALUE. Internal readings were available even before license activation. See [code39 validation](VALIDATION-knox-code39.md) for values and boundaries. SOTI delivery/roles and older broadcast behavior remain NOT VERIFIED.
