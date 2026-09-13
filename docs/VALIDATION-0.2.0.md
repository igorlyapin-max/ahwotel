# Проверка AHWOTel 0.2.0

Дата: 2026-09-13. Package `com.ahwotel`, versionCode 2, versionName 0.2.0, debug signing тем же ключом, что у 0.1.0.

APK: `artifacts/AHWOTel-0.2.0-debug.apk`, 13 999 363 bytes. SHA-256: `e9538d26324f6166495fc5630bf93a957400a68c76b13696f81e1f0e0d675243`.

SHA-256 установленного `base.apk` на телефоне и эмуляторе совпал с артефактом. После финальных тестов APK на телефоне перезапущен, служба мониторинга отсутствует. Контрольная копия базы подтвердила сохранение всех 150 замеров пятиминутной сессии, включая 149 значений каждого косвенного показателя (`artifacts/v0.2.0/420096dadcada677/final-history-check.json`). Вспомогательный пакет `com.ahwotel.test` удалён с телефона; тестовый эмулятор завершён.

## Результаты сборки и тестов

- `:app:testDebugUnitTest`: 31/31 passed (Core 10, Persistence 7, Transport 4, Commands 2, CPU sources 5, Source registry 2, Migration 1).
- `:app:lintDebug`: 0 errors, 30 warnings, 1 hint; baseline и отключение правил не использовались.
- `:app:assembleDebug :app:assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- `scripts/check-locales.py`: 141 EN/RU строки, одинаковые ключи и placeholders.
- Эмулятор Android 15 / API 35: 9/9 instrumented scenarios, 41.257 s.
- Samsung SM-J260F / Android 8.1 / API 27: 9/9 instrumented scenarios, 59.918 s; протокол `artifacts/v0.2.0/physical-instrumentation.txt`.

Для lint использован отдельный процесс Gradle с `--no-daemon --max-workers=2` после внутреннего сбоя Kotlin FIR analysis. Проверка API обнаружила недоступные старым Android символы; итоговый код использует `Os.open/read/close` и API guard для `O_CLOEXEC`.

Сценарии покрывают язык, pause/resume, команды, Off/Basic/Verbose и stdout/stderr/Logcat/JSONL; проверку доступности из APK без записи истории; выключение косвенных проб; постоянный TID и паузу потока пробы; новые графики/экспорт и ручной recheck без перезапуска сессии. UI-тест CPU-графиков удерживает экран включённым только флагом тестируемой Activity, без изменения системного screen timeout.

Локальные тесты дополнительно проверяют отказ/кэш/30-second retry `/proc`, разбор `schedstat`, нулевое ожидание, сброс счётчиков, пропущенные сроки, разделение sleep и delay, stale values, отсутствие влияния косвенных показателей на Performance State, отсутствие повторного логирования отказов и `source_recovered` после recheck → warmup → available. Проверка protobuf читает timestamp именно у каждого gauge: probe metrics имеют время пробы, обычные метрики — время основного замера.

## Установка поверх 0.1.0

До установки сохранены остановленные SQLite snapshots; после установки и запуска сравнены записи по id/sessionId/time:

| Устройство | Room | Замеры до / после | Сессии до / после | Очередь |
| --- | --- | --- | --- | --- |
| SM-J260F | 1 → 2 | 176 / 176 | 9 / 9 | 0 / 0 |
| Emulator API 35 | 1 → 2 | 68 / 68 | 21 / 21 | 0 / 0 |

Все старые записи сохранены; новые probe-поля старых строк null. Отдельный тест миграции исходной schema v1 подтверждает также сохранение непустого outbox с исходными protobuf bytes. Snapshots находятся в `artifacts/v0.2.0/<serial>/before` и `after`.

## Доступность из рабочего кода APK

Результат одинаков на проверенных устройствах:

| Показатель | Источник | Результат |
| --- | --- | --- |
| System CPU | `/proc/stat` | UNSUPPORTED / permission_denied |
| CPU wait | `/proc/self/task/<tid>/schedstat` | AVAILABLE / probe_thread |
| Probe scheduling delay | uptime-based timer | AVAILABLE / probe_thread |

Это результат чтения внутри APK, а не вывода `run-as`. Instrumented test отдельно подтвердил постоянный TID, отличный от тестового потока, и фактический путь чтения. Полные отчёты 22 параметров: `artifacts/v0.2.0/<serial>/cpu-source-check.json`.

На SM-J260F память, хранилище и батарея доступны; thermal/headroom — `UNSUPPORTED / api_unavailable`. Косвенные значения не записываются в `device.cpu.utilization` или `performance.pressure.cpu`.

## Пятиминутная физическая сессия

Сессия `5f8304c0-5056-4f73-a008-3d6b8ae6fc04`, duration 300 s, interval 2 s, indirect CPU ON, screen-off collection ON, OTLP/SOTI OFF:

- `FINISHED / timeout`, фактическая длительность 300.100 s;
- 150 замеров: 131 screen-off, 19 screen-on;
- 149 значений ожидания и задержки; первая проба — baseline/WARMING_UP;
- ожидание CPU: 0–1.359463 ms, среднее 0.015565 ms за измеренный интервал;
- задержка запуска: 1–49 ms, среднее 2.657718 ms;
- System CPU: нет значений (`count(cpu)=0`); все значения `null`;
- 25 событий определения/изменения доступности: один отказ `/proc/stat`, прогрев и доступность проб, один `fallback_selected=thread_schedstat`; одинаковый отказ не повторяется на каждом замере;
- после timeout служба отсутствует, после force-stop/запуска история сохранена и мониторинг OFF.

Сводка, база и журналы: `artifacts/v0.2.0/physical-smoke/summary.json`, `monitor.db`, `events.jsonl`. Этот длительный прогон выполнен до завершающего изменения только журнала восстановления источников; измерительный код не менялся, финальный APK повторно прошёл instrumented scenarios на обоих устройствах.

На физическом экране проверены [CPU wait полной сессии](../artifacts/v0.2.0/physical-smoke/cpu-wait-visible.png), [график задержки](../artifacts/v0.2.0/420096dadcada677/probe-delay-chart.png), [русский интерфейс](../artifacts/v0.2.0/420096dadcada677/acceptance-ru.png). График CPU wait имеет полностью видимую область 426 × 240 px и выбранную точку. На эмуляторе дополнительно сохранён [видимый CPU wait](../artifacts/v0.2.0/emulator-5554/cpu-wait-visible.png).

## Нагрузка и ограничения

- CPU процесса: около 4.64% одного ядра за 70.072 s, 325 ticks, подтверждённый `AT_CLKTCK=100`.
- PSS с интерфейсом: 43503 KiB ≈ 42.5 MiB; при выключенном экране: 36814 KiB ≈ 36.0 MiB.
- SQLite snapshot после сессии: 393216 bytes; включает предыдущую историю и тестовые сессии, не является размером только 150 новых строк.
- Это отдельное измерение, не контролируемое A/B-сравнение с 0.1.0. Battery impact не измерялся: питание USB, заряд 100%.
- Часы телефона показывают 2018-01-02. История и экспорт отражают часы устройства; они не исправлялись. Планирование пробы использует uptime, таймер сессии — elapsed realtime.
- На эмуляторе возникало окно `System UI isn't responding`; видимость графика отдельно проверена после кнопки Wait. Это ограничение тестовой среды; снимок с перекрывающим диалогом не использован как доказательство видимого графика.
- Root/ADB не нужны для работы APK. Реальные SOTI enrollment, корпоративный OTLP ingest, другие OEM и длительный Doze не проверялись в этой итерации. Retention ограничивается одновременно возрастом и размером; 14 дней не гарантируются при исчерпании выбранной квоты.

Git commit/push не выполнялись. Артефакты и SDK остаются в игнорируемых каталогах.
