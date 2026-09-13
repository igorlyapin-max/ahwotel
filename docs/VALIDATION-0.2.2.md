# Проверка исправлений review — AHWOTel 0.2.2

Дата: 2026-09-13. Версия: `0.2.2`, `versionCode=4`, обычный пакет `com.ahwotel`.

## Изменения

- **P1:** инструментальные тесты перенесены в build type `acceptance`, пакет `com.ahwotel.acceptance`, отдельное имя AHWOTel Test / AHWOTel Тест. Рабочие SQLite, DataStore и outbox находятся в другом Android sandbox. `scripts/instrument.sh` проверяет manifests обоих APK до любой ADB-операции; SafeTestRunner повторяет проверку до создания Application. Старый запуск instrumentation для `com.ahwotel` не поддерживается.
- Подготовка и завершение help-тестов работают с тестовым профилем. Завершение ждёт остановки своей сессии и восстанавливает настройки, включая намеренное исключение внутри сценария. Изменения production MonitoringService не входили в исправление.
- **P3:** HEADROOM в EN/RU описан как опрос не чаще одного раза в 10 секунд. Фактическая частота зависит от sampling interval; пропуски возможны. Алгоритм измерения, Room и форматы экспорта не менялись.
- Добавлены проверки пустой истории, клавиатуры, безопасного runner и сохранности рабочего профиля. Реальный TalkBack вынесен в отдельный opt-in сценарий с внешними зависимостями.
- Ручная проверка воспроизвела невидимый текстовый × в русской подсказке при шрифте 130%, хотя кнопка продолжала нажиматься. Знак заменён векторной `Icons.Default.Close`, не зависящей от размера шрифта. Регрессия проверяет светлые пиксели иконки в снимке кнопки, дополнительно к semantics и нажатию.

## Сборка и статические проверки

```bash
python3 scripts/test_instrument.py
python3 scripts/check-locales.py
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.MetricHelpTest \
  :app:lintDebug :app:lintAcceptance :app:assembleDebug --offline
./scripts/gradle.sh :app:assembleAcceptance :app:assembleAcceptanceAndroidTest --offline
```

Host guard: 2/2. Ресурсные тесты: 2/2. Локализация: 374 строки с согласованными placeholders; 22 метрики × 8 разделов на каждом языке. Lint debug/acceptance: по 0 errors, 30 warnings, 1 hint. Эти предупреждения не считаются исправленными данным изменением. Финальная сборка и проверки: `artifacts/v0.2.2/build-close-icon.txt`, `artifacts/v0.2.2/acceptance-build.txt`; XML результатов и lint сохранены рядом.

Реальный обычный APK, переданный в `--app-apk` с `--verify-only`, отклонён с exit 1 и `unsafe_instrumentation_package`: `artifacts/v0.2.2/unsafe-apk-preflight.txt`. Host tests отдельно проверяют неправильные package, targetPackage, runner и повреждённый XML, требуя ноль ADB-вызовов.

## Рабочий профиль и обновление

Проверка выполнялась только на тестовом эмуляторе API 35, `emulator-5554`. Создан синтетический рабочий профиль: retention 90 дней, одна сессия и замер возрастом 30 дней, один pending OTLP payload с отложенной на сутки отправкой в `https://isolation.invalid/v1/metrics`. Реальные данные пользователя не использовались.

До и после help-тестов, их аварийного завершения и установки обычного 0.2.2 поверх 0.2.1 совпали все строки `samples`, `sessions`, `outbox`, байты DataStore и Room version 2. Cold start нового APK завершился `Status: ok`. Повторная сверка после перезапуска Android также прошла. Снимки и контрольные суммы: `artifacts/v0.2.2/isolation/`; результат: `artifacts/v0.2.2/isolation-verify.txt`.

После исправления × обновление повторено с точным финальным APK: на эмулятор установлен 0.2.1, поверх него 0.2.2, выполнен cold start и сравнение контрольного профиля. Все данные совпали: `artifacts/v0.2.2/upgrade-final.txt`. SHA-256 установленного `base.apk` совпал с передаваемым артефактом; финальный cold start — 1224 мс: `artifacts/v0.2.2/ordinary-final-launch.txt`, `ordinary-final.png`.

После проверок синтетический профиль заменён исходной пустой базой и исходными настройками из резервной копии. Восстановлены 1080 × 1920 px, density 420, font scale 1.0 и выключенная accessibility service. Тестовые APK, включая старый `com.ahwotel.test`, TalkBack и eSpeak удалены. Обычный `com.ahwotel` 0.2.2 сохранён. Протокол: `artifacts/v0.2.2/cleanup.json`.

Сертификат обеих версий, SHA-256: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`.

## UI и доступность

TalkBack финальной сборки: **1/1**, 82,28 с. Для EN и RU проверены открытие справочника и CPU wait командами TalkBack, доступность всех восьми абзацев с автоматической прокруткой, закрытие и возврат accessibility focus к `Metric guide` / `Справочник параметров`. Протокол: `artifacts/v0.2.2/talkback-release-verified.txt`; JSON с событиями выбранных элементов и снимки: `artifacts/v0.2.2/talkback-release/`. Использован экран 1080 × 1920 px, density 420, font scale 1.3.

Общий прогон финальной сборки: **10/10**, 37,526 с, экран **360 × 640 dp** (540 × 960 px, density 240), font scale **1.3**. Протокол: `artifacts/v0.2.2/help-vector-close-verified.txt`. Охват:

- Все 22 пункта справочника и входы Monitor / History / Settings / Diagnostics; отключённый сборщик; открытие без запуска сессии или чтения источников.
- EN/RU, смена языка и пересоздание Activity с открытой справкой; длинные подсказки CPU wait, HEADROOM и STATE.
- Пустая история и UNSUPPORTED с причиной и временем проверки; видимая кнопка × размером не менее 48 dp; закрытие возвращает исходный экран.
- Tab / Enter: открытие и закрытие справки, возврат keyboard focus на исходную кнопку.
- Открытая справка сохраняет активную сессию; намеренное исключение в тесте приводит к остановке тестовой сессии и восстановлению её настроек.
- Runner отклоняет рабочий и неизвестный package до конструктора Application.
- Diagnostic Basic / временный Verbose, его истечение и structured logging через stdout/stderr, Logcat и локальный JSONL. P0 по диагностике и operational sink подтверждён.

Итоговые снимки небольшого экрана: `artifacts/v0.2.2/vector-close/`. Видимость × при пустой истории, UNSUPPORTED и в русской подсказке проверена на изображениях. Более ранние снимки в `small-screen/` относятся к реализации с текстовым знаком и не являются доказательством исправленной отрисовки.

На чистом acceptance-профиле отдельно проверен первый запуск на английском при русской локали Android: `artifacts/v0.2.2/fresh-default-en.xml`. Выбранный русский язык и открытая справка сохраняются при пересоздании Activity в общем прогоне.

Команда общего прогона:

```bash
./scripts/instrument.sh emulator-5554 artifacts/v0.2.2/help-vector-close-verified.txt \
  com.ahwotel.MetricHelpAcceptanceTest,com.ahwotel.TestIsolationAcceptanceTest,com.ahwotel.AcceptanceTest#diagnosticsUseFileAndSystemSinksAndVerboseExpires
```

Тестовый TalkBack: `com.android.talkback`, версия `2021-04-23`, x86_64; eSpeak `1.52.0`. Источники сборок: [F-Droid TalkBack](https://f-droid.org/en/packages/com.android.talkback/) и [F-Droid eSpeak](https://f-droid.org/en/packages/com.reecedunn.espeak/). Эта проверка не является сертификацией всех версий TalkBack или оценкой качества произношения.

При подготовке жестового сценария зарегистрирован сбой системного `InputDispatcher` API 35: `Expected ACTION_HOVER_MOVE ... action=HOVER_ENTER`, затем `INSTRUMENTATION_ABORTED: System has crashed`. Android восстановился; жестовой прогон не считается пройденным. Итоговый сценарий использует клавиатурные команды реального TalkBack. Android TestApi нужен, потому что стандартный `UiAutomation.injectInputEvent` обходит accessibility input filter: [исходный код Android 15](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/UiAutomation.java). Навигация TalkBack с клавиатурой описана [Google](https://support.google.com/accessibility/android/answer/6006598?hl=en-GB).

Harness сохраняет работающий accessibility service при подготовке, сбрасывает кэш accessibility focus и ждёт автоматическую прокрутку. Промежуточные протоколы с ошибками оставлены в `artifacts/v0.2.2`; успешный TalkBack-протокол указан выше.

## Поставка и ограничения

APK: `artifacts/AHWOTel-0.2.2-debug.apk`, 14 235 422 bytes.

SHA-256: `000db94ef6b6cc9a5676c1d980b631c5be22b3d5b5e03decaf4fe7657dacadea`.

Физический телефон в финальной проверке `adb devices -l` отсутствовал; доступен только эмулятор. Установка 0.2.2 и повторная проверка на SM-J260F не выполнены. Debug signing сохранён; production signing и commit/push не входили в эту задачу.
