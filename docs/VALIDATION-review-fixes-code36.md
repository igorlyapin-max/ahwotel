# Исправления review — code36

2026-11-02, рабочее дерево после `730d013`. VERSION остаётся `00.00.00.15`; versionCode повышен до 36. Commit/tag/push не выполнялись.

## Пять исправлений

1. Binding обновляется через `OtlpSender.updateIdentity` под общей блокировкой generation/регистрации вызовов: отмена запросов, сохранение binding и сброс кеша сертификата не позволяют новому send захватить новую generation со старой привязкой. Ошибка commit оставляет отмену в силе и возвращает отказ применения. Binding-only обновление сохраняет очередь.
2. Извлечение правила применяет только rule к актуальному draft, сохраняя delegated и остальные свежие настройки. Идентификатор операции и смена alias/правила/режима/управления инвалидируют устаревший результат; CancellationException не превращается в ошибку сертификата.
3. Локальный Check connection использует отдельный preview с reference alias: те же проверки правила, ключа, сроков, server CA и hostname, без выдачи grants и без кеширования managed identity. Production resolver по-прежнему требует managed binding.
4. Лабораторный entrypoint сохраняет исходный путь без преждевременного resolve. Symlink каталога/предка/.runtime отклоняется до генерации ключей; требования приватности каталога и запрет перезаписи сохранены.
5. Runner создаёт уникальный run manifest, передаёт runId в Android и отмечает успех только после успешного завершения instrumentation. Android удаляет старый test-owned результат и публикует полный набор Room-точек новой сессии. Checker требует `--run`, совпадение run/serial/endpoint, успешный запуск и все значения/timestamps соответствующей серии Prometheus; старые форматы не принимаются. Перед проверкой удаляется прежний PASS artifact.

## Проверки

- JVM **31/31 PASS**: MtlsTest 11, CertificateRuleTest 4, HttpPolicyTest 3, DeploymentProfileTest 7, TransportTest 6. Новые регрессии: отправка блокируется на незавершённом commit identity, отказ commit, сохранение актуальных полей draft и отклонение изменённого alias/режима/критериев. Существующая регрессия отмены во время key lookup сохранена.
- Python **18/18 PASS**: test_mtls_lab.py 7, test_mtls_delivery.py 3, test_instrument.py 2, test_otel_lab.py 6. Проверены обход symlink через штатный lab CLI, старый/неуспешный запуск, неправильный serial/endpoint/сессия, неполный набор, изменённое значение и положительный полный набор.
- Debug/Acceptance/AcceptanceAndroidTest сборки и lintDebug PASS; 1580 EN/RU строк и `git diff --check` PASS. Полный suite проекта не запускался.
- Android API 35: итоговый прогон **8/8 PASS**: MtlsAcceptanceTest, DeploymentProfileAcceptanceTest, TaskBoundaryAcceptanceTest. Реальная установка PKCS#12 и выбор KeyChain; локальный rule preview успешно отправил запрос в MockWebServer; production resolver с тем же локальным правилом без binding вернул `tls_binding_missing`. Managed режим использовал настоящую привязку ключа; проверки очереди, EN/RU и возврата из системных окон проходят.
- Новая сессия Android → KeyChain/mTLS → отдельный Collector → Prometheus: новый checker подтвердил **все 6 исходных RAM-точек**, значения и timestamps совпали. Проверка неуспешного первого запуска дала `unsuccessful_run`, без старого PASS.

В первых Android-прогонах выявлены проблемы тестового harness: кнопка preview после раскрытия правила находилась ниже экрана; последующие прогоны сталкивались с оставшимся installer-диалогом и растущим списком alias. Harness теперь ждёт актуальный UI, прокручивает кнопку с assertIsDisplayed, использует постоянный собственный alias, очищает старый installer-процесс только на эмуляторе и ограничивает password selector пакетом certinstaller. Expected result не ослаблялся.

Доказательства, исключённые из Git: `artifacts/review-fixes36/final.txt`, `final.run.json`, `apk.json`, `artifacts/mtls/native-keychain-prometheus.json`; JVM XML/lint в `app/build/`. Первые неуспешные прогоны не считаются положительной приёмкой.

## APK и runtime

APK `com.ahwotel`, `00.00.00.15 / code36`, SHA-256 `ae18cceda11b89f6d04931323742d853d5810c2e4b8c9a19654df37b2794d6a2`.

Единственное доступное ADB-устройство — emulator-5554: install -r PASS, версия проверена, запуск Status: ok, процесс жив, FATAL EXCEPTION в его crash buffer нет. Физических, offline и unauthorized устройств не было. Перезагрузки не выполнялись.

Основной стенд остаётся None на прежних портах/маршрутах. Отдельный тестовый Collector остановлен, reverse tcp:14318 удалён после проверки. Схемы portable profile 4 и Room не менялись. Off/Basic/временный Verbose, structured stdout/stderr, Logcat и JSONL сохранены; тестовый профиль проверяет сбор с Basic/file logging. Секреты не добавлены в диагностику.

`../aspm/4-reply.csv` обновлён по ID 120211 с доказательствами исправления lab entrypoint; исходный порядок/ID, принятый риск HTTP и требование патчей ОС сохранены. Все статусы остаются PENDING_ASPM_RESCAN. Реальное SOTI delegation, корпоративная PKI и пилот на двух физических устройствах остаются отдельной непроверенной приёмкой.
