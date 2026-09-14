# Git-поставка AHWOTel 00.00.00.03

Дата: 2026-09-14. Ветка: `main`. База: корневой `VERSION=00.00.00.02`, аннотированный `v00.00.00.02`, commit `beaf48665be1d859bd298107846ab9b452accb6b`. Локальные и удалённые branch/tag совпадали при preflight; recovery push не требовался. Новая версия — `00.00.00.03`, тег — `v00.00.00.03`.

## Состав

- Android: HTTP OTLP только по явному разрешению, auth None; строгая проверка endpoint, запрет redirects, отзыв разрешения и отмена выполняющейся отправки без остановки локального сбора.
- Ограниченный разбор OTLP-ответа и partial success; узкая миграция ранее сохранённых HTTPS-портов 0/65536 с сохранением остальных настроек и EN/RU уведомлением.
- Локальный Docker Compose стенд Collector → Prometheus → Grafana: сохранение данных/очереди, ограничения ресурсов, pinned images, диагностика Off/Basic/временный Verbose, structured stdout/stderr и отдельный logging overlay.
- Исправления review: истечение Verbose после перезапуска, отсутствие отката конфигурации при истечении, точные временные границы PromQL, отдельные источники Android/Knox, полная EN/RU справка CPU/Thermal на небольшом экране.
- JVM, Android, Go, Python и browser-проверки; отчёты физических и сквозных проверок.
- Презентация проекта: 9 слайдов, редактируемый PPTX, PDF, иллюстрации и скрипт генерации. Оценка затрат и статистика токенов — исторический снимок, не текущие показания сессии.

Корневой `VERSION` задаёт `versionName`; Android `versionCode=14`. От установленного и проверенного code13 APK отличается версией поставки; Room остаётся 4. Подготовка Git-передачи не переустанавливала приложение на Samsung и не прерывала его текущую сессию. Физические и сквозные проверки code13 не выдаются за новый прогон на code14.

## Проверки поставки

```bash
python3 scripts/check-locales.py
python3 scripts/test_instrument.py
python3 scripts/test_otel_lab.py
node --check scripts/check-lab-grafana.mjs
node --check scripts/check-lab-help.mjs
bash -n scripts/otel-lab.sh
./scripts/gradle.sh :app:testDebugUnitTest \
  --tests com.ahwotel.TransportTest --tests com.ahwotel.HttpPolicyTest \
  --tests com.ahwotel.OtlpResponseTest --tests com.ahwotel.EndpointPortMigrationTest \
  :app:lintDebug :app:assembleDebug --offline
git diff --check
```

- 1292 EN/RU строки, placeholders и полнота подсказок — PASS.
- 2 проверки instrumentation guard, 3 проверки generator/CLI, синтаксис browser/helper scripts — PASS.
- 13 JVM-тестов на новой версии: Transport (6), HTTP policy (3), OTLP response (2), migration портов (2) — PASS, без failures/errors/skips.
- Сборка APK и Lint — PASS: 0 errors, 33 warnings, 1 hint. Gradle выполнен вне sandbox после ошибки локального сокета `Could not determine a usable wildcard IP for this machine.`
- APK manifest: `com.ahwotel`, `versionName=00.00.00.03`, `versionCode=14`, targetSdk 36. Подпись APK проверена; SHA-256 сертификата совпадает с предыдущими поставками: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`.
- PPTX: 9 слайдов, ZIP integrity — PASS; известные шаблоны ключей/токенов в передаваемых файлах не найдены.

Протокол: `artifacts/git-handoff-00.00.00.03-build.txt`; сводка: `artifacts/git-handoff-00.00.00.03-result.json`.

Локальный артефакт: [AHWOTel-00.00.00.03-code14-debug.apk](../artifacts/AHWOTel-00.00.00.03-code14-debug.apk), 14945340 bytes, debug signing. SHA-256: `b81566fd9239c23642076ca1d3f2b4e3a6f9b7b6a1b2212817588a5256a1f720`. APK code14 на устройство в рамках Git-передачи не устанавливался; в ADB доступен только Samsung с продолжающимся сбором на code13.

Основные сценарии реализации и доказательства: [code12](VALIDATION-otel-lab-code12.md), [исправления code13](VALIDATION-review-code13.md). Подтверждены три HTTP-сценария на Samsung API27, физическая миграция сохранённых портов, точные совпадения Room и Prometheus, графики/справка EN/RU, истечение диагностики, сохранность deployment-настроек и восстановление очереди. APK code13 установлен на Samsung с сохранением истории/настроек; после Home сбор продолжился новой непрерывной сессией. Это результаты предшествующего этапа, а не повторная проверка при push.

## CI/container gate

Результат `gkm-ci-container-delivery`: **не пройден для проверенной контейнерной поставки заказчику**. Текущая передача включает исходники локального стенда и инструкции его сборки.

- Локальная сборка supervisor выполняется внутри Docker, не требует Go на хосте; upstream images закреплены digest. Runtime readiness, выбранные/запущенные image ID, non-root, уровни диагностики и операционный log sink проверены на code13 и приведены в его отчёте.
- Производные образы локальные, с label `code13`. Они не имеют подтверждённой привязки к итоговому Git commit и не считаются `verified`; не опубликованы в registry. Эту локальную сборку следует рассматривать как `unverified-local`, хотя отдельная machine-readable provenance-маркировка пока не реализована.
- Не реализованы CI build/push, канонический verified build path, полный Git revision/clean-source/runtime artifact identity в образах и image-only deployment profile. Блокер проверенной контейнерной поставки — отсутствие этого контура; Git tag его не заменяет.
- Custom CA trust gate: `not-applicable` для текущего auth None стенда с публичными upstream images. Private CA, mTLS/OIDC и промышленная авторизация не входят в этот этап.
- P0 диагностики/логирования реализован: Android Off/Basic/ограниченный Verbose через structured stdout/stderr + Logcat; контейнеры — Off/Basic/ограниченный Verbose через основной structured pipeline и отдельный Docker local logging overlay. Runtime-доказательства — в отчёте code13.

В Git включены только исходники, тесты, безопасный `.env.example`, конфигурационные шаблоны, документация и материалы презентации. Реальный `.env`, `.runtime/`, APK, debug keystore, SDK/JDK/Gradle caches, резервные копии устройства и сырые журналы исключены. `.dockerignore` ограничивает build context исходниками supervisor. APK не публикуется в GitHub Releases в рамках этой передачи. Android CI также не настроен; результаты сборки получены локально.
