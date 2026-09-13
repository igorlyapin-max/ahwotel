# Git-поставка AHWOTel 00.00.00.02

Дата: 2026-09-13. Ветка: `main`. База: `v00.00.00.01`, commit `5a8710d6aac6c35bcd7a006c1947a0b2b62af6fd`; локальные и удалённые branch/tag совпадали при preflight. Следующая версия — `00.00.00.02`, аннотированный тег — `v00.00.00.02`. Незавершённых предыдущих push не было.

## Состав

- Графики с осями времени/значений, агрегацией и локализованными состояниями.
- OEM/Knox: проверка доступности, дополнительные показатели, инвентаризация, события, managed configuration, экспорт и EN/RU-подсказки.
- Применение непрерывного/ограниченного режима к текущей сессии с сохранением первоначального времени старта; исправления паузы и навигации Back.
- Независимые Battery и Self Telemetry: настройки, быстрые и медленные измерения, локальная история, графики, экспорт и подробные подсказки.
- Исправления последних review: сроки очереди OTLP и OEM, измерительные контракты, полнота графиков, ограничение числа WorkManager-задач, экспорт после пересоздания Activity, профили Battery/Self-only и границы учёта WakeLock.
- Room migrations до схемы 4, исходные OEM/Self ТЗ, тесты, генераторы ресурсов и отчёты проверок.

Корневой `VERSION` задаёт `versionName`; Diagnostics и OTLP используют `BuildConfig.VERSION_NAME`. `versionCode=11` отличает поставку от проверенной локальной code10. При подготовке Git-поставки изменены только версия и документация; измерительные алгоритмы и Room 4 сохранены.

Локальные APK, SDK/JDK, кэши, ключи подписи, журналы, снимки экрана, инструкции агента и временные артефакты исключены из Git. Бинарный APK не публикуется в GitHub Releases в рамках этой передачи.

## Проверки

```bash
python3 scripts/check-locales.py
python3 scripts/test_instrument.py
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.TransportTest \
  :app:lintDebug :app:assembleDebug --offline
git diff --check
```

- 1288 EN/RU строк, placeholders и контракты подсказок: passed.
- Две проверки изоляции инструментального harness: passed.
- Шесть OTLP/TLS-тестов на новой версии: passed, без failures/errors.
- Сборка и Lint: passed; 0 errors, 33 warnings, 1 hint. Протокол: `artifacts/git-handoff-00.00.00.02-build.txt`.
- Манифест APK: `com.ahwotel`, `versionName=00.00.00.02`, `versionCode=11`, minSdk 26, targetSdk 36.
- Подпись APK проверена; сертификат совпадает с предыдущими локальными сборками.

Предшествующая проверка тех же измерительных алгоритмов: [code10](VALIDATION-review-code10.md) — 41 focused JVM-тест и 21 отдельный native-сценарий на API 35 с учётом повторных проверок. Эти результаты не выдаются за новый полный прогон на code11. Ранее выполненные проверки OEM, таймера и Battery/Self: [code6](VALIDATION-OEM-code6.md), [code7](VALIDATION-timer-code7.md), [code8](VALIDATION-battery-self-code8.md), [code9](VALIDATION-review-code9.md).

P0 диагностики и логирования подтверждён native-сценарием code10: Off по умолчанию, Basic, временный Verbose с истечением срока; основной structured pipeline, stdout/stderr, Logcat и опциональный JSONL. Измерительные ограничения, включая отсутствие доказательства overhead ≤5% и лицензированной среды Knox, сохраняются.

## Запуск APK поставки

На эмуляторе Android 15/API 35 выполнены установка с `-r`, холодный запуск (`Status: ok`), запуск и остановка сбора через UI. Во время сбора подтверждён foreground service, после Stop он отсутствует; журнал содержит `session_started` и `session_finished`. На экране Diagnostics виден `AHWOTel 00.00.00.02 (11)`. Сохранённые настройки остались побайтно прежними; SHA-256 установленного `base.apk` совпал с локальным артефактом ниже.

Доказательства: `artifacts/git-handoff-00.00.00.02/result.json`, `install.txt`, `launch.txt`, `services-running.txt`, `runtime-log.txt`, `diagnostics-version.png` и `last-ui.xml`. Первый запуск UI-проверки был перекрыт системным ANR эмулятора; повтор после закрытия диалога и исправления прокрутки в smoke-helper прошёл. Приложение для этого не менялось. Проверка не затрагивала физический Samsung.

## Артефакт

Локальный APK: [AHWOTel-00.00.00.02-code11-debug.apk](../artifacts/AHWOTel-00.00.00.02-code11-debug.apk), 14 925 204 bytes.

SHA-256: `4f9f35d64b559e0d490e9656c5c5b7d11c6992d2dc935f05a9ea9508ee60a29d`.

Debug signing, сертификат SHA-256: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`. Keystore в Git не включён. Для обновления уже установленного приложения нужен тот же ключ подписи; новая сборочная среда по умолчанию создаёт другой debug key.

## Samsung и CI/container gate

Проверка code10 на физическом Samsung прервалась при потере USB-соединения; main APK на телефоне не обновлён. Последний подтверждённый установленный вариант — code9; текущий сбор без подключения проверить нельзя. После восстановления связи остаются проверка финального APK, нормальная остановка старой сессии, установка с сохранением данных и запуск новой непрерывной сессии с прежними настройками.

Поставляется Android APK, Docker image/compose/контейнерный runtime отсутствуют. Контейнерные требования `gkm-ci-container-delivery` неприменимы. Android GitHub Actions в репозитории не настроен: удалённая CI-сборка, публикация бинарного APK и установка на Samsung не подтверждаются фактом Git push. Доказательства сборки и тестов получены локально.
