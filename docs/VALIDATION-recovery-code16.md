# Исправления review восстановления — code16

Дата: 2026-09-18. База Git: `721f81ebad8eb0339478af515f6f15cbb5bcb6d1`.
APK `00.00.00.03`, `versionCode=16`, Room 5. Изменения локальные, commit/push не выполнялся.

**Промежуточный результат:** четыре исправления реализованы, APK установлен на Samsung, стенд обновлён на прежних портах. Функциональные проверки, физические reboot/Stop-сценарии, доставка измерений и Grafana EN/RU прошли. Новый суточный прогон запущен и имеет статус **RUNNING**; полная приёмка устойчивости остаётся открытой до его завершения.

## Исправления

1. Stop и административный запрет сначала отменяют сбор и запрещают восстановление в памяти. Запись DataStore и Room выполняется независимо; отказ `resume_state` не препятствует остановке runtime. Ошибки идут через существующие structured logs и показываются на выбранном языке интерфейса.
2. Предохранитель DataStore `resume_blocked` атомарно записывается с настройками до Room-транзакции. Отсутствующий ключ означает запрет. Только успешный непрерывный Start или завершённое изменение режима может снять запрет; компенсация настроек его не снимает. Явная continuous-команда SOTI совместима с timed default. Общий legacy/fallback восстановления не добавлен.
3. Наблюдатель сравнивает `container_id`, `started_at`, `image_id`, `restarts` и `child_generation`. Замена контейнера тем же образом и перезапуск child process теперь фиксируются.
4. `runtime_status()` возвращает частично доступные сведения и конкретные безопасные коды ошибок. CLI печатает эти данные и завершает работу с ошибкой. Soak сохраняет проблемное наблюдение, продолжает независимые проверки и не превращает предыдущий сбой в PASS после восстановления.

При live-проверке дополнительно исправлен недостаточный лимит памяти Grafana: вместо фиксированных 384 MiB добавлен валидируемый `GRAFANA_MEMORY_LIMIT=1g`. Необходимость подтверждена настоящим cgroup OOM под dashboard-нагрузкой, а не предположением о расходе памяти.

При одновременном отказе DataStore и Room Stop прекращает текущий сбор, но подтвердить сохранение запрета после смерти процесса невозможно. EN/RU предупреждение явно сообщает это ограничение; после восстановления хранилища требуется новый успешный Start/Stop.

## Проверки

Артефакты относительно `artifacts/recovery-code16/`.

| Проверка | Результат / доказательство |
|---|---|
| JVM: ResumeGuard, Resume, Migration, Persistence, SessionTiming | 21 PASS; `jvm-results/`, `build.txt` |
| Debug/acceptance/test APK и Android Lint | PASS; 0 errors, 18 warnings, 1 hint; `final-build.txt` |
| Подпись debug APK | APK Signature Scheme v2 PASS; `apk-signature.txt` |
| EN/RU и справочник параметров | PASS: 1311 строк, 22 base + 60 OEM × 8 разделов; `locales.txt` |
| Python: lab, soak, instrumentation guard | 13 PASS; `python-tests.txt` |
| Go supervisor | 4 PASS при сборке образов; `lab-up.txt` |
| API35: ResumeFailure + Resume | 8 PASS; `android-api35.txt` |
| API35: SessionTiming + HTTP opt-in/revoke/real Collector | 10 PASS; `timing-http-api35.txt` |
| API35: точное совпадение RAM и timestamp Room → Prometheus | PASS; `api35-prometheus.json` |
| API35: реальные kill, Force stop/open, reboot, Stop/reboot/open | PASS; `lifecycle-api35/result.json`, `lifecycle-api35-run.txt` |
| Samsung API27: ResumeFailure, Resume, SessionTiming, HTTP/real Collector | 18 PASS; `android-samsung.txt`, `instrument-samsung.txt` |
| Samsung acceptance: точное совпадение RAM и timestamp Room → Prometheus | PASS; `samsung-acceptance-prometheus.json` |
| Samsung основной APK: reboot до открытия Activity, Stop/reboot/open, screen off | PASS; `samsung-upgrade/lifecycle.json`, `boot-service.txt`, screenshots |
| Samsung основной APK: точное совпадение RAM и timestamp Room → Prometheus | PASS; `samsung-main-prometheus.json` |
| Grafana EN/RU: RAM, battery, PSS, availability и pipeline финальной сессии | PASS; `main-grafana/grafana-browser.json`, screenshots, frames |
| Runtime: новые образы, users, capabilities, logging, Basic/Off | PASS; `runtime.json`, `runtime-check-final.txt` |
| Runtime: внутренний перезапуск обнаружен при неизменном контейнере | PASS: generation 3 → 4 → 5 и `unexpected_runtime_change`; `runtime.json` |
| Runtime: фактическое пересоздание с тем же образом и restart count 0 | PASS; `same-image-recreate-detection.json` |
| Grafana: новый лимит, пересоздание только одного сервиса, повторный browser-smoke | PASS; `grafana-memory-restart.json`, `grafana-1g.txt` |

JVM-проверка границ commit использует настоящий DataStore и Room rollback: восстанавливает новый экземпляр DataStore после отмены scope, без компенсации приложения. Это проверка протокола сохранения, не имитация успешной физической перезагрузки телефона.

В отдельном API35 lifecycle-сценарии выполнены настоящие перезагрузки эмулятора: foreground service возобновился до открытия Activity, Stop сохранился после следующего reboot/open. На эмуляторе повторялся ANR `com.android.systemui`; закрывался только подтверждённый системный диалог, screenshots сохранены. ANR AHWOTel не игнорировался. После сценария исходные настройки acceptance-пакета восстановлены.

## APK и Samsung

Готовый APK: `artifacts/AHWOTel-00.00.00.03-code16-debug.apk`.
SHA-256: `b2ec81cd77b65e5681c61ed422cdad88be6fcc646bceb712f0e2474922772a5f`.

Основной пакет `com.ahwotel` обновлён до code16; SHA-256 установленного APK совпал с локальным. Instrumentation использует только отдельный `com.ahwotel.acceptance`; данные основного приложения не очищались. Перед установкой текущая сессия остановлена через UI, после установки выполнен явный Start: `85a34df8-ba56-48b2-83b2-38d977e56d6f`.

Проверены SQLite integrity и равенство настроек до/после: сохранены все 6 сессий, 121221 samples, 144190 telemetry records; OEM observations — 0. Outbox уменьшилась 185 → 146 за счёт работающей выгрузки, её количество не является инвариантом обновления. Снимки и результат: `samsung-upgrade/before.json`, `after-install.json`, `result.json`.

Первоначально Samsung пропал из ADB при установке тестового APK; после восстановления USB весь набор из 18 native-тестов выполнен успешно. Сообщения `null root node` в helper относятся к временной недоступности UIAutomator после смены Activity; контрольные UI-состояния и снимки получены при повторном чтении.

Физические проверки основного code16:

1. Явный Start после обновления: `85a34df8-ba56-48b2-83b2-38d977e56d6f`.
2. Reboot: foreground service появился до открытия Activity, новая сессия `d87eaf25-69b6-4b39-a49e-3724b9257569`.
3. Stop → reboot → открытие: приложение осталось «Готов к сбору», активного сервиса нет.
4. Финальный Start: `34dbc650-7cc7-4da6-9e0b-4f3efd28e584`. Home и выключение экрана не остановили foreground service; измерения этой сессии показаны в Grafana.

После успешного шага 3 ADB прервал чтение архива с кодом 255. Незавершённый архив не использовался. Повторены только получение полного снимка и шаг 4; SQLite integrity проверена. Исходный сбой сохранён в `samsung-lifecycle-run.txt`, продолжение — `samsung-lifecycle-finish.txt`. Обе перезагрузки прошли до этого транспортного сбоя.

Точное совпадение основного APK: RAM `307720192` bytes в `1789716522460` ms, сессия после первого reboot; Prometheus хранит то же значение и исходное время `1789716522.46` seconds. Перед browser-проверкой отдельно подтверждены батарейные метрики и PSS финальной сессии: `slow-metrics-ready.json`.

## Стенд и суточная приёмка

Три образа `:code16` реально собраны и запущены локально, volumes и адреса сохранены. Это `unverified-local` build; CI/registry-поставка не заявляется. Диагностика сейчас Off; доступен Basic и ограниченный Verbose. Structured stdout/stderr доставляются в настроенный Docker local logging sink; все сервисы non-root с `cap_drop: ALL`.

`status-final.txt`: все сервисы ready, очередь `0/10000`, Docker restart count 0. Child generation Collector/Prometheus — 5, новой Grafana — 1. Лимиты памяти: Collector 256 MiB, Prometheus 2 GiB, Grafana 1 GiB. Это точечный снимок, не гарантия суточной устойчивости.

Во время восстановления WAL один проход runtime-проверки получил timeout Docker CLI. Доступные состояния и конкретные ошибки сохранены в `runtime-check.txt`; после загрузки повторный проход успешен. Неуспешный проход не выдаётся за чистое наблюдение.

Старый прогон `artifacts/recovery-code15/soak/result.json` завершён со статусом `INTERRUPTED` перед обновлением: 25 samples, 0 failed samples, 1594.7 секунды. Его время не переносится в новую приёмку.

Первый code16-прогон начат в 10:41:56 МСК, но после browser-smoke Grafana достигла 383.9/384 MiB. В 10:44:20 ядро завершило Grafana и Prometheus datasource plugin по cgroup OOM. Наблюдатель сохранил `grafana_child_unavailable`, `grafana_not_ready`, а после перезапуска — `unexpected_runtime_change`. Этот прогон остановлен и сохранён как **FAIL** в `soak-grafana-384m-failed/`: 5 samples, 3 failed samples; 24 часа не завершены. Подтверждение причины — `kernel-oom.txt`, исходные наблюдения — `samples.jsonl`.

После изменения лимита до 1 GiB пересоздана только Grafana, с тем же образом и прежними volumes; контейнеры Collector/Prometheus не изменились. Повторный EN/RU browser-smoke прошёл с той же сессией Samsung. На первом новом наблюдении Grafana использовала 570.8 MiB; `memory.events` показывает `max=0`, `oom=0`, `oom_kill=0` (`grafana-memory-events-final.txt`). Это подтверждает короткий повторный сценарий, но ещё не суточную устойчивость.

Новый полный 24-часовой прогон после исправления Grafana запущен **2026-09-18 10:50:57 МСК**. Минимальное время окончания — **2026-09-19 10:50:57 МСК**. Первые три наблюдения без ошибок, очередь 0; в третьем возраст RAM-замера около 8 секунд, Grafana использует 538.8 MiB, перезапусков нет. Статус **RUNNING**; неуспешное наблюдение предыдущего прогона не удалено и не засчитано в новую приёмку.

Текущее состояние: `artifacts/recovery-code16/soak/result.json`; наблюдения: `samples.jsonl`; PID и параметры: `runner.json`. Для приёмки необходим `state=PASS`, фактическое время не менее 24 часов и отсутствие ошибок. Компьютер со стендом и Wi-Fi Samsung должны оставаться доступными. Наблюдатель использует отдельный каталог, не смешивает code15/code16 и не перезапускает сервисы.
