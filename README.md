# AHWOTel — Android Performance Monitor

Автономный Android APK: системные метрики, исходные замеры в SQLite, графики и диагностические сессии. Android 8+ (`minSdk=26`, `targetSdk=36`). Бизнес-приложение не изменяется.

Интерфейс: **English по умолчанию**, переключение на **Русский** в Settings → Interface language → Save settings. Язык устройства не меняет первоначальный выбор приложения.

Материалы для обсуждения пилота: [скелет презентации — 9 слайдов, схемы, заметки докладчика и оценка затрат](docs/PRESENTATION-AHWOTel.md).

Готовая презентация с иллюстрациями: [PowerPoint (.pptx)](docs/AHWOTel-Project-Overview.pptx) · [PDF для просмотра](docs/AHWOTel-Project-Overview.pdf). Текст и схемы редактируются, заметки докладчика включены в PPTX.

Текущая Git-поставка — **00.00.00.03**, `versionCode=14`, Room **4**. Добавлены [лёгкий стенд Collector → Prometheus → Grafana](deploy/otel-lab/README.md), HTTP по явному разрешению в APK (auth None), EN/RU dashboards и исправления review: срок Verbose, восстановление настроек с недопустимым портом, точность временных границ и разделение источников в графиках, подробная справка. Состав и проверки: [VALIDATION-00.00.00.03.md](docs/VALIDATION-00.00.00.03.md). На Samsung проверен и установлен функционально эквивалентный code13; code14 меняет версию поставки. Контейнеры стенда собираются локально; проверенная CI/registry-поставка пока не реализована.

Предыдущая Git-поставка — **00.00.00.02**, `versionCode=11`, Room **4**. Включает OEM/Knox, графики с осями, исправленный таймер, независимые Battery/Self Telemetry и исправления review. При установке на Samsung история и настройки сохранены; непрерывный сбор возобновлён новой сессией. На устройстве прошли 20 отдельных native-сценариев, включая OTLP с локальным HTTPS mock server. После подключения Wi-Fi часы сменились с 2018 на 2026 год и старые записи удалились по retention; резервные копии сохранены на компьютере. Результаты: [VALIDATION-samsung-code11.md](docs/VALIDATION-samsung-code11.md). Состав Git-поставки: [VALIDATION-00.00.00.02.md](docs/VALIDATION-00.00.00.02.md).

Этап **versionCode=7**, Room **3**: переключение непрерывного режима и длительности применяется к работающей сессии. Ограничение времени считается от первоначального старта, включая паузы screen-off; если новый срок уже истёк, сбор завершается сразу. Сохранение других настроек не меняет таймер. APK: [AHWOTel-00.00.00.01-code7-debug.apk](artifacts/AHWOTel-00.00.00.01-code7-debug.apk).

Установка с сохранением данных и фоновые сценарии на Samsung подтверждены: [проверка таймера и выхода](docs/VALIDATION-timer-code7.md).

Этап **versionCode=6** добавил графики с осями времени/значений, 60 дополнительных показателей Android/Knox, инвентарный список, журнал изменений, managed configuration и EN/RU подсказки. OEM по умолчанию выключен; включается в Settings. Контракт и ограничения: [docs/oem-telemetry.md](docs/oem-telemetry.md).

APK этого этапа: [AHWOTel-00.00.00.01-code6-debug.apk](artifacts/AHWOTel-00.00.00.01-code6-debug.apk). Он установлен на тестовый Samsung с сохранением истории и настроек. Проверки и фактическая доступность Knox: [docs/VALIDATION-OEM-code6.md](docs/VALIDATION-OEM-code6.md).

`VERSION` в корне — единственный источник `versionName`, версии в Diagnostics и OTLP. Формат — `XX.YY.ZZ.NN`; Git-поставка отмечается аннотированным тегом `v<VERSION>`. Android `versionCode` увеличивается отдельно для обновления установленного приложения. Предыдущие локальные APK 0.1.0–0.2.2 и отчёты сохраняют свои исходные обозначения.

Состав первой поставки, результаты проверок и контрольная сумма APK: [docs/VALIDATION-00.00.00.01.md](docs/VALIDATION-00.00.00.01.md).

## Сборка

Нужны JDK 17, Android SDK Platform 36, Build-Tools 35.0.0/36.0.0, platform-tools. Gradle 8.11.1 закреплён wrapper, зависимости закреплены в Gradle-файлах. Локальные инструменты и кэши `.tools/` не входят в Git.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug
./scripts/gradle.sh :app:lintDebug
python3 scripts/check-locales.py
./scripts/adb.sh install -r app/build/outputs/apk/debug/app-debug.apk
./scripts/adb.sh shell am start -n com.ahwotel/.MainActivity
```

При наличии локального SDK/JDK в `.tools/` переменные можно не задавать. Для SDK installation примите licenses через `sdkmanager --licenses`. На ограниченной среде Gradle требует доступа к Maven/Google и локальным сокетам; sandbox network error не означает дефект APK.

APK: `app/build/outputs/apk/debug/app-debug.apk`, debug signing. Это не production signing key. Для обновления уже установленного APK сохраняйте локальный debug keystore; не публикуйте его в Git.

## Использование

1. Установить APK и разрешить уведомления.
2. В Settings выбрать collectors, интервал, длительность или Continuous until stopped.
3. Нажать Start monitoring. Notification → Stop останавливает сбор.
4. В History выбрать сессию/период, приблизить график, нажать точку для min/avg/max; экспортировать CSV или JSON.
5. Diagnostics показывает реальную глубину истории, размер хранения и очередь OTLP.

По умолчанию: сессия 300 секунд, интервал 2 секунды, все collectors, screen-off collection включён, retention 14 дней, лимит 512 MiB. Интервал/collectors/пороги фиксируются на сессию; режим, длительность и screen-off поведение применяются после сохранения сразу. Изменение режима и длительности сохраняет ID сессии и её замеры; изменённые поля обновляются в записи сессии и её configuration. Повторное сохранение не начинает отсчёт заново. Если screen-off collection выключен, сбор приостанавливается и возобновляется при включении экрана; таймер продолжает идти. После reboot/уничтожения процесса мониторинг не возобновляется, предыдущая сессия помечается INTERRUPTED при следующем старте приложения.

Выход через «Назад», «Домой» или удаление карточки из недавних приложений не останавливает foreground service. Для остановки используйте Stop в приложении или уведомлении. Это не гарантия против принудительной остановки или ограничений прошивки. Basic журналирует `session_timing_changed`, `session_timer_expired` и `monitoring_task_removed` через существующие stdout/Logcat/JSONL sinks; ошибки применения настроек не выдаются за успешное переключение.

Storage — внутренний файловый раздел данных приложения. CPU — `/proc/stat`, если разрешён OEM/Android. При недоступности показывается UNSUPPORTED, а не CPU процесса агента. Thermal API доступен с API 29, headroom — с API 30 и опрашивается не чаще одного раза в 10 секунд; фактическая частота зависит от интервала сбора. Публичного API для гарантированной system-wide CPU на всём парке нет. Отсутствующие значения остаются null; графики не соединяют разрывы.

## Ожидание CPU и доступность источников — 0.2.0

Settings → Indirect CPU signals включён по умолчанию и действует вместе со сборщиком CPU. Обычный отдельный поток пробы читает `/proc/self/task/<tid>/schedstat` и измеряет ожидание CPU за интервал; второй показатель — опоздание относительно запланированного запуска пробы. Косвенные показатели собираются также при доступном `/proc/stat`, но не меняют общий Performance state и не подменяют `device.cpu.utilization`.

Fallback: отказ `/proc/stat` → самостоятельные показатели CPU wait / Probe scheduling delay; отказ `schedstat` → остаётся только Probe scheduling delay. Недоступные значения остаются null. Задержки могут быть вызваны GC, планировщиком и ограничениями Android; это не доказательство перегрузки устройства. Поток пробы не выполняет SQLite, OTLP или запись логов. При паузе и глубоком сне измерительный интервал разрывается, после возобновления нужен новый baseline.

Diagnostics → Check availability проверяет источники внутри APK. Вне сессии проверка не сохраняет метрики, во время сессии запрос обрабатывают работающие сборщики. Для каждого параметра показаны source, scope, status, reason и время проверки. Отключённые сборщики не читаются. Постоянные отказы повторно проверяются при новой сессии или по кнопке; временные ошибки — не чаще раза в 30 секунд.

События `source_probe_result`, `capability_changed`, `fallback_selected`, `source_recovered` доступны и при Diagnostic Off. Повторные неизменные отказы не засоряют журнал. Basic добавляет начало/окончание ручной проверки, Verbose временно показывает TID пробы. В логи поступают только типизированные коды источников, метрик, статусов и причин, без raw `/proc` или содержимого исключений.

Экспорт JSON version 2 и CSV содержат `agent.cpu.runqueue_wait` / `agent.scheduling.delay` (ms), `probe.timestamp`, `probe.interval_ms`, `probe.segment`, `sources`. Поле `sources` — группы `[source, status, reason, [metric enum names]]`; общие поля не повторяются для каждой метрики. Графики и OTLP косвенных метрик используют `probe.timestamp`, а не время последующей записи строки. В OTLP добавлены `measurement.scope=probe_thread` и source.

APK 0.2.0 обновляет 0.1.0 тем же signing key; Room 1 → 2 сохраняет историю/очередь и добавляет nullable-поля. Старые записи не содержат косвенных метрик; сохранённые настройки получают включённый Indirect CPU signals. Downgrade базы не поддерживается.

## Подробные подсказки — 0.2.1

Кнопка **ⓘ** у карточки или графика открывает подробную справку: смысл, единицы и область измерения, влияние, интерпретация, пример, ограничения, участие в общей оценке и источник. Панель прокручивается; закрывается кнопкой × или Back. В Settings кнопки у сборщиков показывают связанные параметры. Diagnostics → Metric guide содержит все 22 показателя, даже до первой сессии и при отключённом сборе.

Все разделы и пояснения статусов доступны офлайн на выбранном языке интерфейса; английский — первоначальный язык. Русский перевод включает название «Тепловой запас». После смены языка и перезапуска справка использует сохранённый выбор. Технические коды и идентификаторы остаются исходными для сопоставления с экспортом и логами.

В Diagnostics поясняется фактический результат последней проверки с её временем. В History справка описывает график и агрегацию, не переносит текущую доступность источника на прошлую сессию. Примеры порогов обозначены как значения по умолчанию. Открытие справки не выполняет проверку источников и не запускает сбор.

Тексты сверены с измерительным кодом и первичными источниками: счётчик ожидания описан в [Linux scheduler statistics](https://docs.kernel.org/scheduler/sched-stats.html#proc-pid-schedstat), направление шкалы теплового запаса — в [PowerManager](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)), доступная ёмкость файловой системы — в [StatFs](https://developer.android.com/reference/android/os/StatFs#getAvailableBytes()), сведения о батарее — в [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager). Источник определения RAM — [ActivityManager в AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityManager.java).

Для каждого нового или изменённого параметра одновременно обновляются EN/RU подсказки: что измеряется, единицы и область измерения, влияние, интерпретация, пример, ограничения, участие в Performance state и источник. Подсказки должны работать при отключённом сборе, UNSUPPORTED и отсутствии истории; технические коды сохраняются с локализованными пояснениями. Ресурсы находятся в `values*/metric_help.xml` и `metric_help_ui.xml`; `scripts/check-locales.py` проверяет переводы и обязательные разделы для каждого `Metric`.

## OTLP — выключен по умолчанию

Settings → OTLP export, endpoint вида `https://telemetry.example.org/v1/metrics`. **MVP без аутентификации**: mTLS/JWT/PKI и lifecycle credentials не реализованы. По умолчанию разрешён только HTTPS с проверкой сертификата и hostname. Для локального стенда HTTP включается отдельным переключателем; URL credentials/query и redirects запрещены в обоих режимах. Выключение разрешения HTTP при HTTP endpoint выключает OTLP и очищает очередь отправки, сохраняя историю измерений.

При выключении нет отправки и нет требований к endpoint/credentials. Включение экспортирует только новые замеры. При отключении либо смене endpoint экспортная очередь очищается; локальная история остаётся. Очередь переживает перезапуск и содержит готовые protobuf bytes с исходными timestamps/identity. Лимиты по умолчанию: 24 часа/32 MiB, oldest-first. WorkManager отправляет при наличии сети с backoff; доставка at-least-once, при неоднозначном сетевом сбое возможны дубликаты. Невосстановимые 4xx отбрасываются с диагностикой; 408/429/5xx повторяются. Частичный ответ фиксируется явно.

Интеграция использует OTel SDK Meter API. Сериализатор protobuf OTel 1.50.0 из internal package изолирован в `Telemetry.kt`; при обновлении OTel обязателен wire-format regression test.

UUID установки можно заменить корпоративным `device.id` при остановленном сборе и пустой очереди. IP не используется как identity.

## Команды SOTI — выключены по умолчанию

Включить Settings → SOTI commands. **Авторизация команд в MVP отсутствует:** другие локальные приложения могут отправлять explicit broadcasts. Receiver недоступен до включения. Проверка через `adb` не подтверждает доставку SOTI enrollment.

```bash
./scripts/adb.sh shell am broadcast -n com.ahwotel/.SotiCommandReceiver \
  -a com.ahwotel.START --es sessionId poc-001 --el durationSeconds 300 \
  --el samplingIntervalMs 2000 --ez continuous false \
  --es metrics memory,cpu,storage,thermal,battery --es reason INC-001
./scripts/adb.sh shell am broadcast -n com.ahwotel/.SotiCommandReceiver \
  -a com.ahwotel.STOP --es sessionId poc-001
```

Эквивалент для SOTI legacy scripting (подлежит проверке на enrollment):

```text
sendintent -b "intent:#Intent;component=com.ahwotel/.SotiCommandReceiver;action=com.ahwotel.START;S.sessionId=poc-001;l.durationSeconds=300;l.samplingIntervalMs=2000;B.continuous=false;S.metrics=memory,cpu,storage,thermal,battery;S.reason=INC-001;end"
```

Broadcast result: 202 — принято к запуску/остановке, 200 — повтор существующего sessionId, 409 — конфликт сессии, 400 — некорректная/отклонённая команда, 403 — интеграция выключена. 202 не доказывает старт foreground service: окончательный результат виден в истории/логе `session_started`. Сервис повторно проверяет конфликт и идемпотентность. Параметры команд не меняют endpoint, device identity или настройки безопасности. При запрете фонового запуска появляется уведомление с предложением открыть приложение вручную.

## Диагностика

Settings → Diagnostic level: Off / Basic / Verbose. По умолчанию Off. Verbose действует 15 минут, затем Off. Основные события старта/остановки, unsupported и ошибки пишутся всегда. Diagnostic events идут через тот же structured pipeline: stdout/stderr + Android Logcat + опциональный ротируемый JSONL (2 × 1 MiB). В журнал попадают только event codes и ограниченная числовая metadata: URL, reason, device identity, raw intent, токены и stack traces не журналируются.

```bash
./scripts/adb.sh logcat -d -s AHWOTel:I System.out:I System.err:W
```

Logcat — системный операционный sink за пределами процесса. Local JSONL можно выгрузить через Diagnostics. Для корпоративной централизованной доставки журналов потребуется отдельная интеграция; она не нужна для автономного MVP.

## Проверка

```bash
./scripts/gradle.sh :app:assembleAcceptance :app:assembleAcceptanceAndroidTest
./scripts/adb.sh devices -l
python3 scripts/test_instrument.py
./scripts/instrument.sh DEVICE_SERIAL artifacts/help-tests.txt com.ahwotel.MetricHelpAcceptanceTest,com.ahwotel.TestIsolationAcceptanceTest
```

Для целевой проверки подсказок передайте третий аргумент `com.ahwotel.MetricHelpAcceptanceTest`; он передаётся runner как фильтр класса. Пример: `./scripts/instrument.sh DEVICE_SERIAL artifacts/help-tests.txt com.ahwotel.MetricHelpAcceptanceTest`. Ресурсные тесты: `./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.MetricHelpTest`.

Замените `DEVICE_SERIAL` идентификатором из `adb devices`. Скрипт проверяет итог `OK (N tests)`, поскольку Android instrumentation может вернуть exit code 0 даже при падении теста. Для остальных adb-команд при нескольких устройствах также укажите `-s DEVICE_SERIAL`.

С версии 0.2.2 instrumentation работает только в отдельном приложении **AHWOTel Test / AHWOTel Тест** (`com.ahwotel.acceptance`); тестовый runner — `com.ahwotel.acceptance.test/com.ahwotel.SafeTestRunner`. `scripts/instrument.sh` сам устанавливает оба acceptance APK после проверки их manifest. Нужны Python 3 и SDK Command-line Tools (`apkanalyzer`). Проверка только артефактов: `./scripts/instrument.sh DEVICE_SERIAL --verify-only`. Передача обычного APK или неправильного target завершается отказом до подключения к устройству. Внутри runner target повторно проверяется до создания Application. Старый режим instrumentation для `com.ahwotel` не поддерживается; ранее установленные тестовые APK старых версий не использовать.

Реальный TalkBack проверяется отдельно: на эмуляторе API 35 установите и включите TalkBack с рабочим TTS, закройте его начальное обучение и запустите `./scripts/instrument.sh emulator-5554 artifacts/talkback.txt com.ahwotel.TalkBackHelpAcceptanceTest`. Тест подаёт клавиатурные команды TalkBack через Android 15 accessibility input filter и проверяет переход по восьми разделам EN/RU и возврат фокуса после закрытия. При отсутствии сервиса сценарий завершается ошибкой. Общий запуск без фильтра исключает этот сценарий с внешними зависимостями; обычные UI-тесты выполняйте с выключенным TalkBack.

Обычный APK устанавливается отдельно как `com.ahwotel`; тесты не запускают его и не меняют его данные. Нужен разблокированный Android/эмулятор; screen-off сценарий переводит тестовое устройство в сон и будит его. Настройки языка и размера шрифта самой Android-системы менять только на эмуляторе и восстанавливать после проверки.

Проверка сохранности P1 выполняется только на эмуляторе с пустым, уже инициализированным профилем обычного приложения: `python3 scripts/check-test-isolation.py emulator-5554 prepare`, затем целевые тесты через `scripts/instrument.sh`, затем `python3 scripts/check-test-isolation.py emulator-5554 verify`. Скрипт создаёт синтетический замер возрастом 30 дней, сессию, очередь OTLP и retention 90 дней. Непустой исходный профиль и физические устройства для создания fixture отклоняются. Снимки сохраняются в `artifacts/v0.2.2/isolation`; повторный независимый прогон требует нового `--evidence` каталога и пустого тестового профиля. Для очереди используется несуществующий `.invalid` endpoint и отложенная попытка отправки.

Сценарии — [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md), результаты 0.2.2 — [docs/VALIDATION-0.2.2.md](docs/VALIDATION-0.2.2.md), 0.2.1 — [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md), 0.2.0 — [docs/VALIDATION-0.2.0.md](docs/VALIDATION-0.2.0.md), исходного MVP — [docs/VALIDATION.md](docs/VALIDATION.md). Репозиторий: https://github.com/igorlyapin-max/ahwotel .

Исходное ТЗ: `TZ_Android_Performance_Monitoring.txt`. Production credentials, реальная SOTI compatibility matrix, OEM battery policy, production thresholds и signing остаются отдельным этапом. Legacy не поддержана/не запланирована для Android ниже 8 и исторических форматов конфигурации.

## Battery and Self Telemetry (code 8)

В настройках добавлены отдельные разделы «Батарея» и «Телеметрия приложения». Сбор и отправка имеют независимые интервалы; внешний коллектор использует прежние общий переключатель OTLP и endpoint. По умолчанию история хранится 14 дней, Self Telemetry включается вместе со сбором, отправка остаётся выключенной. На экранах мониторинга и истории доступны новые графики, EN/RU-подсказки и JSON/CSV-экспорт.

Подробные правила измерения, доступности и ограничений: [battery-self-telemetry.md](docs/battery-self-telemetry.md). Проверки и установленная сборка: [VALIDATION-battery-self-code8.md](docs/VALIDATION-battery-self-code8.md).

## Review corrections (code 9)

Исправлены загрузка старых настроек OTLP, срок хранения отложенных пакетов, задержка отправки OEM, расчёты Self Telemetry и разряда батареи, effective config hash, полнота длинных графиков, подписи состояний и навигация Back. Старые данные не пересчитываются. Очередь сохраняется до более позднего срока: createdAt + queueHours или dueAt + 1 час; лимит байтов остаётся действующим. Контракт: [battery-self-telemetry.md](docs/battery-self-telemetry.md).

Результаты исправлений и проверки обновления: [VALIDATION-review-code9.md](docs/VALIDATION-review-code9.md).

## Review corrections (code 10)

Исправлены разрастание задач OTLP, восстановление параметров CSV-экспорта после пересоздания Activity, запуск профилей только с Батареей или Телеметрией приложения, учёт первого захвата и границ наблюдения WakeLock. Обновлены русские подсказки. Эти изменения вошли в установленную на Samsung поставку code11; результаты устройства приведены в [отдельном отчёте](docs/VALIDATION-samsung-code11.md).

Результаты и оставшиеся проверки: [VALIDATION-review-code10.md](docs/VALIDATION-review-code10.md).

## Review corrections (code 13)

В OTEL-стенде временной диагностикой управляют supervisor-процессы внутри Compose: срок действует после reboot, настройки адресов/портов/retention не откатываются. Таблицы Grafana используют точное время наблюдения и сохраняют отдельные источники Android/Knox. Полная справка EN/RU открывается по ссылке «Подробнее» и читается на мобильном экране.

APK восстанавливает сохранённые HTTPS endpoint с портом `0` или выше `65535`, принятые старой версией: отключает OTLP, сохраняет адрес для исправления и показывает уведомление на языке интерфейса. Локальный сбор и история остаются доступны. Строгая проверка нового ввода сохранена; общий fallback повреждённых настроек не добавлен. Проверки: [VALIDATION-review-code13.md](docs/VALIDATION-review-code13.md).
