# Local OEM telemetry MVP

Реализован согласованный локальный вариант `android_oem_extended_telemetry_knox_mvp_TZ.txt`: один APK, существующий мониторинг, дополнительные Android/Android Enterprise/Knox источники, локальная история, OEM-диагностика и экспорт. Backend, fleet dashboard, серверная compliance-оценка, provisioning и автоматическая активация Knox не входят в этот этап. SOTI использует APK и стандартный контракт managed configuration; успешность доставки политик из реального SOTI требует отдельного управляемого стенда.

## Использование

1. В **Settings → Extended telemetry / OEM** включить сбор, выбрать группы и профиль, нажать **Save**. По умолчанию OEM выключен, интерфейс English. Русский выбирается в Settings.
2. В **Diagnostics → Extended telemetry / OEM → Check availability** проверить доступ из процесса APK. Ручная проверка обновляет текущие значения и профиль доступности; история замеров не создаётся.
3. Нажать **Start monitoring** на главном экране. OEM работает только в этой сессии и следует общему переключателю сбора при выключенном экране. Остановка не ожидает завершения зависшего OEM API.
4. В OEM-разделе доступны группы значений, подробные подсказки, список приложений и изменения. **Show graph** показывает числовой показатель одного провайдера. **Period and export** задаёт сессию/период графиков и выгрузки.

Последние значения и инвентарный список сохраняют время замера: они могут относиться к завершённой сессии. Ручная проверка вне сессии не сохраняет новый инвентарный снимок. Список приложений показывает последний сохранённый снимок выбранного провайдера. Журнал UI ограничен последними 200 событиями; полный период доступен в JSON.

Графики имеют подписанные оси X/Y: время с часовым поясом и числа с единицами. Диапазоны времени от суток включают дату; короткие интервалы — секунды. Процентная шкала устройств — 0–100; CPU агента может превышать 100, поэтому имеет свободную шкалу. Для состояний используются целые уровни и ступени, для числовых данных — среднее и диапазон min/max. Пропуски и границы сегментов не соединяются. Нажатие выбирает замер; для TalkBack доступны действия перехода между замерами и текстовое описание осей.

## Источники и ограничения

`OemTelemetryProvider` отделяет платформенный код от планирования, хранения и экспорта. `AndroidStandardProvider` читает публичные Android API, в том числе доступные Android Enterprise сведения. `SamsungKnoxProvider` вызывает только allowlist публичных SDK getters. `KnoxCapabilityRegistry` фиксирует поддерживаемый минимум Android/Knox, permission и известное требование авторизации.

В manifest подключена необязательная shared library `com.samsung.android.knox.knoxsdk`. Системный JAR не копируется в APK; нет private API, `setAccessible`, собственного Binder-протокола, загрузки dex, shell-команд или root из приложения. Такой способ обнаружения следует [Android uses-library](https://developer.android.com/guide/topics/manifest/uses-library-element). Поддержка старого пространства имён Knox 2.x не запланирована. Минимум приложения — Android API 26; минимум Knox-адаптера — Knox API 24 / 3.0.

| Группа | Чтение | Ограничения |
|---|---|---|
| Устройство | Build, SDK, security patch, собственная роль owner, видимость KSP | Неизвестное управление другим DPC не превращается в «не управляется». Серийный номер проверяется на доступность и сразу отбрасывается. |
| Безопасность | Developer options, ADB setting, secure credential, encryption state | Нет универсальной compliance/integrity-оценки; неизвестное не считается безопасным или небезопасным. |
| Приложения | PackageManager: package, version/code, enabled, system; снимки и изменения | Только текущий пользователь. `managed` остаётся null без достоверной классификации. Для полного инвентарного списка локального enterprise APK явно заявлен QUERY_ALL_PACKAGES. Это не заявление о допустимости публикации в Google Play. |
| Сеть | App-visible default network, Wi-Fi, cellular/Ethernet/VPN presence, airplane/proxy presence | Нет SSID, MAC, подписчика, адреса прокси, VPN-секретов. UID bytes относятся к агенту и являются накопительными счётчиками. |
| Батарея | Health/status, voltage V, current mA, charge mAh, power saving | Ток относится к батарее устройства; это не расход агента. Android sentinel переводится в отсутствие значения. |
| Ресурсы | CPU time/delta/estimate процесса, PSS, Java/native heap | 100% CPU = одно ядро. PSS не RSS. Публичный RSS-reader в MVP отсутствует. Системная RAM/диск/thermal остаются в основных сборщиках. |
| Политики | Camera, USB, debugging, Bluetooth, Wi-Fi, installation, factory reset, VPN, owner password/lock requirements, external volume state | Разрешение настройки и разрешение использования различаются. Показатели разных провайдеров не сливаются. Политики только читаются. |

### Allowlist Samsung

Все перечисленные методы проверяются реальным вызовом из отдельного рабочего потока APK. Наличие библиотеки или Samsung в `Build.MANUFACTURER` не выдаёт права. `required_permission=null` означает отсутствие заявленного требования в используемом контракте getter, а не гарантированный доступ на каждой прошивке.

| Публичный API | Результат | Permission / ограничение |
|---|---|---|
| EnterpriseDeviceManager.getAPILevel | API и производная версия Knox | Версия сопоставляется по [таблице Samsung](https://docs.samsungknox.com/dev/knox-sdk/introduction/knox-version-mapping/). Незнакомое число не получает вымышленное название версии. |
| RestrictionPolicy.isCameraEnabled(false) | Камера | Текущий пользователь; без показа системного сообщения. |
| RestrictionPolicy.isUsbHostStorageAllowed | USB host storage | Global scope; отличается от Android DISALLOW_USB_FILE_TRANSFER. |
| RestrictionPolicy.isUsbDebuggingEnabled | Разрешение USB debugging | Global scope; deprecated API 35, отсутствие метода обрабатывается. |
| RestrictionPolicy.isBluetoothEnabled(false), isWiFiEnabled(false) | Разрешение Bluetooth/Wi-Fi | Текущий пользователь; это не факт соединения. |
| RestrictionPolicy.isFactoryResetAllowed, isVpnAllowed | Разрешение сброса/VPN | Global scope; никаких изменений политики. |
| ApplicationPolicy.getApplicationInstallationMode | Режим по умолчанию | ALLOW=1, DISALLOW=0; правила отдельных пакетов могут отличаться. |
| ApplicationPolicy.getInstalledApplicationsIDList | Пакеты | KNOX_APP_MGMT, signature; отсутствие разрешения не доказывает отсутствие лицензии. |
| DeviceInventory.isDeviceSecure | Secure credential | Deprecated API 30; отсутствующие методы не вызывают падение агента. |
| KnoxEnterpriseLicenseManager.getLicenseActivationInfo | Есть/нет записи активации этого APK | Только API 33+. RECORDED не подтверждает действительность лицензии. Для более старого API — UNKNOWN. Ключи не читаются/не экспортируются. |

Контракты getters: [RestrictionPolicy](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/restriction/RestrictionPolicy.html), [ApplicationPolicy](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/application/ApplicationPolicy.html), [DeviceInventory](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/deviceinfo/DeviceInventory.html), [KnoxEnterpriseLicenseManager](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/license/KnoxEnterpriseLicenseManager.html).

Документированный device-wide CPU/temperature getter Knox в этот MVP не включён: `OEM_SYSTEM_CPU` и `OEM_THERMAL` не получают числовых значений. `getApplicationCpuUsage(package)` не подменяет системный CPU. Существующая проверка `/proc/stat`, CPU wait и probe scheduling delay продолжают работать независимо.

## Планирование и хранение

Balanced: устройство/инвентарь 24 часа, безопасность/политики 15 минут, сеть 5 минут, батарея/ресурсы агента 60 секунд. Custom: устройство/инвентарь 3600–86400 секунд, остальные группы 15–86400. Первый сбор — при старте сессии; изменения сети, питания и пакетов помечают группу для обновления с ограничением 15 секунд. События слушаются только во время сессии. После паузы, разрыва времени или смены настроек расчёт CPU delta начинается с новой базы.

У каждого провайдера один рабочий поток на процесс, без очереди задач. Тайм-аут ожидания — 3 секунды. Неотменяемый OEM-вызов оставляет этот провайдер BUSY до фактического возврата; новые потоки не создаются. Основной сборщик и второй провайдер имеют независимое выполнение.

Room schema 3 добавляет `oem_observations`, `oem_profiles`, `oem_inventory`, `oem_events`. Миграция 2→3 не меняет прежние строки samples/sessions/outbox. TTL и общий лимит локального хранилища охватывают OEM-таблицы; по умолчанию 14 дней / 512 MiB. Ошибки и unsupported хранятся с null, не с нулём. Удаления пакетов определяются только между двумя полными снимками.

JSON `kind=ahwotel.oem`, schema 1 содержит profiles, observations, inventory, events. CSV содержит numeric/boolean показатели со временем, session/device ID, provider, source, scope, unit, status/reason/quality. UI/экспорт не читают содержимое приложений или произвольные app restrictions. Полные экспорты предназначены для выбранного пользователем места через Android Storage Access Framework.

При включённом существующем OTLP отправляются числовые значения `agent.oem.<metric.wire>` и `agent.oem.capability`, с исходным временем каждого замера и атрибутами source/scope/status/reason/quality/capability. Значение capability=1 обозначает запись о состоянии; unsupported никогда не создаёт нулевой физический замер. Тексты, инвентарные списки и изменения пакетов не отправляются OTLP. По умолчанию сеть/коллектор выключены.

## Managed configuration / SOTI

Описатель: `app/src/main/res/xml/app_restrictions.xml`, стандартный [Android managed configurations](https://developer.android.com/work/managed-configurations). Ключи накладываются на локальные настройки, блокируют соответствующие UI-поля и не затирают локальные значения. Удаление ключа возвращает локальную настройку. Один неправильный тип, значение или неизвестный ключ отклоняет всё обновление и сохраняет последний принятый набор.

| Ключ | Тип и допустимые значения |
|---|---|
| monitoring_enabled | boolean; false останавливает сессию и запрещает Start. true разрешает Start, но не запускает сбор автоматически. |
| oem_telemetry_enabled, knox_telemetry_enabled | boolean |
| sampling_profile | `balanced` / `custom`; пользовательские интервалы остаются локальными |
| upload_interval | integer 0–86400, задержка планирования отправки в секундах; не период фонового сбора |
| application_inventory_enabled, security_telemetry_enabled, network_telemetry_enabled | boolean, соответствующая группа |
| debug_mode | `off` / `basic` / `verbose`; Verbose истекает через 15 минут; повтор того же config не продлевает срок |
| backend_environment | Метка `[A-Za-z0-9._-]{1,64}`, только telemetry resource; не URL и не переключение endpoint |
| config_version | Метка `[A-Za-z0-9._-]{1,64}` |

SOTI/DPC управляет своим enrollment и правами. Обычный AHWOTel не объявляет себя Device Owner, не наследует права SOTI/KSP и не активирует лицензию. Лицензируемые/закрытые getters могут оставаться PERMISSION_DENIED даже на Samsung. Работа Fully Managed, выдача разрешений и фактическая доставка SOTI-конфигурации проверяются отдельно на соответствующем стенде.

## Диагностика и проверка

Существующий pipeline сохраняется: structured stdout/stderr, Android Logcat, опциональный ротируемый JSONL. Off по умолчанию, Basic и временный Verbose выбираются без изменения кода. OEM-события: `oem_probe_result`, `oem_capability_changed`, `oem_source_recovered`, `oem_fallback_<category>`, `oem_provider_<provider>_<state>`. Логи содержат только коды/метаданные API, не значения показателей, package inventory, raw exceptions, URL или лицензии.

Тесты: `ChartScaleTest`, `OemWorkerTest`, `OemContractsTest`, `MigrationTest`, OEM wire test в `TransportTest`; native `OemAcceptanceTest` проверяет реальный APK, паузу, экспорт, локализованную справку и отрисовку осей. Запуск instrumentation только через `scripts/instrument.sh`, в пакете `com.ahwotel.acceptance`. Основной `com.ahwotel` не очищается и не используется как test target.

При изменении подсказок обновить XML обеих локалей, выполнить `python3 scripts/update-oem-resources.py`, затем `python3 scripts/check-locales.py`. Статическая таблица ресурсов сохраняет все 60 OEM-подсказок и их локализации при resource shrinking.
