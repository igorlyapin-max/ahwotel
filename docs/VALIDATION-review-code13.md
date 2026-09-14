# Проверка исправлений review: code13

Дата: 2026-09-14. Исправлены шесть замечаний review после `beaf486`; добавлена проверка отмены выполняющейся HTTP-отправки.

## Изменения

- Supervisor внутри трёх контейнеров управляет Off/Basic/Verbose через атомарную политику. Срок проверяется при старте и по монотонным часам во время работы; нет host timer или Docker socket. Изменения диагностики не пересоздают deployment-конфигурацию.
- Узкая DataStore migration отключает OTLP для ранее принятого HTTPS-порта 0/65536, сохраняет остальные настройки и выводит EN/RU уведомление. Общего сброса повреждённых настроек нет.
- Grafana использует точный `ts_of_last_over_time` в `(начало, конец]`, разделяет source/scope. Справка CPU/Thermal состоит из восьми точных разделов; полный текст доступен через EN/RU help dashboards.

## Выполненные проверки

- Gradle: debug APK, acceptance APK, instrumentation APK, lintDebug — PASS.
- 14 JVM checks: EndpointPortMigrationTest (2), PersistenceTest (7), HttpPolicyTest (3), OtlpResponseTest (2) — PASS. Дополнительно свежие wire fixtures: LabWireFixtureTest (1) — PASS.
- Go: 3 теста срока, повреждённой политики и сохранения аргументов выполняются при Docker build — PASS.
- Python: 3 проверки generator/CLI и 2 проверки instrumentation guard — PASS.
- Locale gate: 1292 строки EN/RU; 22 base + 60 OEM × 8 разделов — PASS.
- Samsung SM-J260F API27: HttpLabAcceptanceTest — 3 теста PASS (63.308 s). Проверены UI permission/revocation, отмена начатого NO_RESPONSE запроса при продолжающемся локальном сборе, реальные RAM данные. Instrumentation использует только com.ahwotel.acceptance.
- Дополнительно на физическом Samsung: в остановленный acceptance-пакет записаны исходные настройки старого формата с портами 0/65536. Для обоих случаев подтверждены успешный и повторный запуск, отключённый OTLP, сохранность остальных настроек, EN/RU уведомления. Исходные настройки acceptance восстановлены; основной PID 2388 не менялся.
- Изолированный стенд: смена bind/ports/retention во время Verbose, Off ≤15 s после TTL, запуск с истёкшей политикой, авария дочернего процесса и Docker recovery, повреждённая политика, точные границы PromQL, раздельные Android/Knox — PASS.
- Четыре потока сериализаторов APK: 22 series, gzip, исходные timestamps, out-of-order, отсутствие подмены UNSUPPORTED нулём; постоянная очередь пережила остановку Prometheus и restart Collector — PASS.
- Chrome: CPU/Thermal EN/RU на 1440×1000 и 390×844, восемь разделов, видимость tooltip и конца справки, Escape и переход клавиатурой — PASS на изолированном и основном стенде.
- После первой штатной пакетной отправки (сохранённый интервал 300 s) Chrome подтвердил все EN/RU панели новой сессии Samsung, RAM/Battery/Self, таблицу доступности и отсутствие ошибок запросов/NaN. Более ранний запуск этой проверки до отправки пакета не считается PASS.

## Основной runtime

Стенд пересобран и пересоздан с сохранением volumes. Collector: 192.168.202.35:4318; Grafana: 192.168.202.35:3000; Prometheus: 127.0.0.1:9090; health: 127.0.0.1:13133. Все readiness — PASS. Все effective levels — Off. JSON stdout/stderr + явный Docker local logging overlay, non-root и cap_drop=ALL подтверждены. Custom CA: not-applicable.

| Сервис | Запущенный/выбранный image ID | Пользователь |
|---|---|---|
| collector | `sha256:e0edb0936112d3c47c187ef9cfed04deac996d1e0105815558dc198a65095db6` | `10001:10001` |
| prometheus | `sha256:5b84036b6e1768397915118535b1c3d3135f77ed078532ae3833a7bcc431f441` | `nobody` |
| grafana | `sha256:c4d887361961d9aa7713d7977427236e9f59a2cd2373a665795c911dbaac1d69` | `472` |

Производные образы имеют label code13 и pinned base image. Запущенный ID совпадает с локально выбранным. У контейнеров read-only policy mount и отдельный 1 MiB tmpfs для статуса. Registry publication не выполнялась.

## Обновление Samsung

До установки сбор остановлен через UI; затем Home, force-stop и подтверждение отсутствия PID. Согласованная копия прошла `PRAGMA integrity_check=ok`. После `adb install -r` проверены code13 и совпадение SHA-256 установленного/локального APK.

- APK: `artifacts/AHWOTel-00.00.00.02-code13-debug.apk`.
- SHA-256: `e826aa40371f4871d18133e4be9f220890b9bf71c70ed7381ede856aedd4a54c`.
- Сохранено: 21709 базовых замеров, 20293 записей Battery/Self, 2 завершённых сессии; все настройки и device ID совпадают до/после установки.
- Новая сессия: `2dac0db9-d9fa-43d8-853b-76d51e748706`; PID `2388`. После Home foreground service продолжил сбор; базовых замеров стало 21733.
- Точная RAM точка из Room (timestamp 1789377955579, value 288321536.0) найдена в Prometheus новой сессии — PASS.

## Доказательства

`artifacts/code13/`: `jvm/`, `http-samsung.txt`, `lab-regression.json`, `wire-smoke.json`, `help/result.json`, `main-help/result.json`, `runtime.json`, `samsung-upgrade/result.json`, `samsung-prometheus.json`. Снимки устройства и браузера сохранены рядом с результатами.

`port-migration-device/result.json` — физическая миграция двух портов; `main-grafana/grafana-browser.json` — графики реальной новой сессии. `samsung-slow-prometheus.json` подтверждает точное совпадение Room code13 и Prometheus: заряд 100% и температура 24.5°C при timestamp 1789377909485; PSS 43693056 bytes при timestamp 1789377969604. Настройки периодов сбора/отправки не менялись. Для сверки использован согласованный снимок `samsung-slow-1`; неудачные копии изменявшегося WAL не использовались.

Физический OEM остаётся выключен согласно сохранённым настройкам; разделение источников проверено wire/PromQL fixture. Полная перезагрузка рабочего компьютера не выполнялась: восстановление проверено остановкой и запуском всех контейнеров с сохранённой истёкшей политикой.
