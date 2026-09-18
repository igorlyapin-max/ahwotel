# Возобновление сбора и восстановление OTEL-стенда — code15

**Обновление статуса:** прогон code15 остановлен перед плановым обновлением code16. Итог `INTERRUPTED`, 25 наблюдений без ошибок, около 26.6 минуты. Суточная приёмка не пройдена; ниже сохранён исходный отчёт о запуске. Продолжение: [code16](VALIDATION-recovery-code16.md).

Дата: 2026-09-18. База: `721f81ebad8eb0339478af515f6f15cbb5bcb6d1`, `main`.
Версия APK: `00.00.00.03`, `versionCode=15`; Room 5. Изменения локальные, Git push не выполнялся.

**Промежуточный результат:** реализация, установка и перечисленные функциональные проверки завершены. Обязательное суточное наблюдение запущено, его результат пока **RUNNING**, не PASS. Полная приёмка устойчивости остаётся открытой до окончания этого наблюдения.

## Поведение приложения

- Независимые `resumeOnBoot` и `resumeOnOpen`, обе по умолчанию OFF. Выбор Continuous сам по себе сбор не запускает.
- Явный Start непрерывной сессии сохраняет разрешение восстановления и конфигурацию в Room. Stop из приложения, уведомления или SOTI снимает разрешение. Timed и административное отключение также запрещают восстановление.
- Reboot, открытие Activity и системное восстановление процесса проверяют разрешение и текущие настройки. Восстановление создаёт новый UUID; прежняя сессия остаётся INTERRUPTED. Параллельные запросы не создают несколько активных сессий.
- Запуск процесса для выгрузки WorkManager не запускает сбор. Системный Force stop не обходится; последующее явное открытие приложения может возобновить ранее разрешённый сбор.
- Миграция Room 4 → 5 сохраняет историю и outbox, но не выводит разрешение восстановления из старого `continuous=true`. Общий legacy/fallback-механизм не добавлен.
- Подсказки, настройки и результаты восстановления доступны на EN/RU. Debug остаётся выключенным по умолчанию; используются существующие Basic/временный Verbose и structured stdout/stderr, Logcat, JSONL.

## Стенд и сохранность данных

Перед обновлением сохранены база/настройки основного APK, TSDB Prometheus и дисковая очередь Collector. Контрольные суммы и проверенные резервные копии находятся в `artifacts/recovery-code15/backup/`. Основной APK не использовался как instrumentation target; тесты запускаются в `com.ahwotel.acceptance`.

Старый лимит Prometheus 768 MiB заменён на настраиваемый `PROMETHEUS_MEMORY_LIMIT`, по умолчанию 2g. При восстановлении обнаружена высокая кардинальность метки `segment`: около 5100 значений, более миллиона head series. Collector исключает `segment` из backend labels; локальные данные APK, timestamps, device/session/source/scope сохранены. Исторические ряды удаляются штатным retention; volumes не очищались, выгрузка истории вне существующей outbox не запускалась.

Supervisor сохраняет числовые exit code и signal дочернего процесса и пишет их в structured stderr. `status` проверяет readiness, память, restart count, image ID и заполнение очереди. `up --force-recreate` применяет также изменения bind-mounted конфигурации при неизменном image ID. Операционный log sink — явный Docker local logging overlay; все три сервиса работают non-root с `cap_drop: ALL`.

После рестарта Collector/Prometheus проверена дисковая очередь. На контрольном снимке `status-final.txt`: все сервисы ready, диагностика Off, очередь `0/10000`, restart count 0; Prometheus использует около 255 MiB при лимите 2 GiB. Это снимок, не результат суточного наблюдения.

Во время browser-smoke Grafana достигала 383.4 MiB при лимите 384 MiB; после закрытия браузера — 376.5 MiB. Проверка cgroup: `oom=0`, `oom_kill=0`; рестартов не было. Запас памяти Grafana под нагрузкой небольшой и остаётся предметом наблюдения, поэтому краткий успешный smoke не подтверждает суточную устойчивость.

Собраны и реально запущены три локальных образа `:code15`. Image IDs, users, caps и logging зафиксированы в `runtime.json`. Это **unverified-local** build; проверенная CI/registry-поставка этим этапом не заявляется.

## Проверки

| Проверка | Результат / доказательство |
|---|---|
| JVM: Resume, Migration, Persistence, SessionTiming, LabWireFixture | 18 тестов PASS; `build-checks.txt` |
| Debug/acceptance/test APK, финальные debug APK и Lint | PASS; `final-debug-build.txt`: 0 errors, 18 warnings, 1 hint |
| Полнота EN/RU | PASS: 1308 строк, 22 base + 60 OEM × 8 разделов справки |
| Python: lab CLI/queue/memory и instrumentation guard | 5 + 2 теста PASS |
| Go supervisor, включая exit 0/17/SIGKILL | 4 теста PASS при Docker build |
| Samsung API27: Resume + SessionTiming | 11 тестов PASS; `resume-samsung.txt` |
| Samsung API27: HTTP opt-in/revoke/cancel, реальный Collector | 3 теста PASS; `http-samsung.txt` |
| Emulator API35: Resume | 4 теста PASS; `resume-api35.txt` |
| Emulator: kill процесса, Force stop + открытие, reboot, Stop + reboot/open | PASS; `lifecycle-api35/result.json` |
| Emulator: POST_NOTIFICATIONS denied, foreground сбор и Stop | PASS; `notifications-api35/result.json` |
| Wire: timestamps, out-of-order, gzip, UNSUPPORTED, отсутствие segment | PASS, 22 series; `wire-smoke.json` |
| Collector queue при cold restart | PASS; `queue-restart.txt`, `durableQueueRestart=true` |
| Samsung acceptance: точный Room → Prometheus замер | PASS; `samsung-acceptance-prometheus.json` |
| Samsung основной APK: точный Room → Prometheus замер | PASS; `samsung-main-prometheus.json` |
| Grafana EN/RU: RAM, battery, self, availability и pipeline финальной сессии | PASS; `main-grafana/grafana-browser.json`, screenshots и data frames |

Все пути доказательств в таблице относительны к `artifacts/recovery-code15/`. На эмуляторе встречался ANR именно System UI: harness сохраняет его screenshot и закрывает только системный диалог; ANR AHWOTel не игнорируется. После восстановления окружения сценарии прошли. Частичные ADB-архивы не использовались как резервные копии: целостность проверена у полного архива и SQLite.

Первая browser-проверка началась до первого пакета медленных метрик и завершилась по timeout таблицы availability. После появления метрик повторный прогон EN/RU прошёл без ошибок запросов; исходный timeout сохранён в `grafana.txt`, успешный результат — в `grafana-final.txt`. Ожидание включает накопление окна и отдельный `uploadSeconds=300`; данные проверяются по времени измерения, а не доставки.

## Основной Samsung

На SM-J260F установлен финальный APK code15 с проверкой SHA-256:
`3d40c4f78a8ac04f81d61067729a0287b198b67b296cc66daf08d087dad18b15`.

При миграции сохранены все 3 прежние сессии, 120615 base samples, 136894 telemetry records. Остальные настройки сохранены; через UI включены только обе новые опции возобновления. Outbox уменьшалась за счёт доставки; её количество не является инвариантом миграции.

Реальные проверки основного APK (`samsung-upgrade/lifecycle.json`):

1. Явный Start: `73a38e10-e09b-426a-a1f3-ab6d28c2a243`.
2. Физическая перезагрузка: foreground service появился **до открытия Activity**, новая сессия `3286a3d2-26bb-4aba-9c61-903039902588`. Предыдущая записана INTERRUPTED, причина `process_interrupted`.
3. Stop → повторная перезагрузка → открытие: сбора нет. Сессия записана FINISHED, причина `manual_stop`.
4. Финальный явный Start: `da50e396-e037-4a58-ac43-2be2cb3e5fbe`. После Home и выключения экрана foreground service продолжил работу; данные поступают в Prometheus.

Точное совпадение основного APK: RAM `301154304` bytes в `1789712772681` ms, сессия после первого reboot; Prometheus хранит то же значение и время `1789712772.681` seconds.

## Суточное наблюдение

Наблюдатель запущен 2026-09-18 **09:31:56 MSK**, минимальный срок окончания — 2026-09-19 **09:31:56 MSK**. Компьютер со стендом и Wi-Fi телефона должны оставаться доступными.

`scripts/otel_lab_soak.py` раз в минуту записывает memory usage/limit, image IDs, restart counts, очередь Collector, возраст последнего RAM-замера Samsung и доступность Grafana. Не перезапускает сервисы. Первое измерение: ошибок нет, очередь 0, возраст данных около 21 секунды. Изменение runtime, недоступность, устаревшие данные или рост очереди к завершению дают FAIL.

Текущее состояние: `artifacts/recovery-code15/soak/result.json`; отдельные наблюдения: `samples.jsonl`; PID: `runner.json`. Итоговая приёмка требует `state=PASS`, не менее 24 часов и отсутствия ошибок. Этот документ фиксирует запуск, а не обещание успешного завершения.
