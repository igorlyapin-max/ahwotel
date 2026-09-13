# Проверка MVP 0.1.0

Дата проверки: 2026-09-13. Проверены сборка, локальное хранение, UI и foreground service на физическом телефоне и эмуляторе. Это автономный MVP с выключенными по умолчанию OTLP и SOTI.

## Артефакт

- `artifacts/AHWOTel-0.1.0-debug.apk`, 13 924 120 bytes, package `com.ahwotel`, versionCode 1, versionName 0.1.0.
- SHA-256: `7d8c3a6cf30cc070f14f2f049ef27711ed04472a161a6cfad2a5c2ecc2d5624e`.
- APK подписан локальным debug key. SHA-256 установленного на SM-J260F `base.apk` совпадает с артефактом.
- Артефакты и SDK находятся в игнорируемых каталогах; в Git не опубликованы. Origin настроен на `https://github.com/igorlyapin-max/ahwotel.git`; существующая GPLv3 LICENSE сохранена.

## Автоматические проверки

| Проверка | Результат |
| --- | --- |
| `:app:testDebugUnitTest` | 21/21 passed: Core 10, Persistence 6, Transport 3, Commands 2 |
| `:app:lintDebug` | 0 errors, 30 warnings, 1 hint |
| `:app:assembleDebug :app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `python3 scripts/check-locales.py` | 122 EN/RU strings, ключи и placeholders совпадают |
| Instrumentation на Samsung SM-J260F, Android 8.1 / API 27 / ARMv7 | 5/5 passed, 34.28 s |
| Instrumentation на эмуляторе Android 15 / API 35 / x86_64 | 5/5 passed, 54.181 s |

Пять instrumented scenarios: смена языка при активной сессии и recreation Activity; отсутствие новых замеров во время screen-off pause и возобновление; START/STOP, идемпотентность и конфликты explicit broadcasts; Basic/Verbose expiry и file/Logcat/stdout/stderr; timeout и экспорт реальных строк в JSON/CSV. Тесты восстанавливают настройки по умолчанию, не удаляя историю.

Room-тесты проверяют удаление по возрасту, сохранение gaps/null и пиков, перевод RUNNING в INTERRUPTED, байты и timestamps очереди. Набор из 604800 замеров (14 дней с интервалом 2 секунды) агрегирован SQL в не более 241 корзины с сохранением пика. Transport-тесты используют локальный TLS server: HTTPS/protobuf, исходное время замеров, отсутствие Authorization, отказ недоверенному сертификату. Это не проверка корпоративного collector.

Lint warnings включают доступность обновлений зависимостей, unused resources, особенности locale/backup attributes и экспортируемый receiver без авторизации, предусмотренный согласованным MVP. Предупреждения не скрыты через baseline. Полные результаты: `app/build/reports/` и `artifacts/*-instrumentation.txt`.

## Физический пятиминутный smoke

Телефон: Samsung SM-J260F, Android 8.1 / API 27, доступная ОС память 900724 KiB. Первый запуск показал English при русском языке системы. Запуск выполнен обычной кнопкой Start monitoring, duration 300 s, interval 2 s, screen-off collection включён, OTLP/SOTI выключены.

Сессия `f170ae5d-d058-4e91-b66c-59dbda80a153`:

- длительность по записи session: 300.091 s;
- результат `FINISHED`, `endReason=timeout`;
- 150 исходных замеров: 15 screen-on, 135 screen-off;
- Memory, Storage, Battery доступны; CPU, Thermal, Headroom — UNSUPPORTED;
- CPU/thermal записаны как null; значения не подменяются нулями или CPU процесса;
- доступная RAM: 330489856–341835776 bytes;
- экспортная очередь пуста; история сохранилась после force-stop и нового запуска; сбор сам не возобновился.

На реальном экране проверены [English startup](../artifacts/first-launch-en.png), [active session](../artifacts/monitoring-en.png), [русские настройки](../artifacts/settings-ru.png), [RAM graph с выбранной точкой](../artifacts/history-en.png). У графика подтверждена видимая область 426 × 240 px; это реальные данные пятиминутной сессии. SQL snapshot и сводка: `artifacts/device-db/monitor.db`, `artifacts/sm-j260f-session-summary.json`.

**Часы телефона показывали 2018-01-02.** Исходные timestamps и подписи графика отражают часы устройства. Системное время не изменялось. После исправления часов такие старые записи могут быть удалены обычной политикой retention; копия проверенной сессии сохранена в локальных артефактах. Таймер сессии использует monotonic clock.

## Нагрузка на SM-J260F

- PSS с открытым интерфейсом: 48750 KiB (около 47.6 MiB).
- PSS во время фонового сбора с выключенным экраном: 41657 KiB (около 40.7 MiB).
- CPU процесса: около 3.5% одного ядра на отдельном окне около 70 s; 243 ticks при подтверждённом `AT_CLKTCK=100`. Это оценка overhead агента, не system-wide CPU и не гарантия для всего парка.
- SQLite snapshot после 150 замеров: 98304 bytes, включая schema/index/session overhead; это не линейная оценка размера двух недель.
- Battery impact количественно не подтверждён: устройство было подключено по USB, заряжалось и показывало 100%. Отдельного измерения сетевого трафика не было.

Raw evidence: `artifacts/sm-j260f-memory-*.txt`, `artifacts/sm-j260f-cpu-t*.txt`, `artifacts/sm-j260f-log.jsonl`.

## Перезапуск и reboot

На изолированном эмуляторе русский язык и Basic сохранены через Settings; после force-stop/нового запуска показан русский интерфейс и событие `diagnostic_enabled` в stdout и Logcat. Затем начата сессия и выполнен настоящий `adb reboot` только эмулятора.

После reboot `MonitoringService` отсутствует в `dumpsys activity services`. При открытии приложения видна кнопка «Начать сбор»: мониторинг OFF. Сессия `6bcd8e8e-b453-40f6-a3e2-aa99cdc9f222` содержит 4 замера, имеет `status=INTERRUPTED`, `endReason=process_interrupted`. После проверки восстановлены English, diagnostic Off и monitoring Off.

Во время загрузки эмулятора появилось системное окно `System UI isn't responding`; проверка UI продолжена после кнопки Wait. Диалог относился к процессу System UI. Evidence: [OFF после reboot](../artifacts/emulator-reboot/off-after-reboot.png), `artifacts/emulator-reboot/summary.json`, `diagnostic-restart.txt` и SQLite snapshot в том же каталоге. Телефон пользователя не перезагружался.

## Диагностика и границы проверки

Basic и временный Verbose проверены на обоих устройствах через общий structured pipeline: stdout, stderr, Android Logcat и ротируемый JSONL. В Android поток stderr имеет Logcat priority WARN, при этом JSON `level=ERROR` и запись в `AHWOTel` имеет ERROR. Тест проверяет оба пути. Временный Verbose автоматически выключается; чувствительные строковые payload не передаются в pipeline.

Не подтверждены реальный SOTI enrollment/delivery, корпоративный OTLP ingest, другие модели OEM, расход батареи без USB и длительная работа в OEM Doze. mTLS/JWT/PKI, авторизация SOTI и production signing исключены из согласованного MVP. Переключатели интеграций по умолчанию выключены. Не проводилась физическая многодневная проверка retention или заполнение диска телефона до quota.
