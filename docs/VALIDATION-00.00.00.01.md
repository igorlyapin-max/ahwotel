# Первая Git-поставка — AHWOTel 00.00.00.01

Дата: 2026-09-13. Исходная ветка: `main`; до передачи репозиторий содержал только `LICENSE`, совместимых тегов не было. Начальная версия по принятому формату — `00.00.00.01`, тег — `v00.00.00.01`.

## Состав

Автономный Android MVP, исходное ТЗ, Gradle wrapper, тесты, скрипты проверки и документация. Включены сбор метрик, локальная история и графики, EN/RU, CPU fallback/capability logs, подробные подсказки и исправления review 0.2.2.

Корневой `VERSION` задаёт `versionName`; Diagnostics и OTLP используют полученный `BuildConfig.VERSION_NAME`. `versionCode=5` позволяет обновить локальный APK 0.2.2 (`versionCode=4`). Измерительные алгоритмы, схема Room 2 и настройки не менялись при подготовке Git-поставки.

Локальные инструменты, APK, кэши, ключи, протоколы, снимки экрана и инструкции агента исключены из Git. Отдельное `android_oem_extended_telemetry_knox_mvp_TZ.txt` не входит в этот объём работ. Требования к EN/RU подсказкам доступны в README.

## Проверки перед передачей

```bash
python3 scripts/test_instrument.py
python3 scripts/check-locales.py
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.TransportTest \
  :app:lintDebug :app:assembleDebug --offline
```

- Host guard: 2/2; локализация: 374 EN/RU строки, 22 метрики × 8 разделов.
- OTLP/TLS: 4/4; проверены payload, timestamps, scope и проверка сертификата сервера.
- Сборка успешна; lint: 0 errors, 30 warnings, 1 hint. Протокол: `artifacts/git-handoff-build.txt`.
- Предшествующие измерительные проверки: [0.2.0](VALIDATION-0.2.0.md); подсказки и локализация: [0.2.1](VALIDATION-0.2.1.md); исправления review, 10/10 native-сценариев и 1/1 TalkBack EN/RU: [0.2.2](VALIDATION-0.2.2.md). Эти сценарии не выдаются за повторный полный прогон на новой версии.
- P0 диагностики и логирования подтверждён в проверке 0.2.2: Off по умолчанию, Basic, временный Verbose; основной structured pipeline, stdout/stderr, Logcat и опциональный JSONL.

## Запуск пересобранного APK

На тестовом эмуляторе Android 15 / API 35 подтверждено обновление 0.2.2 → 00.00.00.01, cold start с `Status: ok`, запуск и остановка сбора через UI. В журнале есть `session_started` / `session_finished`, в локальном хранилище появились замеры, очередь OTLP пуста. Сохранённый русский язык остался выбранным; в Diagnostics виден `AHWOTel 00.00.00.01`.

SHA-256 установленного `base.apk` совпал с локальным артефактом ниже. Доказательства: `artifacts/git-handoff/result.json`, `launch.txt`, `runtime-log.txt`, `diagnostics-version.xml`, `diagnostics-version.png`. Версия проверена в нижней части прокручиваемого экрана Diagnostics, после списка источников. Данные физического телефона не затрагивались.

## Артефакт

Локальный APK: `artifacts/AHWOTel-00.00.00.01-debug.apk`, 13 941 100 bytes.

SHA-256: `ce37cd719976a661b3cb99aa0c2d46d68df2d8c504cad7ae572ee5092dbb58c7`.

Debug signing, сертификат SHA-256: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`; совпадает с локальным 0.2.2. Keystore не публикуется. После клонирования новый debug key даст другую подпись, если сборочная среда не использует прежний ключ.

## Применимость CI/container gate

Поставляется Android APK; Docker image, compose и контейнерный runtime отсутствуют, контейнерные проверки неприменимы. GitHub Actions для Android в этом репозитории не настроен; успешный удалённый CI build и публикация APK в GitHub Releases не заявляются. Представленные проверки выполнены локально. Физический телефон при этой передаче не подключён.
