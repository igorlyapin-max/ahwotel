# Проверка локального OTLP-стенда и APK code12

Дата: 2026-09-14. Версия APK: `00.00.00.02`, `versionCode=12`. Проверялся текущий локальный diff; публикация в Git в эту задачу не входит.

## Стенд и контракт

Collector Contrib 0.160.0 → Prometheus 3.14.0 → Grafana 13.2.1. Образы закреплены по digest в `deploy/otel-lab/images.env`. Рабочие адреса этого компьютера:

- OTLP: `http://192.168.202.35:4318/v1/metrics`, auth None.
- Grafana: `http://192.168.202.35:3000/d/ahwotel-ru` и `/d/ahwotel-en`, anonymous Viewer.
- Prometheus: `http://127.0.0.1:9090`, Collector health: `http://127.0.0.1:13133`.

Инструкция воспроизведения: [deploy/otel-lab/README.md](../deploy/otel-lab/README.md). Адрес LAN задаётся в `.env`, не зашит в APK. HTTP разрешается отдельной настройкой и по умолчанию выключен; HTTPS сохраняет проверку сертификата. mTLS/OIDC не входят в этот профиль.

Проверены запуск и готовность трёх сервисов, непривилегированные runtime-пользователи, постоянные volumes, structured JSON stdout/stderr и Docker `local` logging driver из явного overlay. Basic включается конфигурацией; временный Verbose автоматически вернулся в Off после минутного интервала. Итоговый режим — Off. Доказательства: `artifacts/otel-lab/basic-runtime.json`, `deploy/otel-lab/.runtime/debug-expiry.log`.

Для snap Docker этого хоста потребовался `LAB_SNAP_DOCKER=1`: стандартный `no-new-privileges` приводил к `operation not permitted` уже на `collector --version`. Исключение подключено отдельным overlay; non-root и `cap_drop: ALL` сохранены. Это ограничение упаковки Docker данного стенда.

## Автоматические проверки

| Проверка | Результат |
| --- | --- |
| CoreTest, HttpPolicyTest, OtlpResponseTest, TransportTest, LabWireFixtureTest | 22 JVM tests, 0 failures/errors |
| Сборки debug, acceptance, acceptance androidTest | passed |
| `:app:lintDebug` | passed |
| `scripts/check-locales.py` | 1291 EN/RU strings; 22 базовых + 60 OEM показателей × 8 разделов |
| Защита instrumentation helper | 2 Python tests passed; только `com.ahwotel.acceptance` |
| Collector config validate, Python/Node/shell syntax, `git diff --check` | passed |
| API 35 emulator: HttpLabAcceptanceTest | 2 tests passed |
| API 35 emulator: UploadAcceptanceTest | 1 test passed |
| Samsung SM-J260F, Android 8.1 / API 27: HttpLabAcceptanceTest | 2 tests passed, 55.999 s |

HTTP-сценарий проходит через UI включения разрешения, сохранение, настоящий UploadWorker и HTTP mock server. Проверяется отсутствие Authorization. Отзыв разрешения выключает OTLP и очищает ожидающую очередь, не удаляя локальную историю. Другой сценарий собирает 30 секунд реальных RAM/CPU-probe/storage наблюдений и отправляет их рабочим потоком в LAN Collector. Battery/Self в коротком acceptance-сценарии выключены: у них независимые, более длинные окна.

Новый response decoder проверяет пустой OTLP-ответ, пустой `partial_success`, отклонённые точки, повреждённый protobuf и ограничение 64 KiB. Реальный Collector возвращает `0a00` при полном успехе; APK теперь корректно распознаёт этот ответ.

Логи: `artifacts/otel-lab/android-http-rerun.txt`, `android-upload-regression.txt`, `samsung-http.txt`. Ранний неуспешный запуск эмулятора сохранён в `android-http.txt`; окончательный запуск выполнен после исправления доступности Collector и исключения независимых от сценария отложенных Battery/Self пакетов.

## Данные на всём пути

`LabWireFixtureTest` создаёт пакеты настоящими сериализаторами APK. Smoke доставил base, Battery/Wear, Self и OEM, включая gzip и точки вне порядка. Проверены исходные timestamps, значения, одна серия для последовательных окон и отсутствие числового нуля вместо UNSUPPORTED. Smoke остановил только Prometheus стенда, принял отдельный пакет, перезапустил Collector и подтвердил доставку из постоянной очереди после восстановления Prometheus. `artifacts/otel-lab/wire-smoke.json`: 22 серии, `durableQueueRestart=true`. Устройства `wire-*` содержат синтетические значения.

Реальное измерение эмулятора: `artifacts/otel-lab/prometheus-native.json`, 15 точек. Реальное измерение Samsung: `artifacts/otel-lab/samsung-prometheus-native.json`, 15 точек. Для Samsung проверено точное совпадение локального значения RAM `255389696` bytes и времени `1789374434129` ms с Prometheus; тестовое устройство `208b14d6-f8b7-457f-a00f-c24f87097af5`, сессия `fd979c3e-dd81-43c6-9def-b39dcd52f461`.

Chrome/Playwright открыл EN/RU dashboards без авторизации, проверил видимость и геометрию панелей, числовые RAM frames выбранного устройства/сессии, запросы всех разделов после прокрутки. Ошибок запросов данных нет. Ожидаемый 401 персонального `/api/user/stars` для anonymous Viewer исключён отдельно. Артефакты: `grafana-browser.json`, `grafana-{en,ru}.png`, `grafana-{en,ru}-pipeline.png`, соответствующие frames в `artifacts/otel-lab/`.

После доставки Battery/Self браузерная проверка дополнительно требует числовые заряд, температуру и PSS выбранной сессии. Сохраняются `grafana-{en,ru}-{battery,self,availability}.png`. В таблице доступности формат даты применяется только к времени наблюдения; текстовые колонки сохраняют metric/status/reason/source. Исправлено обнаруженное визуальной проверкой преобразование строк в `NaN`. Проверка требует видимые заголовки, названия показателей и отсутствие `NaN`; порядок строк Prometheus не фиксируется. Y-оси получили достаточную ширину, шкала хранилища — четыре десятичных знака; заряд ограничен 0–100%. График результатов отправки выбирает только `.sum` для success/failure/retry и показывает имена серий.

Системная загрузка CPU и thermal status этого Samsung недоступны; графики оставляют отсутствие наблюдений. Косвенные probe CPU wait и scheduling delay отображаются отдельно. Наличие графика не доказывает причинную связь с деградацией. Для slow metrics нужно учитывать окно, задержку отправки, выбранный период и время последнего наблюдения.

## Обновление Samsung

До тестов основной `com.ahwotel` продолжал сессию `cfd88905-976e-455c-b288-4e2c8897f1d3`, PID `12626`. Native instrumentation выполнялась только в `com.ahwotel.acceptance`. После тестов основной PID и файл настроек оставались прежними. Часы Samsung соответствовали 2026-09-14; активна Wi-Fi сеть `192.168.202.0/24`.

Старая сессия остановлена через UI с `manual_stop`. Согласованная резервная копия `artifacts/otel-lab/samsung-main-stopped-verified` прошла `PRAGMA integrity_check=ok`: 1 сессия, 20273 базовых замера, 18710 записей Battery/Self, пустая очередь. При первой попытке force-stop Android повторно запустил Activity, поэтому копия пересеклась с checkpoint и была отвергнута. Перед повтором выполнены Home, force-stop и проверка отсутствия PID; исходная база не восстанавливалась и не ремонтировалась. Несогласованные копии `samsung-main-before` и `samsung-main-stopped` не считаются резервными копиями.

Установлен `artifacts/AHWOTel-00.00.00.02-code12-debug.apk` через `adb install -r`. SHA-256 локального и установленного APK:

`043ff26bdf12bf261645a3607c8df8f84ed02daf5d4319fdb2a6ac6116a19ed7`.

После холодного запуска повторный согласованный снимок `samsung-main-installed` подтвердил сохранность всех исторических строк, файла настроек и `shared_prefs/installation.xml`. Разрешены только штатные изменения расписания, SQLite locale и времени проверки OEM capabilities. Доказательство: `artifacts/otel-lab/samsung-upgrade/preservation.json`.

Через UI включены HTTP и OTLP, сохранён endpoint стенда. Все прежние ключи настроек, кроме endpoint/включения OTLP, проверены на равенство: Русский, интервал 2 секунды, непрерывный режим, сбор при выключенном экране, хранение 14 дней, выбранные сборщики и параметры Battery/Self сохранены. Сам файл настроек после этого ожидаемо изменился.

Новая сессия `e371ee08-94cf-41fe-8dc4-e514d17f8b49`, основной device ID `ba41bea0-8c98-4031-a310-e95b875d8f9e`, PID `30599`. После Home подтверждены тот же процесс, foreground service, уведомление `id=42`, прежние настройки и 25 точек RAM за 47.94 секунды в Prometheus. Согласованный снимок работающей сессии сохранил старую историю и содержал 38 новых базовых замеров, 12 записей Battery и 76 Self. Метаданные новых записей содержат `agent.build=12`. Доказательства: `samsung-upgrade/result.json`, `prometheus-after-home.json`, `final-snapshot.json`, `runtime-main-log.txt`, `resumed.png`.

В этом профиле первый локальный пакет Battery/Self запланирован на отправку через 300 секунд; начальное окно CPU содержит `BASELINE` без числового значения. Такая ожидающая очередь не означает ошибку HTTP. После наступления срока подтверждены три точных совпадения с локальной базой: `device_battery_level_percent`, `device_battery_temperature_celsius`, `agent_memory_pss_bytes`. Исходные timestamps сохранены; метаданные локальных записей содержат build 12. Отдельно проверена доставка `agent.cpu.percent` со статусом `UNAVAILABLE`, причиной `BASELINE`. Доказательство: `artifacts/otel-lab/samsung-slow-prometheus.json`, включая путь к согласованному локальному снимку.

Числовая оценка Self CPU требует последующего окна с двумя чтениями; первый пакет не использовался как подтверждение такой оценки. После отправки следующего окна отдельно подтверждено точное совпадение `agent_cpu_percent=10.14356727168408` и timestamp `1789375351240` ms с локальной записью: `artifacts/otel-lab/samsung-self-cpu-prometheus.json`. Это замер в ходе проверки, а не оценка постоянных накладных расходов приложения. Износ/паспорт имеют суточные/недельные интервалы и сохраняют прежнее расписание. OEM в основном профиле выключен; его транспорт проверен пакетами сериализатора, получение лицензируемых Knox показателей на этом устройстве не заявляется.

Сбор основной сессии и стенд оставлены работающими. Настройка телефона `stay_on_while_plugged_in` восстановлена в исходное значение `0`. Финальные runtime image IDs, уровни диагностики, volumes и log sinks: `artifacts/otel-lab/final-runtime.json`. Стенд предназначен для доверенной LAN; этот результат не является проверкой production-аутентификации или внешнего collector.
