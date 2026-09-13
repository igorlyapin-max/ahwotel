# Проверка локального OEM MVP — versionCode 6

Проверено 2026-09-13. Это локальная сборка после Git-версии `00.00.00.01`, без нового commit/tag/push. Контракт реализации: [oem-telemetry.md](oem-telemetry.md).

## APK и установка

| Поле | Подтверждённое значение |
|---|---|
| APK | `artifacts/AHWOTel-00.00.00.01-code6-debug.apk` |
| SHA-256 APK | `d12e8bcea51451221ab801b5f592a3a2c6596be1fcb421ebf93136174cc85559` |
| Package / versionName / versionCode | `com.ahwotel` / `00.00.00.01` / `6` |
| Room | `3` |
| SHA-256 сертификата | `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553` |
| Телефон | Samsung SM-J260F, Android 8.1.0 / API 27 |
| Установка | `adb install -r`: Success; обновление прежнего versionCode 5 |
| Запуск | `am start -W -n com.ahwotel/.MainActivity`: Status ok |

Сертификат совпадает с прежним установленным APK. Старый артефакт `AHWOTel-00.00.00.01-debug.apk` сохранён отдельно. APK и сырые результаты находятся в игнорируемом Git каталоге `artifacts/`.

После установки и первого запуска, до изменения любых настроек, выполнено сравнение полного содержимого таблиц и файла DataStore:

| Данные | До | После | Сравнение |
|---|---:|---:|---|
| Схема Room | 2 | 3 | Миграция выполнена |
| Сессии | 29 | 29 | Все строки идентичны |
| Замеры | 547 | 547 | Все строки идентичны |
| Очередь OTLP | 0 | 0 | Без изменений |
| Настройки | — | — | Побайтное совпадение |

Созданы `oem_observations`, `oem_profiles`, `oem_inventory`, `oem_events`. Доказательство: `artifacts/post-oem-upgrade-summary.json`, архивы `pre-oem-install.tar` и `post-oem-upgrade.tar`. Сохранение непустой очереди дополнительно проверено в `MigrationTest` с заполненной базой schema 2.

## Knox непосредственно из основного APK

В основном приложении через интерфейс временно включены OEM и Basic, затем выполнено **Диагностика → Расширенная телеметрия / OEM → Проверить доступность**. Сессия мониторинга не запускалась.

- Package Manager подтвердил подключение optional library `com.samsung.android.knox.knoxsdk` к `/system/framework/knoxsdk.jar`.
- Класс `com.samsung.android.knox.EnterpriseDeviceManager` доступен, но публичный `getAPILevel()` отсутствует. Статус провайдера: `API_NOT_SUPPORTED`; результаты Knox: `UNSUPPORTED / METHOD_MISSING`, без числовых значений. Состояние лицензии: `UNKNOWN`.
- Android-провайдер: `DEGRADED`; группы сети и батареи `READY`, остальные частично доступны. Недоступные права и методы не заменяются нулями.
- В основном APK зарегистрированы 71 событие `oem_probe_result`, результаты провайдеров и fallback для DEVICE, SECURITY, INVENTORY, RESOURCES, POLICY.
- Доставка одних и тех же structured-событий подтверждена в `System.out`, теге `AHWOTel` Android Logcat и ротируемом JSONL. Для проверенного процесса отсутствует `FATAL EXCEPTION`.
- Проверка сохранила профиль доступности; число сессий/замеров осталось 29/547, OEM-история, инвентарные снимки и события изменений остались пустыми.

Артефакты: `oem-main-discovery.tar`, `oem-main-logcat.txt`, `oem-main-installed-package.txt`, [экран доступности](../artifacts/oem-main-phone-discovery.png).

После проверки OEM и диагностика возвращены в Off. Все прежние ключи настроек сохранили свои значения, включая русский язык, retention 14 дней, 512 MiB и выключенный OTLP. Итоговое сравнение прежних строк снова прошло. Временная настройка удержания экрана при USB восстановлена. Приложение открыто на главном экране. Доказательства: `oem-main-final-summary.json`, `oem-main-final.png`.

Часы тестового телефона показывают 2018-01-02; timestamps экспорта и экранов отражают время устройства. В ходе проверки системные часы не менялись.

## Сценарные проверки

Инструментальные тесты работают только в `com.ahwotel.acceptance`; runner перед установкой проверяет оба manifest и `targetPackage`. Пользовательский `com.ahwotel` не используется как test target и не очищается.

| Сценарий | Среда | Результат |
|---|---|---|
| Реальное обнаружение Android/Knox из worker APK, null при отказах, отсутствие истории вне сессии | Samsung API 27 | PASS |
| Сбор вместе с основными метриками, JSON/CSV, пауза screen-off, новый CPU baseline, быстрая остановка без поздних записей | Samsung API 27 | PASS |
| Английская/русская подробная справка без сбора, прокрутка, доступное закрытие, сохранение после recreation | Samsung API 27 и эмулятор API 35 | PASS |
| Числа и единицы Y, время и timezone X, пригодный размер графика, пиксели подписей, accessibility-выбор замера | Samsung API 27 и эмулятор API 35, шрифт 130% | PASS |
| OEM выключен по умолчанию; managed overlay останавливает сбор, запрещает Start и блокирует управляемый переключатель | Samsung API 27 | PASS |

На телефоне пройдено пять уникальных сценариев: четыре в `oem-phone-final.txt`, сценарий managed overlay — в `oem-phone-managed-final.txt` после исправления неоднозначного селектора теста. В первом файле сохранён исходный отказ селектора; результат повторной целевой проверки — `OK (1 test)`. Эмулятор: `oem-emulator-final.txt`, `OK (2 tests)`. Условия сценариев не ослаблялись.

В тестовой сессии получены 235 OEM-наблюдений: 155 AVAILABLE, 57 UNSUPPORTED, 3 PERMISSION_DENIED, 20 UNAVAILABLE; доступны 48 различных показателей. Три полных Android-инвентарных снимка по 157 пакетов текущего пользователя. Событий изменений нет: состав окружения между снимками не менялся. Эти значения относятся к изолированному acceptance-пакету и конкретной тестовой сессии.

Визуально проверены [оси на Samsung](../artifacts/axes-phone-en.png), [оси при шрифте 130%](../artifacts/axes-emulator-130.png), [русская справка при шрифте 130%](../artifacts/oem-help-ru-130.png). Числовой ряд соединён внутри сегмента, подписи осей читаются и не перекрываются. Эмулятор после проверки остановлен, масштаб шрифта восстановлен.

## Проверки на компьютере

- Сборка debug, acceptance и acceptanceAndroidTest — PASS (`oem-axis-build.txt`, `oem-acceptance-rebuild.txt`).
- Android Lint — PASS (`oem-lint-final.txt`); Lint выполнен отдельным вызовом после сборки, чтобы не читать одновременно перегенерируемые kapt stubs.
- 28 целевых JVM/Robolectric тестов — PASS: ChartScale 3, Commands 2, Migration 2, OemContracts 7, OemWorker 2, Persistence 7, Transport 5 (`oem-host-final.txt`, `oem-host-results.json`).
- Локализация: 1063 пары EN/RU, совпадение placeholders; 22 основных и 60 OEM-показателей × 8 разделов справки на каждом языке — PASS.
- `python3 scripts/test_instrument.py`: 2 проверки защиты instrumentation — PASS.
- `git diff --check` — PASS.

Команды для повторения целевых проверок:

```bash
./scripts/gradle.sh :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest --offline
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.ChartScaleTest --tests com.ahwotel.OemWorkerTest --tests com.ahwotel.OemContractsTest --tests com.ahwotel.MigrationTest --tests com.ahwotel.TransportTest --tests com.ahwotel.PersistenceTest --tests com.ahwotel.CommandsTest --offline
./scripts/gradle.sh :app:lintDebug --offline
python3 scripts/check-locales.py
python3 scripts/test_instrument.py
./scripts/instrument.sh <serial> artifacts/oem-acceptance.txt com.ahwotel.OemAcceptanceTest
```

## Границы подтверждения

Лицензированный Knox, Fully Managed и фактическая доставка конфигурации из SOTI не проверены: согласованный стенд — обычный тестовый Samsung. Тест managed configuration доказывает применение Bundle внутри приложения, а не SOTI enrollment или выдачу прав. На SM-J260F успешное чтение расширенных Knox getters не подтверждено из-за отсутствия требуемого публичного API. Системный CPU через Knox остаётся UNSUPPORTED; CPU процесса агента его не подменяет. Backend и fleet-функции в этот этап не входят.
