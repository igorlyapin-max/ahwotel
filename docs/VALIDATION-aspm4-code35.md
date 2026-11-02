# ASPM 4.csv — code35

Проверено 2026-11-02. База: `730d01329c791486a4bf31475bd4fb976172c752`, отчёт заказчика engagement 2144. Все три строки High разобраны; статусы в системе заказчика не изменялись. Ответ: `../aspm/4-reply.csv`, UTF-8 BOM, `;`, CRLF, исходные ID и порядок сохранены.

## Решения

| ID | Результат |
| --- | --- |
| 114496 | ACCEPTED_RISK по решению владельца продукта. HTTP сохранён с явным allowHttp, выключенным по умолчанию. Решение владельца продукта не заменяет принятие исключения ИБ заказчика. |
| 114497 | HARDENED_OS_PATCH_REQUIRED: taskAffinity пустой, allowTaskReparenting=false, стандартный launch mode сохранён. minSdk=26/targetSdk=36. Это не полное исправление StrandHogg 2.0 на непропатченной старой ОС. |
| 120211 | FALSE_POSITIVE_HARDENED: доверенный локальный оператор намеренно выбирает каталог вывода. Добавлены проверки пути/приватности и запрет перезаписи; этот инструмент не обрабатывает сетевой ввод. |

См. [EN](mtls.md) / [RU](mtls-ru.md): транспорт, патчи ОС, требования к каталогу генерации. Для генерации нужны доверенные родители; защита от локального процесса, одновременно подменяющего родителей, не заявляется. Фиксированные имена файлов и проверка приватного каталога не превращают CLI в файловую песочницу.

## Проверки

- `python3 scripts/test_mtls_lab.py`: 6/6 PASS. Проверены symlink каталога/предка/dangling link, файл вместо каталога, `..`, непустой каталог и отсутствие перезаписи, права и владелец POSIX, отказ до вызова OpenSSL без записи, реальная генерация через CLI, `otel_lab.py certs` и приватный tempfile (как в mTLS smoke).
- `test_otel_lab.py` 6/6, `test_instrument.py` 2/2 PASS. Итого 14 Python-тестов.
- `assembleDebug`, `assembleAcceptance`, `assembleAcceptanceAndroidTest`, `lintDebug`: PASS. Реальная Gradle-задача `generateMtlsTestAssets` выполнилась с усиленным генератором.
- JVM: HttpPolicyTest 3/3, MtlsTest 9/9, TransportTest 6/6 — 18/18 PASS. Полный suite не запускался.
- API 35, `emulator-5554`: TaskBoundaryAcceptanceTest 2/2 и KeyChain enrollment/selection 1/1 — **3/3 PASS**. Проверены запуск из launcher, открытие через настоящий PendingIntent уведомления действующей сессии, открытие и отмена системных export/import диалогов с возвратом в Settings; реальная установка тестового PKCS#12 и mTLS с выбранным ключом через MockWebServer. Успешное сохранение/чтение документа через системный picker этим прогоном не проверялось.
- Merged manifest подтверждает пустой taskAffinity, запрет reparenting, usesCleartextTraffic=true, minSdk=26, targetSdk=36. Проверка версии 2 атаки на непропатченной Android 8–10 не выполнялась.
- Локализация: 1579 EN/RU строк PASS; `git diff --check` PASS.
- Диагностика сохранена: Off по умолчанию, Basic/временный Verbose, structured stdout/stderr, Logcat и вращаемый JSONL. В Android-прогоне Basic включён в изолированном тестовом профиле; события видны в Logcat, JSONL-файлы созданы. Нового вывода ключей/паролей нет.

Локальные доказательства: `artifacts/aspm4/android.txt`, `artifacts/aspm4/apk.json`, JVM XML/lint в `app/build/`; generated материалы и ключи не входят в Git.

## APK и устройства

`com.ahwotel`, versionName `00.00.00.15`, versionCode **35**. VERSION не повышался: commit/tag/push не запрошены.

Debug APK SHA-256: `ea20b1960b800d223f625eec6b82602ac0298f048d42dfdfe930b8c76e7acd9b`.

Единственный доступный ADB device — `emulator-5554`: `install -r` PASS, установленная версия проверена, `am start -W` вернул `Status: ok`, процесс жив, в его crash buffer нет `FATAL EXCEPTION`. Физических устройств, offline и unauthorized в списке не было. Перезагрузки не выполнялись.

Стенд и контейнеры не перезапускались; полный сетевой mTLS smoke Collector → Prometheus в этой задаче не повторялся. Изменений схем профилей/Room и четырёх ранее открытых P2 нет. Все строки ответа требуют `PENDING_ASPM_RESCAN`; риск HTTP и требование патчей ОС не объявлены исправленными.
