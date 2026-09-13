# Acceptance scenarios

## Предусловия

- JDK 17, Android SDK 36, доступные Maven/Google repositories и локальные сокеты Gradle/adb.
- Для UI: Android emulator/API 35 либо физическое Android 8+ устройство с USB debugging, разблокированный экран.
- Для instrumentation: отдельный acceptance APK `com.ahwotel.acceptance` и `SafeTestRunner`, устанавливаемые через `scripts/instrument.sh`. Diagnostic Off по умолчанию; OTLP/SOTI выключены, без credentials.
- Обычный debug APK `com.ahwotel` используется для проверки обновления. Изменяющие данные instrumentation не должны запускаться в его профиле.
- Реальные SOTI/OEM сценарии требуют отдельного устройства/enrollment; emulator не доказывает совместимость парка.

## Основной пользовательский сценарий

Scenario: первый запуск → английский интерфейс → пятиминутный START → переключение на русский во время сбора → screen-off → screen-on → timeout → History → графики → перезапуск.

Expected result: локальные замеры и session identity сохранены, исходные timestamps корректны, значения и графики видимы, pause/resume следует настройке, timeout прекращает сбор. Русский сохраняется после перезапуска; смена языка не создаёт новую сессию.

Validation scope: новый Android APK и его локальные контракты.

Verification: `:app:connectedDebugAndroidTest`, ручной/emulator пятиминутный smoke, SQL/JSON export, `adb logcat`.

Evidence: результаты instrumented tests, screenshot фактически видимого графика и обоих языков, записи session_started/session_finished, отсутствие новых замеров после timeout. Ускоренный 4-second automated test дополняет, но не заменяет 5-minute runtime smoke.

## Дополнительные сценарии

- Continuous session → notification Stop; screen-off continuation; screen-off pause with automatic resume; timer expires while paused.
- Force-stop/process loss/reboot → OFF; старая сессия INTERRUPTED при следующем запуске.
- Missing CPU/thermal → UNSUPPORTED; временная ошибка/null → пропуск, другие collectors продолжают работать.
- Basic и временный Verbose → structured stdout/stderr, Logcat и файл; после expiry verbose events больше не пишутся.
- Retention age/size → удаляются старейшие данные; реальная глубина истории видна. SQLite и WAL включены в размер.
- История 14 дней × 2 секунды (604800 замеров) → SQL aggregation возвращает ограниченное число корзин с min/max.
- OTLP OFF → нет запросов. ON + TLS test server → protobuf и исходные timestamps без Authorization; bad certificate → отказ; reconnect → очередь отправляется без смены timestamps.
- SOTI receiver disabled/repeated START/conflicting START/STOP mismatch проверяются локальным контрактом. Реальная доставка SOTI не считается проверенной через adb.

## CPU diagnostics 0.2.0

- Установка поверх 0.1.0: число и содержимое старых записей/сессий сохранены, новые поля старых строк null.
- Diagnostics → Check availability: реальные источники проверяются внутри APK, без сессии число записей не меняется.
- CPU wait использует один TID потока пробы; пауза прекращает пробы, возобновление создаёт baseline; выключенные CPU/indirect не запускают пробы.
- `/proc/stat` permission_denied → системный CPU UNSUPPORTED + отдельные косвенные показатели; `schedstat` недоступен → остаётся задержка таймера. Значения не заменяются нулями.
- Новые графики видимы на физическом экране; экспорт содержит собственный timestamp пробы; общий Performance State не меняется от косвенных метрик.
- Логи при Off содержат результат выбора источника и fallback без повторяющихся отказов; Basic/Verbose остаются в том же pipeline.
- Пятиминутный smoke на SM-J260F: screen-off/on, timeout, реальные значения/статусы, повторное измерение CPU/PSS агента, сохранность истории после restart.

## Ограничения среды

Android Doze/OEM могут ограничивать wakelock/FGS. `specialUse` применён к пользовательской диагностике с видимым stop control; Play Store publication не входит в поставку. System-wide CPU и энергопотребление требуют измерений на целевых OEM, особенно SM-J260F.

APK, локальные тесты, emulator smoke, физический smoke и SOTI delivery отражаются раздельно. Не считать успешную компиляцию подтверждением runtime.
## Подсказки 0.2.1

Для целевой проверки использовать `com.ahwotel.MetricHelpAcceptanceTest` через третий аргумент `scripts/instrument.sh`.

- Открыть Diagnostics → Metric guide до запуска сбора: все 22 параметра доступны, просмотр не меняет число замеров и результаты проверок источников.
- Открыть CPU wait из Monitor, History и Settings при выключенном CPU/Indirect CPU: описание одинаковое, закрытие возвращает к исходному экрану. History показывает общую справку об интервалах/агрегации без текущего статуса источника.
- Через Settings сохранить Русский, открыть «Тепловой запас», пересоздать Activity: заголовок, текст и кнопки остаются русскими. Вернуть English и проверить Performance state.
- На маленьком экране и с крупным шрифтом прокрутить длинные тексты до источника и причин недоступности; проверить доступность кнопки закрытия и возврата. Подтвердить снимками EN/RU.
- В Diagnostics открыть пояснение результата UNSUPPORTED / permission_denied: время проверки и причина читаются; значение не представлено нулём.
- Открыть справку во время сбора: sessionId и настройки сохраняются, замеры продолжаются.
- На чистом профиле эмулятора с русским языком Android первый запуск AHWOTel и подсказок остаётся английским. После смены языка приложения проверить сохранение выбора при полном перезапуске.
- При обновлении 0.2.0 → 0.2.1 сравнить сохранённые строки samples/sessions/outbox и настройки; очистка данных телефона не допускается.

## Исправления review 0.2.2

Validation scope: изоляция instrumentation, справка и её EN/RU accessibility-пути; полный набор измерительных/сетевых тестов не требуется.

| Scenario | Expected result | Verification / evidence |
| --- | --- | --- |
| Неверный APK/package/target/runner | Отказ до установки и любых операций ADB | `python3 scripts/test_instrument.py`; проверка обычного APK через `--app-apk ... --verify-only` |
| Неверный target внутри Android | Application не создаётся | `TestIsolationAcceptanceTest` |
| Сохранность рабочего профиля | Все исходные строки samples/sessions/outbox и байты настроек равны до/после | `check-test-isolation.py prepare/verify`, SQLite snapshots и SHA-256 |
| Ошибка внутри тестового сценария | Его сессия остановлена, настройки тестового профиля восстановлены | `MetricHelpAcceptanceTest#failedScenarioStopsItsSessionAndRestoresTestSettings` |
| HEADROOM при интервалах 2/30/60 секунд | EN/RU справка объясняет минимальный промежуток, не обещает фиксированную частоту | Сверка текста с Collectors и Settings.INTERVALS; ресурсные тесты |
| Пустая история / UNSUPPORTED | Справка и × видны после анимации, закрытие возвращает на исходный экран | Целевые Compose tests и снимки |
| Клавиатура / TalkBack | Открытие, чтение, закрытие и возврат фокуса доступны на EN/RU | Keyboard test; отдельный TalkBack smoke с указанием версии службы |
| Знак × при крупном шрифте | Векторный значок виден, а кнопка нажимается; локализованная accessibility label сохранена | Проверка пикселей кнопки и снимки пустой истории, UNSUPPORTED и русской справки при font scale 1.3 |
| Обновление 0.2.1 → 0.2.2 | Обычный APK запускается и сохраняет данные | Установка поверх прежнего APK, restart, сравнение snapshot и APK hash |

Verification prerequisites: локальные JDK/SDK, Python 3, apkanalyzer, доступ к сокетам ADB/Gradle; явный serial. Для синтетического профиля, шрифта 130% и TalkBack — тестовый эмулятор API 35. Перед UI-прогоном проверить отсутствие системного ANR-диалога: System UI не является частью AHWOTel. На физическом устройстве не менять системные настройки и не создавать синтетический рабочий профиль.

Diagnostic gate: Off по умолчанию, Basic и временный Verbose; основной structured pipeline stdout/stderr + Logcat и JSONL подтверждать отдельным целевым тестом.
