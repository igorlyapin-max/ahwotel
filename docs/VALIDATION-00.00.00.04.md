# Git-поставка AHWOTel 00.00.00.04

Дата: 2026-09-18. Ветка `main`. База: `VERSION=00.00.00.03`, аннотированный тег `v00.00.00.03`, commit `721f81ebad8eb0339478af515f6f15cbb5bcb6d1`. Локальные и удалённые branch/tag совпали при preflight; recovery push не требуется. Новая версия — `00.00.00.04`, тег — `v00.00.00.04`.

## Состав

- Независимые опции возобновления непрерывного сбора после reboot и при открытии приложения, обе по умолчанию OFF. Явный Start разрешает восстановление, Stop запрещает; восстановление создаёт новый ID сессии. WorkManager для выгрузки не запускает сбор.
- Room 5 сохраняет намерение восстановления; предохранитель DataStore блокирует старое разрешение при незавершённом изменении настроек. Stop и административный запрет останавливают runtime даже при отказе записи в Room. Ошибки и ограничения сохранения запрета объясняются на EN/RU.
- Collector исключает высококардинальный `segment` из backend labels; Prometheus/Grafana получили настраиваемые лимиты памяти 2 GiB/1 GiB. Локальные данные, timestamps и session/source/scope сохраняются.
- Supervisor сохраняет exit code, signal и `child_generation`. Наблюдатель проверяет контейнеры, дочерние процессы, память, очередь, свежесть телеметрии и Grafana, сохраняя частичные данные при сбоях.
- Целевые регрессии, lifecycle helper и протоколы физических/сквозных проверок.

`VERSION` задаёт Android `versionName`; `versionCode=17`. От проверенного code16 APK отличается только метаданными версии. Room остаётся 5. Основной Samsung продолжает непрерывную сессию на `00.00.00.03/code16`: Git-передача не прерывает текущий суточный прогон. Результаты code16 не выдаются за установку или физическую проверку code17.

## Проверки

Подробная функциональная приёмка: [code15](VALIDATION-recovery-code15.md), [code16](VALIDATION-recovery-code16.md). На code16 прошли 21 JVM-, 36 Android-, 13 Python- и 4 Go-теста, Lint, физические reboot/Stop-сценарии, точное сравнение Room → Prometheus и Grafana EN/RU. Сохранность истории и настроек Samsung проверена. Проверки явной continuous-команды не заменяют испытание устройства, зарегистрированного в SOTI.

Проверки текущей версии: `assembleDebug`, `lintDebug` и 8 целевых JVM-тестов (`ResumeGuardTest`, `ResumeTest`, `LabWireFixtureTest`) — PASS. Lint: 0 errors, 33 warnings, 1 hint; 15 warnings относятся к доступным обновлениям зависимостей. Также прошли 13 Python-тестов, проверка 1311 строк локализации и Compose config с безопасным `.env.example` и overlays. Артефакты — `artifacts/git-handoff-00.00.00.04/`.

APK `artifacts/AHWOTel-00.00.00.04-code17-debug.apk`: 14 975 436 bytes, package `com.ahwotel`, versionName `00.00.00.04`, versionCode `17`, minSdk 26, targetSdk 36. Подпись v2 проверена; SHA-256 debug-сертификата совпадает с установленным code16: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`. SHA-256 APK: `085f377a1869e11fa6174c1da040d8bfdd677b1c1570a604b939967e7fdd0d03`.

Суточная проверка после исправления OOM Grafana начата **18 сентября 2026, 10:50:57 МСК**. Её минимальное время окончания — **19 сентября 2026, 10:50:57 МСК**. На момент подготовки Git-передачи статус `RUNNING`, ошибок нет; это ещё не `PASS`. Актуальный итог записывается в `artifacts/recovery-code16/soak/result.json`. Предыдущий неуспешный прогон сохранён отдельно; его время не засчитывается.

## CI/container gate

`gkm-ci-container-delivery`: **не пройден для verified контейнерной поставки заказчику**. Передаются исходники локального стенда, тесты и инструкции сборки.

- Производные образы `:code16` собраны локально внутри Docker и реально проверены: image IDs, readiness, non-root, `cap_drop: ALL`, диагностика и операционный Docker local log sink. Это `unverified-local`, без подтверждённой привязки образа к итоговому release commit.
- Не настроены CI build/push в registry, каноническая verified-сборка, полная revision/clean-source/runtime-artifact identity и image-only deployment profile. Git tag не подтверждает эти этапы. Android CI также не настроен.
- P0 диагностики/логирования подтверждён на code16: Off по умолчанию, Basic и временный Verbose, structured stdout/stderr + Logcat в Android; в контейнерах — основной structured pipeline и отдельный logging overlay.
- Private CA/mTLS/OIDC не применяются к текущему локальному auth None профилю. Для него используются публичные pinned upstream images, безопасный `.env.example` и явная настройка адреса/портов.

В Git передаются исходники, тесты, Room schema, конфигурационные шаблоны и документация. APK, реальные `.env`, `.runtime`, SDK/JDK/Gradle caches, ключи, снимки телефона и сырые журналы исключены правилами ignore и проверкой staging. Публикация бинарного APK в GitHub Releases в этот этап не входит.
