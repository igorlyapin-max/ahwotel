# Правила mTLS — code33

Проверка 2026-11-02. Рабочий APK `00.00.00.14 / code33`, portable schema 4. Git-тег не создавался. Инструкции: [RU](mtls-ru.md), [EN](mtls.md).

## Подтверждено

- Сборки debug/acceptance/instrumentation и `lintDebug` проходят. 24 целевых JVM-теста: CertificateRuleTest, MtlsTest, DeploymentProfileTest, CollectionPolicyTest. Полный suite проекта не запускался.
- OpenSSL генерирует два разных индивидуальных сертификата с общим SAN URI назначения. Проверены совпадение общего правила для обоих, AND с издателем, настоящие расширения template OID и certificate policy OID, отрицательные OID, повреждённый DER, неверные сроки/назначение, контролируемая выдача доступа и отзыв нового grant при неподходящем сертификате.
- Экспорт schema 4 не содержит reference-device alias в режиме правила. Проверены отдельная MDM-привязка, смена alias без изменения profile revision, несовпадающая revision, отклонение неприменённого макроса SOTI и восстановление старых локальных настроек в ручной режим.
- На `emulator-5554`, API 35: **6 Android-сценариев PASS**. Редактор правила редактируется и сохраняется на EN/RU; экспорт сохраняет правило и исключает уникальный alias. Проверены read-only управление, локальный сбор при недоступном ключе и сохранение очереди при обновлении привязки.
- Настоящий ключ установлен через системный PKCS#12 installer и выбран через KeyChain. Управляемый профиль с SAN URI правилом и отдельным alias использует этот ключ для mTLS. Затем managed RUNNING/STOPPED управляют настоящим сбором RAM; WorkManager отправляет измерения через отдельный mTLS Collector в основной Prometheus. Проверено совпадение исходного времени и числового значения Room с Prometheus, получены 6 точек. В этой Android-проверке ключ заранее предоставлен системным выбором; это **не** доказательство реального делегирования от SOTI.
- Проверка локализации: 1579 EN/RU строк, совпадающие placeholders и полнота справочника метрик. `git diff --check` проходит.
- Off/Basic/временный Verbose и существующие structured stdout/stderr, Logcat, JSONL сохранены. Новые ошибки содержат диагностические коды без alias, PEM, паролей и Subject/SAN. Роль и привязка не добавляют сбор инвентаря приложений или QUERY_ALL_PACKAGES.

Команды:

```bash
./scripts/gradle.sh :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest :app:lintDebug :app:testDebugUnitTest \
  --tests com.ahwotel.CertificateRuleTest --tests com.ahwotel.MtlsTest \
  --tests com.ahwotel.DeploymentProfileTest --tests com.ahwotel.CollectionPolicyTest
./scripts/instrument.sh emulator-5554 artifacts/mtls-rules/android-final.txt \
  com.ahwotel.MtlsAcceptanceTest,com.ahwotel.DeploymentProfileAcceptanceTest \
  --mtls-lab-endpoint https://127.0.0.1:14318/v1/metrics
python3 scripts/check-mtls-android.py emulator-5554 --run artifacts/mtls-rules/android-final.run.json
```

Для последней команды нужен описанный в проверке code32 отдельный loopback Collector с сертификатами **текущего** тестового APK и ADB reverse. Основной стенд остаётся в None. После теста временный Collector и перенаправление остановлены. Generated evidence: `artifacts/mtls-rules/android-final.txt`, `artifacts/mtls/native-keychain-prometheus.json`, JVM XML/lint отчёты в `app/build/`.

Примечание code36: показанная команда обновлена под новый checker. Исторический прогон code33 не создавал run manifest и не является доказательством для нового формата; требуется новый запуск instrumentation.

## Ограничения приёмки

У пользователя SOTI **2026.2.2.1001**, собственный агент Device Owner. Подстановка `%CertificateTemplate_<Macro-Template-Name>%`, назначение Certificate Selection выбранным способом доставки APK, настоящий self-grant и отсутствие влияния на VPN/SSO **не проверены в этой среде**. До такого пилота массовый автоматический режим не считается принятым. Делегированный Android API покрыт контролируемым unit-тестом; версия Android для self-grant — 11+.

Два разных сертификата проверены в JVM; прогона на двух физических устройствах не было. Samsung отсутствует в ADB. Обновляется только доступный `device` — эмулятор; для Samsung требуется USB debugging. Перезагрузки физических устройств не выполнялись. Старые portable-профили schema 1–3 нужно переэкспортировать; это отличается от автоматического восстановления локальных настроек.
