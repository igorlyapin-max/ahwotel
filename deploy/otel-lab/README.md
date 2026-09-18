# AHWOTel: локальный стенд без аутентификации

Collector Contrib 0.160.0 → Prometheus 3.14.0 → Grafana 13.2.1. Базовые образы и Go builder закреплены по digest в `images.env`. `up` собирает локальные производные образы code13 с небольшим supervisor; установка Go, systemd и доступ контейнеров к Docker socket не нужны. Устройства отправляют OTLP HTTP/protobuf; Grafana предоставляет анонимный Viewer. Сертификаты и учётная запись для просмотра не нужны. Профиль предназначен для доверенной локальной сети.

## Запуск

Нужны Docker Engine, Docker Compose >= 2.24.4 и Python 3.10+. Текущие лимиты контейнеров: Collector 256 MiB, Prometheus 2 GiB, Grafana 1 GiB — суммарно 3.25 GiB. Предусмотрите также память для Docker/ОС и несколько GiB диска. Это ограничения, а не измеренное потребление; лимиты Prometheus и Grafana настраиваются в `.env`.

Из корня репозитория:

```bash
cp deploy/otel-lab/.env.example deploy/otel-lab/.env
# В .env задайте LAB_BIND_ADDRESS: адрес LAN-интерфейса этого компьютера.
./scripts/otel-lab.sh up
./scripts/otel-lab.sh status
```

Скрипт выводит адреса. По умолчанию:

- APK: `http://<LAN-IP>:4318/v1/metrics`.
- Grafana: `http://<LAN-IP>:3000/d/ahwotel-en` и `/d/ahwotel-ru`.
- Prometheus: `http://127.0.0.1:9090`, доступен локально; Grafana использует `prometheus:9090` внутри Docker.
- Collector health: `http://127.0.0.1:13133/`.

gRPC receiver не включён. Адрес LAN задаётся явно; после изменения DHCP обновите `.env` и endpoint APK. Разрешите в локальном firewall входящий TCP на выбранные порты Collector/Grafana. Wi-Fi client isolation может мешать телефону обращаться к компьютеру.

На нашем snap Docker `no-new-privileges` вызывает `exec ...: operation not permitted` даже для `collector --version`. Только для такого хоста задайте `LAB_SNAP_DOCKER=1`: подключится явный `compose.snap-docker.yaml`. Обычный профиль сохраняет этот параметр. Непривилегированные runtime-пользователи и `cap_drop: ALL` остаются в обоих профилях. Владелец volume очереди инициализируется одноразовым контейнером от root; рабочие сервисы от root не запускаются.

## Настройка APK code12

1. Settings / Настройки → OTLP.
2. Включите **Allow HTTP for a test lab / Разрешить HTTP для тестового стенда**.
3. Введите полный endpoint из вывода `up`, включите OTLP и сохраните настройки.
4. Запустите мониторинг; в Grafana выберите устройство, сессию и период.

По умолчанию язык APK — English, HTTP и отправка OTLP выключены. HTTPS проверяет сертификат сервера; автоматического downgrade нет. Смена endpoint или выключение OTLP очищает очередь отправки, сохраняя историю. Измерения, созданные при выключенном OTLP, задним числом не выгружаются.

Базовые измерения отправляются согласно их настройке. У Battery/Self независимые окна и интервалы: штатно 300 секунд; время до первой отправки может включать и накопление окна, и задержку отправки. Износ измеряется и отправляется реже. WorkManager также зависит от сети и планировщика Android. Проверяйте локальную очередь, время наблюдения и панели Collector.

```bash
./scripts/adb.sh -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
./scripts/adb.sh -s <serial> shell am start -n com.ahwotel/.MainActivity
```

Обновление завершает процесс APK. После обновления начните новую сессию, если шёл сбор; настройки и история сохраняются. Испытания выполняйте изолированным acceptance APK.

## Контракт данных

Путь метрик: APK → Collector `/v1/metrics` → Prometheus `/api/v1/otlp/v1/metrics`. Prometheus scrape Collector используется только для служебных метрик. Исходные timestamps и точки одного пакета сохраняются, включая поступившие вне очереди в пределах настроенного окна.

HTTP 200 подтверждает приём Collector. Постоянная очередь экспортера повторяет доставку после восстановления Prometheus; окончательное сохранение проверяется в Prometheus. Перед постоянной очередью нет batch processor с отдельным буфером в памяти. Очередь конечна; переполнение или ограничение памяти требуют повторов со стороны APK. Доставка не является exactly-once; одинаковые точки могут приходить повторно.

Настройки хранения: `RETENTION_DAYS=14`, `RETENTION_SIZE=2GB`, `OUT_OF_ORDER_WINDOW=24h`. Ограничение TSDB не является квотой всего диска: WAL, очередь и логи занимают место отдельно. Окно опоздания согласуйте с очередью APK. Неверные часы устройства исправляйте на устройстве; Collector не подменяет время измерений.

Имена переводятся через `UnderscoreEscapingWithSuffixes`: `agent.cpu.runqueue_wait` / `ms` → `agent_cpu_runqueue_wait_milliseconds`. Для единицы `1` переводчик добавляет `_ratio`; смысл исходного статуса/счётчика от этого не меняется. Оконные `.sum`/`.count` приложения остаются gauges; `rate()` к ним не применяется.

Collector исключает `window.start` и `window.duration_ms` из labels; они остаются в локальной истории APK. Сохраняются сегмент, сессия, источник, качество, статус и component. Сумма на графике означает сумму за окно настроенной длительности. Динамические resource-атрибуты — PID, screen/doze/network/config hash — не копируются целиком в labels; сохраняются устройство, модель, ОС, версия агента, окружение и сессия.

Недоступное числовое значение не заменяется нулём. Таблицы availability/capability показывают статус, причину и время последнего наблюдения в выбранном периоде. Старое наблюдение не доказывает текущую доступность. Для редких показателей расширяйте период. Grafana отображает результат PromQL с шагом запроса и lookback; подробная исходная история доступна в CSV/JSON APK. Для сравнения конкретного запуска выбирайте его сессию.

EN/RU дашборды устанавливаются provisioning, анонимное изменение запрещено. Основные описания метрик берутся из ресурсов APK. Для изменения: отредактируйте `scripts/build-lab-dashboards.py`, выполните `python3 scripts/build-lab-dashboards.py`; Grafana перечитывает файлы автоматически.

Таблицы преобразуют метки в колонки через [Labels to fields](https://grafana.com/docs/grafana/latest/visualizations/panels-visualizations/query-transform-data/transform-data/); формат даты применяется только к числовому времени наблюдения через [field override](https://grafana.com/docs/grafana/latest/visualizations/panels-visualizations/configure-overrides/). Технические названия показателей и статусы остаются текстом.

## Операции и диагностика

```bash
./scripts/otel-lab.sh logs
./scripts/otel-lab.sh debug Basic
./scripts/otel-lab.sh debug Verbose --minutes 10
./scripts/otel-lab.sh debug Off
./scripts/otel-lab.sh down
```

`down` сохраняет volumes. Удаление истории автоматически не выполняется. Смена диагностики корректно перезапускает процессы внутри контейнеров с коротким перерывом; очереди и TSDB сохраняются. `Verbose` ограничен 1–30 минутами, по умолчанию 10. Каждый supervisor проверяет общую политику при старте и раз в секунду; Off применяется не позднее 15 секунд после срока, включая graceful shutdown до 10 секунд. При запуске после reboot истёкшая политика сразу означает Off; внутри процесса срок дополнительно ограничен монотонными часами.

`debug` атомарно меняет только `.runtime/policy/debug.json`; адреса, порты и retention меняются через `.env` и `up`. Снимок окружения таймеру не передаётся. Политика подключается read-only; статус каждого supervisor хранится в отдельном tmpfs и виден через `status` как requested/effective/expires/error. Повреждённая или отсутствующая политика означает Off. При первой установке code13 создаётся Off; старый host timer не поддерживается. Ошибка дочернего процесса завершает supervisor, после чего Docker restart policy восстанавливает контейнер.

`LAB_PROJECT_NAME` задаёт имя Compose-проекта и его постоянной очереди. Для существующего стенда оставьте `ahwotel-lab`, чтобы сохранить прежние volumes. Изолированные проверки используют отдельную копию конфигурации через `AHWOTEL_LAB_DIR`, другое имя проекта и свободные порты.

Таблицы доступности выбирают точное последнее наблюдение в диапазоне `(начало, конец]` отдельно для каждого источника. Используется `ts_of_last_over_time`; закреплённый Prometheus запускается с `--enable-feature=promql-experimental-functions`. При переносе dashboards на другой Prometheus этот контракт обязателен, приближённого legacy-запроса нет. Android и Knox не заменяют друг друга. Подробная справка доступна по ссылке «Подробнее» / Read more на `/d/ahwotel-help-ru` и `/d/ahwotel-help-en`; восемь разделов берутся из точных ключей APK.

Structured JSON идёт в stdout/stderr. Локальный overlay задаёт Docker logging driver `local`: три файла по 10 MiB на сервис, доступ через `logs`. Основной Compose не навязывает driver; эксплуатация может задать свой overlay. Полный OTLP payload и серверный error_message в логах APK не печатаются.

APK разбирает `ExportMetricsServiceResponse`: пустой ответ и пустой `partial_success` — успех; ненулевое число отклонённых точек — частичная доставка без повторной отправки. Неверный ответ или размер свыше 64 KiB не считается доставкой, фиксируется `otlp_response_invalid`.

## Проверки

Профильные offline checks: `python3 scripts/test_otel_lab.py`; Go tests выполняются при сборке образов. `scripts/otel_lab_regression.py` проверяет реальное истечение TTL, изменение конфигурации, восстановление, повреждённую политику и границы PromQL. Он намеренно отказывается работать с основным стендом: требуется `AHWOTEL_LAB_DIR` с отдельной копией и `LAB_PROJECT_NAME`, начинающимся с `ahwotel-code13-test`. Тест использует loopback-порты 14000/14001, 14318/14319, 19090/19091 и 23133/23134; предварительно проверьте, что они свободны. Результат — `artifacts/code13/lab-regression.json`.

```bash
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.LabWireFixtureTest --rerun-tasks
./scripts/otel-lab.sh smoke
# Краткая остановка только Prometheus этого стенда и перезапуск Collector:
./scripts/otel-lab.sh smoke --restart-queue
./scripts/instrument.sh <serial> artifacts/otel-lab/android-http.txt com.ahwotel.HttpLabAcceptanceTest --lab-endpoint http://<LAN-IP>:4318/v1/metrics
```

Fixtures создаются сериализаторами APK. Устройства `wire-*` — синтетические тестовые данные. Подтверждения: `artifacts/otel-lab/wire-smoke.json`. Перед независимым повтором создайте свежие fixtures; точка проверки восстановления отличается от уже доставленных точек. Повтор `smoke --restart-queue` на тех же fixtures не является новым доказательством восстановления.

Браузерная проверка: установите Playwright в окружение инструментов, задайте `PLAYWRIGHT_MODULE` (путь к `index.mjs`), `GRAFANA_URL`, при необходимости `BROWSER_EXECUTABLE`; выполните `node scripts/check-lab-grafana.mjs`. Стандартный браузер — `/usr/bin/google-chrome`. Проверяются EN/RU, геометрия видимых панелей, ошибки запросов и числовые данные. `LAB_DEVICE_ID`, `LAB_SESSION_ID`, `LAB_FROM`, `LAB_TO` выбирают реальное устройство и период. После первой отправки Battery/Self добавьте `LAB_REQUIRE_SLOW=1`: проверка потребует числовые заряд/температуру/PSS и сохранит отдельные скриншоты этих разделов. Скриншоты и результаты — `artifacts/otel-lab/`.

`node scripts/check-lab-help.mjs` с теми же `GRAFANA_URL`/`PLAYWRIGHT_MODULE` проверяет CPU/Thermal на EN/RU, desktop 1440×1000 и mobile 390×844: размеры tooltip, восемь разделов выбранного показателя, чтение последнего раздела и переход клавиатурой. Результаты — `artifacts/code13/help/`; каталог можно задать через `LAB_EVIDENCE_DIR`.

Источники: [Prometheus OTLP](https://prometheus.io/docs/guides/opentelemetry/), [Collector OTLP HTTP exporter](https://github.com/open-telemetry/opentelemetry-collector/blob/main/exporter/otlphttpexporter/README.md), [OTLP responses](https://opentelemetry.io/docs/specs/otlp/), [Grafana anonymous access](https://grafana.com/docs/grafana/latest/setup-grafana/configure-access/configure-authentication/anonymous-auth/).

## Recovery code15

`PROMETHEUS_MEMORY_LIMIT=2g` задаёт начальный лимит памяти Prometheus; формат — положительное целое с суффиксом `m` или `g`. Это не гарантия ёмкости для произвольного парка: проверяйте реальные series, RSS и восстановление WAL. `status` показывает memory usage/limit, restart count, image ID и очередь Collector, возвращает ненулевой код при недоступности компонентов, метрик очереди или её заполнении.

Локальный `segment` исключён из backend labels: иначе каждая смена контекста создаёт новые self-telemetry series. Поле сохраняется в Room/экспорте APK; device/session/source/scope и timestamps передаются. Исторические series не удаляются принудительно. Supervisor пишет числовой exit code и сигнал завершившегося процесса через structured stderr; Off/Basic/ограниченный Verbose и Docker local logging overlay сохраняются.

Суточная проверка после восстановления и запуска телефона:

```bash
python3 scripts/otel_lab_soak.py --device-id DEVICE_ID --hours 24
```

Скрипт только наблюдает: раз в минуту проверяет runtime identity/restarts, очередь, свежесть RAM-точек (не старше 5 минут) и Grafana. Итог `RUNNING` не равен `PASS`; ошибки и изменение runtime делают результат `FAIL`. Текущий каталог по умолчанию — `artifacts/recovery-code16/soak/`; прежний прогон code15 хранится отдельно. При ином интервале отправки требуется соответствующий отдельный профиль наблюдения; этот gate рассчитан на тестовый Samsung с RAM каждые 2 секунды и upload interval 0.

## Recovery code16

`GRAFANA_MEMORY_LIMIT=1g` заменяет прежние фиксированные 384 MiB. На реальном EN/RU dashboard-прогоне старого лимита оказалось недостаточно: ядро завершило Grafana по cgroup OOM. Значение настраивается в `.env`, допустимый формат — положительное целое с `m` или `g`, как у Prometheus. После смены лимита нужен `up` для применения Compose-конфигурации и новый полный soak; старые наблюдения сохраняются отдельно.

Наблюдатель учитывает пересоздание контейнера с тем же образом и внутренний перезапуск child process при изменении диагностики. Identity: `container_id`, `started_at`, `image_id`, `restarts`, `child_generation`. Supervisor увеличивает generation при каждом успешном запуске child и сохраняет её при обновлении diagnostic status. Отсутствующая generation считается ошибкой; legacy supervisor не принимается как успешно проверенный runtime.

`runtime_status()` сохраняет частично доступные данные и безопасные `errors` без исключения на обычную недоступность компонента. CLI `status` печатает результат и возвращает ошибку; soak сохраняет результат вместе с конкретными причинами, продолжая независимые проверки других endpoint. Structured payload/секреты в диагностику не включаются.

Для нового 24-часового прогона используйте пустой каталог `--output`; по умолчанию это `artifacts/recovery-code16/soak/`. Пересборка/перезапуск сервисов требуют нового прогона с нуля. Регрессии наблюдателя без воздействия на стенд: `python3 scripts/test_otel_lab_soak.py`.
