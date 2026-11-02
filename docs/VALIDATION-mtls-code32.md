# mTLS: проверка code32

Дата: 2026-11-02. APK: `versionName=00.00.00.14`, `versionCode=32`; это рабочая сборка поверх Git release 14, без создания нового тега.

## Реализованный контракт

- None по умолчанию; mTLS только по HTTPS, строгие серверные CA/hostname и клиентские EKU/сроки/доступ к ключу. Системный выбор KeyChain либо управляемый alias. Серверный CA импортируется отдельно по SHA-256, через UI или `tls_ca_bundle` Android managed configuration.
- Portable profile schema 3: mode, alias, trust, trustRef; без ключей/сертификатов/паролей и identity установки. Старые схемы не применяются; сохранённое управление можно явно освободить повышенной revision.
- Локальный сбор сохраняется при ошибке ключа; очередь ограничена прежними квотами/повторами. Изменения mode/alias/trust/endpoint очищают очередь до сохранения новой политики. Продление под тем же alias не очищает очередь; каждый запрос читает ключ/цепочку заново и использует отдельное соединение.
- Отмена учитывает также запросы, которые ещё загружают ключ: завершившееся позднее чтение KeyChain не отправит пакет со старыми настройками. Проверка покрывает эту гонку отдельно.
- Коллектор стенда переключается None ↔ mTLS на том же порту; в mTLS-профиле параллельного HTTP receiver нет. `up --collector-only` сохраняет работающие Prometheus/Grafana и volumes.

Инструкции и ограничения: [RU](mtls-ru.md), [EN](mtls.md).

## Автоматические проверки

| Проверка | Результат | Область |
| --- | --- | --- |
| `assembleDebug`, `assembleAcceptance`, `assembleAcceptanceAndroidTest`, `lintDebug` | PASS | Android-сборки, Android lint |
| JVM `MtlsTest`, `DeploymentProfileTest`, `CollectionPolicyTest`, `TransportTest`, `HttpPolicyTest` | 29 PASS | TLS, trust/hostname/назначение/сроки, отсутствие downgrade, профиль/очередь/отмена |
| `test_mtls_lab.py`, `test_otel_lab.py`, `test_instrument.py` | 10 PASS | PKI/None без ключей, конфигурация стенда, изоляция instrumentation |
| `check-locales.py` | PASS, 1563 строки EN/RU | Соответствие ключей/подстановок, существующая полнота справочника метрик |
| `MtlsAcceptanceTest`, `DeploymentProfileAcceptanceTest` на API 35 | 5 PASS | Реальный KeyChain, native TLS, смена очереди без остановки сбора, EN/RU и read-only управление |

Команда целевых JVM-проверок:

```bash
./scripts/gradle.sh :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest :app:lintDebug :app:testDebugUnitTest \
  --tests com.ahwotel.MtlsTest --tests com.ahwotel.DeploymentProfileTest \
  --tests com.ahwotel.CollectionPolicyTest --tests com.ahwotel.TransportTest \
  --tests com.ahwotel.HttpPolicyTest
```

Полный набор тестов проекта, многодневный soak и новая производственная PKI в этот прогон не входят.

## Настоящая доставка и серверные отказы

1. Основной Collector → Prometheus → Grafana был запущен с сохранением volumes. В None `otel-lab.sh smoke` подтвердил четыре потока APK, 22 ряда, числовые значения и исходные timestamps.
2. Для основного OTLP receiver включён mTLS. `mtls_lab_smoke.py` подтвердил принятие правильного клиента и отказ: без сертификата, с неизвестным УЦ клиента, с serverAuth вместо clientAuth, с неизвестным УЦ сервера, с неверным hostname и по обычному HTTP. Protobuf APK снова дал 22 ряда в Prometheus.
3. Основной вход возвращён в исходный None. Во время тестового переключения HTTP-запросы прежнего профиля отклонялись; это не доказывает непрерывность backend-истории за этот интервал. Локальная история на устройствах не изменялась.
4. Для Android создан отдельный временный Collector `ahwotel-mtls-native-test`: loopback `127.0.0.1:14318`, сеть основного стенда, тот же Prometheus. Использованы серверный ключ/сертификат и публичный УЦ из `app/build/generated/mtls-test-assets/mtls`, group 1000 для чтения ключа, пользователь `10001:10001`, собственный volume очереди. Ключ УЦ не монтировался. ADB reverse предоставил эмулятору тот же адрес с подходящим SAN.
5. Изолированный acceptance APK установил временный P12 через системные диалоги **VPN/apps**, выбрал сертификат через KeyChain и получил доступ к настоящему Android-ключу. Инъекция TLS identity в этом сценарии не использовалась. Сначала проверен обмен с локальным TLS server, затем реальный шестисекундный сбор RAM, отправка через WorkManager и mTLS Collector.
6. В Prometheus найдены все шесть точек этого Android-сеанса; первая точка совпала с числовым `memoryAvailable` и исходным временем в Room. Совпадение проверено через range query с `device_id` и `monitoring_session_id`, а не по одному HTTP 200. После прогона отдельный Collector и ADB reverse остановлены.

Команда Android-прогона после подготовки этого отдельного Collector:

```bash
./scripts/adb.sh -s emulator-5554 reverse tcp:14318 tcp:14318
./scripts/instrument.sh emulator-5554 artifacts/mtls/emulator-final.txt \
  com.ahwotel.MtlsAcceptanceTest,com.ahwotel.DeploymentProfileAcceptanceTest \
  --mtls-lab-endpoint https://127.0.0.1:14318/v1/metrics
python3 scripts/check-mtls-android.py emulator-5554
```

Без `--mtls-lab-endpoint` тесты не выполняют дополнительную отправку в реальный Collector; локальные native TLS/KeyChain/UI проверки сохраняются. Тест установки PKCS#12 ограничен эмулятором и пропускается на физических устройствах. При повторной сборке сертификатов сервер должен использовать именно актуальный тестовый CA и серверный сертификат из assets текущего тестового APK.

Generated evidence (игнорируется Git):

- `artifacts/otel-lab/wire-smoke.json`, `wire-smoke-mtls.json`, `mtls-security-smoke.json`;
- `artifacts/mtls/emulator-final.txt`, `native-keychain-prometheus.json`, `apk-smoke.json`;
- `app/build/test-results/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`.

## Debug/logging и устройства

Off по умолчанию сохранён. На mTLS-стенде проверено включение Basic и возврат Off без изменения кода. Фактический operational sink всех трёх компонентов — Docker `local`; structured stdout/stderr и непривилегированные пользователи проверены через Docker inspect/logs. В APK используются прежние stdout/stderr, Logcat и JSONL; новые TLS-ошибки передают только диагностические коды, без PEM/паролей. В обычном APK отсутствуют тестовые credentials; они находятся только в изолированном instrumentation APK.

Все ADB-visible `device` на момент проверки: `emulator-5554`. Основной `com.ahwotel` обновлён через `adb install -r`; подтверждены code32/versionName, запуск и отсутствие `FATAL EXCEPTION` в focused logcat.

Samsung `R9PT90TS6ZL` виден как USB MTP, но интерфейс ADB отсутствует. На нём новая версия не установлена, EKU/доступность конкретного корпоративного сертификата и SOTI grant не подтверждены. Нужны USB debugging и доверие компьютеру; затем установка code32, проверка KeyChain из основного APK и отдельная проверка выдачи доступа SOTI. Физические устройства не перезагружались.
