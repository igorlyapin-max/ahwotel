#!/usr/bin/env python3
"""Generate bilingual metric catalogue and complete help from reviewed metric definitions."""
from pathlib import Path
from xml.sax.saxutils import escape
root=Path(__file__).resolve().parents[1]
# name, wire suffix, unit, group, aggregation, English title, Russian title, interpretation EN/RU
rows=[
('LEVEL','battery.level','%', 'BATTERY','GAUGE','Battery level','Уровень заряда','Remaining charge as a percentage; compare discharge only under similar load.','Оставшийся заряд в процентах; сравнивайте разряд только при сопоставимой нагрузке.'),
('TEMP','battery.temperature','Cel','BATTERY','GAUGE','Battery temperature','Температура батареи','Heating can change charging speed and accelerate wear; temperature alone does not measure wear.','Нагрев может менять скорость зарядки и ускорять износ; температура сама по себе не измеряет износ.'),
('VOLTAGE','battery.voltage','V','BATTERY','GAUGE','Battery voltage','Напряжение батареи','Voltage depends on charge and load. Compare voltage drops with load and charge level.','Напряжение зависит от заряда и нагрузки. Сопоставляйте просадки с нагрузкой и уровнем заряда.'),
('CURRENT','battery.current','uA','BATTERY','GAUGE','Battery current','Ток батареи','Device battery current; positive means charging in the Android API. Unknown OEM scaling is rejected.','Ток всей батареи; положительное значение в Android API означает зарядку. Неизвестный масштаб OEM отклоняется.'),
('CHARGE','battery.charge_counter','uAh','BATTERY','GAUGE','Remaining charge','Остаточный заряд','Remaining charge, not full or design capacity. Zero at a positive battery level is invalid.','Остаточный заряд, а не полная или проектная ёмкость. Ноль при положительном уровне заряда некорректен.'),
('HEALTH','battery.health','1','BATTERY','STATE','Battery diagnostic status','Диагностический статус батареи','Android health: 2 Good, 3 Overheat, 4 Dead, 5 Over voltage, 6 Failure, 7 Cold. Good is not SOH.','Статус Android: 2 исправна, 3 перегрев, 4 неисправна, 5 перенапряжение, 6 сбой, 7 холод. Исправность не является SOH.'),
('STATUS','battery.status','1','BATTERY','STATE','Charging status','Состояние зарядки','Android status: 2 Charging, 3 Discharging, 4 Not charging, 5 Full.','Статус Android: 2 зарядка, 3 разрядка, 4 не заряжается, 5 полный заряд.'),
('PLUG','battery.charging_source','1','BATTERY','STATE','Power source','Источник питания','Android bit mask: 0 unplugged, 1 AC, 2 USB, 4 wireless; connections reset drain windows.','Маска Android: 0 отключено, 1 сеть, 2 USB, 4 беспроводная зарядка; смена питания сбрасывает окно разряда.'),
('CHARGING','battery.charging','1','BATTERY','STATE','Charging or full','Заряжается или заряжена','1 means charging or full, 0 otherwise; external power also prevents drain estimation.','1 означает зарядку или полный заряд, 0 иначе; внешнее питание также исключает оценку разряда.'),
('POWER_SAVE','battery.power_save','1','BATTERY','STATE','Power saving','Энергосбережение','1 means Android power saving is active; compare resource usage within the same power mode.','1 означает активное энергосбережение Android; сравнивайте расход ресурсов в одинаковом режиме.'),
('CYCLES','battery.cycle_count','1','WEAR','GAUGE','Charge cycles','Циклы зарядки','Controller or Android reported cycles; counts can reset after replacement or firmware changes.','Циклы по данным контроллера или Android; счётчик может сбрасываться после замены или обновления.'),
('SOH','battery.soh','%','WEAR','GAUGE','State of health','Остаточное здоровье SOH','Reported or validated full/design capacity ratio; unavailable without a verified source.','Значение источника или проверенное отношение полной и проектной ёмкости; без проверенного источника недоступно.'),
('FULL','battery.full_charge_capacity','uAh','WEAR','GAUGE','Full charge capacity','Ёмкость полного заряда','Estimated controller capacity at full charge; only compare with design capacity using the same units.','Оценка контроллером ёмкости при полном заряде; сравнивайте с проектной ёмкостью в одинаковых единицах.'),
('DESIGN','battery.design_capacity','uAh','PASSPORT','GAUGE','Design capacity','Проектная ёмкость','Manufacturer/controller design capacity. It does not measure current battery condition.','Проектная ёмкость по данным производителя или контроллера; не измеряет текущее состояние.'),
('TECHNOLOGY','battery.technology','1','PASSPORT','STATE','Battery technology','Технология батареи','Reported chemistry such as Li-ion. This descriptive value is not a wear score.','Сообщаемый тип, например Li-ion. Это описание, а не оценка износа.'),
('DRAIN_PERCENT','battery.drain.percent_hour','%/h','BATTERY','GAUGE','Device discharge rate','Скорость разряда устройства','Percentage points lost per hour over at least 15 minutes without power; not agent energy use.','Потеря процентных пунктов в час за окно не менее 15 минут без питания; не энергопотребление агента.'),
('DRAIN_CHARGE','battery.drain.mah_hour','mAh/h','BATTERY','GAUGE','Device charge drain','Расход заряда устройства','Charge counter decline per hour; each increase or missing value restarts its 15-minute window independently of the percentage trend. Charging and timing gaps reset both trends.','Уменьшение счётчика заряда в час; каждый рост или пропуск значения запускает его 15-минутное окно заново, независимо от процентного тренда. Питание и разрывы времени сбрасывают оба тренда.'),
('CPU_TIME','cpu.time','ms','CPU','GAUGE','Process CPU time','CPU-время процесса','Cumulative CPU time of all process threads since process start; resets on restart.','Суммарное CPU-время всех потоков с запуска процесса; сбрасывается при перезапуске.'),
('CPU_DELTA','cpu.delta','ms','CPU','SUM','CPU time in interval','CPU-время за интервал','CPU time consumed between valid observations; excludes sleep and waiting.','CPU-время между корректными наблюдениями; исключает сон и ожидание.'),
('CPU_FOREGROUND','cpu.foreground','ms','CPU','SUM','Foreground CPU time','CPU-время на переднем плане','Process CPU between observed activity transitions while monitoring is active.','CPU процесса между наблюдаемыми переходами Activity во время мониторинга.'),
('CPU_BACKGROUND','cpu.background','ms','CPU','SUM','Background CPU time','CPU-время в фоне','Process CPU while no activity is resumed; a foreground service is still background here.','CPU процесса без активной Activity; служба переднего плана здесь всё ещё относится к фону.'),
('WAKE_AVERAGE','wakelock.average_duration','ms','RUNTIME','GAUGE','Average completed WakeLock hold','Среднее завершённое удержание WakeLock','Mean of completed observed holds, clipped at timeout; a still-held lock has no completed duration.','Среднее завершённых наблюдаемых удержаний до истечения срока; незавершённая блокировка не входит в среднее.'),
('SELF_UPLOAD','self.upload_bytes','By','TASKS','SUM','Self payload bytes sent','Отправленные байты Self Telemetry','Self payload bytes attempted during an active session; includes retries, excludes HTTP and TLS overhead.','Байты данных телеметрии приложения при попытках отправки во время сессии; включают повторы, исключают HTTP и TLS.'),
('CPU_PERCENT','cpu.percent','%','CPU','GAUGE','Estimated process CPU','Оценка CPU процесса','100 × CPU delta / elapsed time; 100% equals one core, multithreading can exceed 100%.','100 × дельта CPU / прошедшее время; 100% соответствует одному ядру, многопоточность может дать больше.'),
('CPU_MINUTE','cpu.ms_per_minute','ms/min','CPU','GAUGE','CPU ms per minute','CPU мс в минуту','CPU delta normalized to one minute of observed elapsed time; not an additional counter.','Дельта CPU, нормированная на минуту наблюдения; не дополнительный счётчик.'),
('CPU_HOUR','cpu.ms_per_hour','ms/h','CPU','GAUGE','CPU ms per hour','CPU мс в час','CPU delta normalized to one hour; a short sample is a rate estimate, not a full hour measurement.','Дельта CPU, нормированная на час; короткое наблюдение даёт оценку скорости, а не замер за час.'),
('PSS','memory.pss','By','MEMORY','GAUGE','Proportional memory','Пропорциональная память PSS','Private memory plus a proportional share of shared pages; useful for comparing process footprint.','Приватная память и пропорциональная доля общих страниц; полезна для сравнения памяти процесса.'),
('RSS','memory.rss','By','MEMORY','GAUGE','Resident memory','Резидентная память RSS','Resident process pages including shared pages; do not add RSS and PSS.','Резидентные страницы процесса, включая общие; RSS и PSS не складываются.'),
('JAVA_USED','memory.java_used','By','MEMORY','GAUGE','Java heap used','Использование памяти Java','Allocated Java heap minus free heap; sawtooth changes can reflect garbage collection.','Выделенная память Java за вычетом свободной; пилообразная динамика может отражать сборку мусора.'),
('JAVA_MAX','memory.java_max','By','MEMORY','GAUGE','Java heap limit','Лимит памяти Java','Runtime maximum heap; it is not a limit for all native and shared process memory.','Максимум памяти Java в среде выполнения; не является лимитом всей нативной и общей памяти процесса.'),
('NATIVE','memory.native_used','By','MEMORY','GAUGE','Native heap used','Использование нативной памяти','Native heap allocator usage; excludes some mapped and graphics memory.','Использование нативного аллокатора; не включает часть отображённой и графической памяти.'),
('OOM','memory.oom_count','1','MEMORY','SUM','Confirmed OOM exits','Подтверждённые завершения OOM','Only confirmed exit reasons count. A restart alone is not proof of OOM.','Учитываются только подтверждённые причины завершения. Сам перезапуск не доказывает OOM.'),
('GC_COUNT','memory.gc_count','1','MEMORY','GAUGE','GC count','Количество GC','Runtime reported garbage collections since process start; collection activity is not automatically a problem.','Количество сборок мусора по данным среды выполнения с запуска; сама активность GC не означает проблему.'),
('GC_TIME','memory.gc_time','ms','MEMORY','GAUGE','GC duration','Длительность GC','Runtime reported collection time since process start; scope depends on Android runtime support.','Время GC по данным среды выполнения с запуска; область измерения зависит от поддержки Android.'),
('PRESSURE','memory.pressure_events','1','MEMORY','SUM','Memory pressure callbacks','События давления памяти','Callbacks received by this app, not all device-wide pressure events.','Обратные вызовы, полученные приложением, а не все события давления памяти устройства.'),
('TX','network.tx','By','NETWORK','GAUGE','UID bytes sent','Отправлено UID','TrafficStats bytes attributed to the application UID; includes traffic outside telemetry uploads.','Байты TrafficStats для UID приложения; включают трафик помимо отправки телеметрии.'),
('RX','network.rx','By','NETWORK','GAUGE','UID bytes received','Получено UID','Received UID bytes; unavailable traffic counters do not mean zero traffic.','Полученные байты UID; недоступный счётчик не означает нулевой трафик.'),
('TX_DELTA','network.tx_delta','By','NETWORK','SUM','UID bytes sent in interval','Отправлено UID за интервал','Difference between valid TX counters; a reset breaks the baseline.','Разность корректных счётчиков TX; сброс требует нового исходного отсчёта.'),
('RX_DELTA','network.rx_delta','By','NETWORK','SUM','UID bytes received in interval','Получено UID за интервал','Difference between valid RX counters; payload bytes are reported separately.','Разность корректных счётчиков RX; байты передаваемых данных учитываются отдельно.'),
('FOREGROUND','runtime.foreground','ms','RUNTIME','SUM','Foreground time','Время на переднем плане','Elapsed time with a resumed activity during collection; screen state is separate.','Время с активной Activity во время сбора; состояние экрана учитывается отдельно.'),
('BACKGROUND','runtime.background','ms','RUNTIME','SUM','Background time','Время в фоне','Elapsed collection time without a resumed activity; can overlap foreground service time.','Время сбора без активной Activity; может пересекаться со временем службы переднего плана.'),
('FG_SERVICE','runtime.foreground_service','ms','RUNTIME','SUM','Foreground service time','Время службы переднего плана','Elapsed time of the monitoring service during observation; not CPU execution time.','Время службы мониторинга за наблюдение; не CPU-время выполнения.'),
('WAKE_COUNT','wakelock.acquire_count','1','RUNTIME','SUM','WakeLock acquisitions','Захваты WakeLock','Observed acquisitions of the agent-owned partial wakelock. Enabling observation during a hold is not another acquisition.','Наблюдаемые захваты собственного WakeLock агента, удерживающего CPU. Включение учёта посреди удержания не считается новым захватом.'),
('WAKE_DURATION','wakelock.duration','ms','RUNTIME','SUM','WakeLock held time','Время удержания WakeLock','Observed hold time clipped to timeout and release; disabled observation periods and time after timeout are excluded.','Наблюдаемое удержание до освобождения или истечения срока. Время отключённого учёта и время после истечения срока не включаются.'),
('WAKE_MAX','wakelock.max_duration','ms','RUNTIME','MAX','Longest WakeLock hold','Максимум удержания WakeLock','Longest observed hold in the window; warnings are configurable product thresholds.','Максимальное наблюдаемое удержание в окне; пороги предупреждений настраиваются.'),
('WAKEUPS','scheduler.wakeups','1','RUNTIME','SUM','Confirmed device wakeups','Подтверждённые пробуждения','Requires a verified source for actual device wakeups. Job execution is not a wakeup.','Требует проверенного источника реальных пробуждений. Выполнение задачи не равно пробуждению.'),
('DB_SIZE','storage.database','By','STORAGE','GAUGE','Database size','Размер базы данных','Database plus WAL and SHM files; physical storage can remain allocated after deleting records.','База плюс WAL и SHM; физическое место может оставаться выделенным после удаления записей.'),
('BUFFER','storage.buffer','By','STORAGE','GAUGE','Pending payload bytes','Размер очереди отправки','Serialized payload bytes waiting for upload; bounded by queue policy.','Байты сериализованных данных в очереди; ограничены политикой очереди.'),
('PENDING','storage.pending','1','STORAGE','GAUGE','Pending packets','Пакеты в очереди','Number of packets awaiting upload, not number of individual measurements.','Количество пакетов, ожидающих отправки, а не отдельных измерений.'),
('DROPPED','storage.dropped','1','STORAGE','SUM','Dropped records or packets','Удалённые записи или пакеты','Count removed by quota or queue expiry, labelled by component; not successful uploads.','Удалённые по квоте или сроку очереди записи, с указанием компонента; не успешные отправки.'),
('CALLS','task.calls','1','TASKS','SUM','Operation executions','Выполнения операций','Count of instrumented operation calls, grouped by a fixed component name.','Число инструментированных вызовов с группировкой по фиксированному имени компонента.'),
('DURATION','task.duration','ms','TASKS','GAUGE','Operation duration','Длительность операции','Elapsed operation duration; includes waiting, and is not attributed CPU time.','Полная длительность операции; включает ожидание и не является CPU-временем.'),
('ERRORS','task.errors','1','TASKS','SUM','Operation failures','Ошибки операций','Failed operations by safe error code; cancelled work is counted separately.','Неудачные операции по безопасному коду ошибки; отмены учитываются отдельно.'),
('GENERATED','task.generated_bytes','By','TASKS','SUM','Generated bytes','Созданные байты','Serialized output bytes of an operation; excludes protocol overhead.','Байты сериализованного результата; исключают протокольные накладные расходы.'),
('SAMPLES','task.samples','1','TASKS','SUM','Collected measurements','Собранные измерения','Measurements produced by an instrumented collector; source failures can reduce the count.','Измерения инструментированного сборщика; отказы источников могут уменьшать количество.'),
('SCHEDULED','scheduler.scheduled','1','RUNTIME','SUM','Scheduled jobs','Запланированные задачи','Expected monitoring executions in the observed window.','Ожидаемые выполнения мониторинга за окно наблюдения.'),
('EXECUTED','scheduler.executed','1','RUNTIME','SUM','Executed jobs','Выполненные задачи','Actual monitored scheduler executions; compare with scheduled and missed counts.','Фактические выполнения планировщика; сравнивайте с запланированными и пропущенными.'),
('MISSED','scheduler.missed','1','RUNTIME','SUM','Missed intervals','Пропущенные интервалы','Whole intervals skipped due to scheduler delay; paused monitoring is excluded.','Целые интервалы, пропущенные из-за задержки; паузы мониторинга исключаются.'),
('DELAYED','scheduler.delayed','1','RUNTIME','SUM','Delayed jobs','Задержанные задачи','Executions more than 100 ms late relative to the monotonic deadline.','Выполнения с задержкой более 100 мс относительно монотонного срока.'),
('DELAY','scheduler.delay','ms','RUNTIME','GAUGE','Scheduler delay','Задержка планировщика','Actual start minus intended monotonic start; measures scheduling delay, not system CPU utilization.','Фактический старт минус плановый монотонный срок; не измеряет системную загрузку CPU.'),
('CANCELLED','task.cancelled','1','TASKS','SUM','Cancelled operations','Отменённые операции','Explicit cancellation of instrumented operations; not an execution failure.','Явная отмена инструментированных операций; не ошибка выполнения.'),
('UPLOADS','upload.count','1','TASKS','SUM','Upload attempts','Попытки отправки','HTTP attempts, including retries; separate from successful deliveries.','Попытки HTTP, включая повторные; учитываются отдельно от успешных доставок.'),
('UPLOAD_OK','upload.success','1','TASKS','SUM','Successful uploads','Успешные отправки','Fully successful OTLP responses; partial success is not counted as full delivery.','Полностью успешные ответы OTLP; частичный успех не считается полной доставкой.'),
('UPLOAD_FAIL','upload.failure','1','TASKS','SUM','Failed uploads','Неудачные отправки','Transport failures, rejected requests and partial success; see safe diagnostic codes.','Ошибки транспорта, отклонённые запросы и частичный успех; смотрите безопасные коды диагностики.'),
('UPLOAD_RETRY','upload.retry','1','TASKS','SUM','Upload retries','Повторные отправки','Attempts after an earlier failure, identified from the persisted retry count.','Попытки после предыдущей ошибки по сохранённому счётчику повторов.'),
('RAW','upload.raw_bytes','By','TASKS','SUM','Uncompressed payload','Несжатые данные','Protobuf bytes before compression, not actual transmitted UID traffic.','Байты protobuf до сжатия, а не фактический трафик UID.'),
('COMPRESSED','upload.compressed_bytes','By','TASKS','SUM','Compressed payload','Сжатые данные','Gzip payload size, excluding HTTP and TLS overhead.','Размер данных gzip без накладных расходов HTTP и TLS.'),
('RATIO','upload.compression_ratio','1','TASKS','GAUGE','Compression ratio','Коэффициент сжатия','Compressed / raw payload bytes; lower values mean greater size reduction.','Байты сжатых / исходных данных; меньшее значение означает большее уменьшение размера.'),
('SELF_DURATION','self.collection_duration','ms','TASKS','SUM','Self Telemetry execution time','Время выполнения телеметрии приложения','Measured own operation duration including aggregation and persistence; avoids recursive events.','Собственная длительность, включая агрегацию и запись; рекурсивные события исключаются.'),
('SELF_CPU','self.cpu','ms','TASKS','SUM','Self Telemetry CPU','CPU телеметрии приложения','Thread CPU for synchronous self sampling; asynchronous persistence CPU cannot be attributed exactly.','CPU потока синхронного опроса; CPU асинхронной записи нельзя точно приписать компоненту.'),
('SELF_RECORDS','self.records','1','TASKS','SUM','Self Telemetry records','Записи телеметрии приложения','Count of self records persisted in previous windows; not recursively measured record by record.','Число записей телеметрии приложения из предыдущих окон; без рекурсивного учёта каждой записи.'),
('SELF_BYTES','self.bytes','By','TASKS','SUM','Self Telemetry bytes','Байты телеметрии приложения','Serialized self data produced by previous windows; transport bytes reported separately.','Сериализованные данные телеметрии приложения из предыдущих окон; транспортные байты учитываются отдельно.'),
('SELF_OVERHEAD','self.cpu_fraction','%','CPU','GAUGE','Measured self CPU fraction','Измеренная доля CPU телеметрии приложения','Measured self CPU / process CPU delta. Partial scope: asynchronous work is excluded; not proof of the complete 5% budget.','Измеренное CPU-время телеметрии приложения / дельта CPU процесса. Неполная область: асинхронная работа исключена; не доказывает бюджет 5% целиком.'),
]
rows += [('CURRENT_AVERAGE',
  'battery.current_average',
  'uA',
  'BATTERY',
  'GAUGE',
  'Average battery current',
  'Средний ток батареи',
  'Signed device current averaged by the controller; the averaging window is firmware dependent. Positive '
  'means charging.',
  'Средний ток всей батареи; окно усреднения определяет прошивка. Положительное значение означает зарядку.'),
 ('ENERGY',
  'battery.energy',
  'nWh',
  'BATTERY',
  'GAUGE',
  'Remaining battery energy',
  'Остаточная энергия батареи',
  'Remaining energy from BatteryManager, not design energy or state of health.',
  'Остаточная энергия BatteryManager, а не проектная энергия или остаточное здоровье.'),
 ('CHARGE_TIME',
  'battery.charge_time_remaining',
  'ms',
  'BATTERY',
  'GAUGE',
  'Time to full charge',
  'Время до полного заряда',
  'Android prediction while charging. A missing prediction is not zero; compare predictions only under '
  'stable charging conditions.',
  'Прогноз Android во время зарядки. Отсутствие прогноза не означает ноль; сравнивайте при стабильных '
  'условиях зарядки.'),
 ('CHARGING_DETAIL',
  'battery.charging_detail',
  '1',
  'BATTERY',
  'STATE',
  'Extended charging status',
  'Режим зарядки Android',
  'Android 14 charging status: 1 normal, 2 too cold, 3 too hot, 4 long life, 5 adaptive. Unknown codes '
  'remain unavailable.',
  'Расширенный статус Android 14: 1 обычный, 2 слишком холодно, 3 слишком горячо, 4 долговечность, 5 '
  'адаптивный. Неизвестные коды недоступны.'),
 ('IO_READ_BYTES',
  'storage.io.read_bytes',
  'By',
  'STORAGE',
  'GAUGE',
  'Storage bytes read',
  'Прочитано с накопителя',
  'Process /proc/self/io read_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io read_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_READ_BYTES_DELTA',
  'storage.io.read_bytes.delta',
  'By',
  'STORAGE',
  'SUM',
  'Storage bytes read in interval',
  'Прочитано с накопителя за интервал',
  'Process /proc/self/io read_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io read_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_READ_BYTES_RATE',
  'storage.io.read_bytes.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Storage bytes read per second',
  'Прочитано с накопителя в секунду',
  'Process /proc/self/io read_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io read_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_WRITE_BYTES',
  'storage.io.write_bytes',
  'By',
  'STORAGE',
  'GAUGE',
  'Storage bytes written',
  'Записано на накопитель',
  'Process /proc/self/io write_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io write_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_WRITE_BYTES_DELTA',
  'storage.io.write_bytes.delta',
  'By',
  'STORAGE',
  'SUM',
  'Storage bytes written in interval',
  'Записано на накопитель за интервал',
  'Process /proc/self/io write_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io write_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_WRITE_BYTES_RATE',
  'storage.io.write_bytes.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Storage bytes written per second',
  'Записано на накопитель в секунду',
  'Process /proc/self/io write_bytes. Storage writes are accounted when pages become dirty; cancelled writes '
  'are separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io write_bytes. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_CANCELLED_WRITE_BYTES',
  'storage.io.cancelled_write_bytes',
  'By',
  'STORAGE',
  'GAUGE',
  'Cancelled write bytes',
  'Отменённая запись',
  'Process /proc/self/io cancelled_write_bytes. Storage writes are accounted when pages become dirty; '
  'cancelled writes are separate. Logical bytes include cache and non-disk I/O; syscall counts are not '
  'hardware IOPS.',
  'Счётчик процесса /proc/self/io cancelled_write_bytes. Запись учитывается при изменении страниц; '
  'отменённая запись отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны '
  'аппаратным IOPS.'),
 ('IO_CANCELLED_WRITE_BYTES_DELTA',
  'storage.io.cancelled_write_bytes.delta',
  'By',
  'STORAGE',
  'SUM',
  'Cancelled write bytes in interval',
  'Отменённая запись за интервал',
  'Process /proc/self/io cancelled_write_bytes. Storage writes are accounted when pages become dirty; '
  'cancelled writes are separate. Logical bytes include cache and non-disk I/O; syscall counts are not '
  'hardware IOPS. A new baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io cancelled_write_bytes. Запись учитывается при изменении страниц; '
  'отменённая запись отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны '
  'аппаратным IOPS. После перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_CANCELLED_WRITE_BYTES_RATE',
  'storage.io.cancelled_write_bytes.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Cancelled write bytes per second',
  'Отменённая запись в секунду',
  'Process /proc/self/io cancelled_write_bytes. Storage writes are accounted when pages become dirty; '
  'cancelled writes are separate. Logical bytes include cache and non-disk I/O; syscall counts are not '
  'hardware IOPS. A new baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io cancelled_write_bytes. Запись учитывается при изменении страниц; '
  'отменённая запись отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны '
  'аппаратным IOPS. После перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_RCHAR',
  'storage.io.rchar',
  'By',
  'STORAGE',
  'GAUGE',
  'Logical bytes read',
  'Логическое чтение',
  'Process /proc/self/io rchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io rchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_RCHAR_DELTA',
  'storage.io.rchar.delta',
  'By',
  'STORAGE',
  'SUM',
  'Logical bytes read in interval',
  'Логическое чтение за интервал',
  'Process /proc/self/io rchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io rchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_RCHAR_RATE',
  'storage.io.rchar.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Logical bytes read per second',
  'Логическое чтение в секунду',
  'Process /proc/self/io rchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io rchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_WCHAR',
  'storage.io.wchar',
  'By',
  'STORAGE',
  'GAUGE',
  'Logical bytes written',
  'Логическая запись',
  'Process /proc/self/io wchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io wchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_WCHAR_DELTA',
  'storage.io.wchar.delta',
  'By',
  'STORAGE',
  'SUM',
  'Logical bytes written in interval',
  'Логическая запись за интервал',
  'Process /proc/self/io wchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io wchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_WCHAR_RATE',
  'storage.io.wchar.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Logical bytes written per second',
  'Логическая запись в секунду',
  'Process /proc/self/io wchar. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io wchar. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_SYSCR',
  'storage.io.syscr',
  '1',
  'STORAGE',
  'GAUGE',
  'Read system calls',
  'Системные вызовы чтения',
  'Process /proc/self/io syscr. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io syscr. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_SYSCR_DELTA',
  'storage.io.syscr.delta',
  '1',
  'STORAGE',
  'SUM',
  'Read system calls in interval',
  'Системные вызовы чтения за интервал',
  'Process /proc/self/io syscr. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io syscr. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_SYSCR_RATE',
  'storage.io.syscr.rate',
  '1/s',
  'STORAGE',
  'GAUGE',
  'Read system calls per second',
  'Системные вызовы чтения в секунду',
  'Process /proc/self/io syscr. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io syscr. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_SYSCW',
  'storage.io.syscw',
  '1',
  'STORAGE',
  'GAUGE',
  'Write system calls',
  'Системные вызовы записи',
  'Process /proc/self/io syscw. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS.',
  'Счётчик процесса /proc/self/io syscw. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS.'),
 ('IO_SYSCW_DELTA',
  'storage.io.syscw.delta',
  '1',
  'STORAGE',
  'SUM',
  'Write system calls in interval',
  'Системные вызовы записи за интервал',
  'Process /proc/self/io syscw. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io syscw. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('IO_SYSCW_RATE',
  'storage.io.syscw.rate',
  '1/s',
  'STORAGE',
  'GAUGE',
  'Write system calls per second',
  'Системные вызовы записи в секунду',
  'Process /proc/self/io syscw. Storage writes are accounted when pages become dirty; cancelled writes are '
  'separate. Logical bytes include cache and non-disk I/O; syscall counts are not hardware IOPS. A new '
  'baseline is required after restart, reset or a gap.',
  'Счётчик процесса /proc/self/io syscw. Запись учитывается при изменении страниц; отменённая запись '
  'отдельно. Логические байты включают кеш и операции вне диска; вызовы не равны аппаратным IOPS. После '
  'перезапуска, сброса или пропуска нужен новый отсчёт.'),
 ('SYSTEM_READ_BYTES',
  'storage.block.read_bytes',
  'By',
  'STORAGE',
  'GAUGE',
  'Read bytes',
  'Прочитанные байты',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_READ_BYTES_DELTA',
  'storage.block.read_bytes.delta',
  'By',
  'STORAGE',
  'SUM',
  'Read bytes in interval',
  'Прочитанные байты за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_READ_BYTES_RATE',
  'storage.block.read_bytes.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Read bytes per second',
  'Прочитанные байты в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_BYTES',
  'storage.block.write_bytes',
  'By',
  'STORAGE',
  'GAUGE',
  'Write bytes',
  'Записанные байты',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_BYTES_DELTA',
  'storage.block.write_bytes.delta',
  'By',
  'STORAGE',
  'SUM',
  'Write bytes in interval',
  'Записанные байты за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_BYTES_RATE',
  'storage.block.write_bytes.rate',
  'By/s',
  'STORAGE',
  'GAUGE',
  'Write bytes per second',
  'Записанные байты в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_READ_OPS',
  'storage.block.read_ops',
  '1',
  'STORAGE',
  'GAUGE',
  'Read operations',
  'Операции чтения',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_READ_OPS_DELTA',
  'storage.block.read_ops.delta',
  '1',
  'STORAGE',
  'SUM',
  'Read operations in interval',
  'Операции чтения за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_READ_OPS_RATE',
  'storage.block.read_ops.rate',
  '1/s',
  'STORAGE',
  'GAUGE',
  'Read operations per second',
  'Операции чтения в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_OPS',
  'storage.block.write_ops',
  '1',
  'STORAGE',
  'GAUGE',
  'Write operations',
  'Операции записи',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_OPS_DELTA',
  'storage.block.write_ops.delta',
  '1',
  'STORAGE',
  'SUM',
  'Write operations in interval',
  'Операции записи за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WRITE_OPS_RATE',
  'storage.block.write_ops.rate',
  '1/s',
  'STORAGE',
  'GAUGE',
  'Write operations per second',
  'Операции записи в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_IO_MS',
  'storage.block.io_ms',
  'ms',
  'STORAGE',
  'GAUGE',
  'Busy I/O time',
  'Время активности I/O',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_IO_MS_DELTA',
  'storage.block.io_ms.delta',
  'ms',
  'STORAGE',
  'SUM',
  'Busy I/O time in interval',
  'Время активности I/O за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_IO_MS_RATE',
  'storage.block.io_ms.rate',
  'ms/s',
  'STORAGE',
  'GAUGE',
  'Busy I/O time per second',
  'Время активности I/O в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WEIGHTED_MS',
  'storage.block.weighted_ms',
  'ms',
  'STORAGE',
  'GAUGE',
  'Weighted I/O time',
  'Взвешенное время I/O',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WEIGHTED_MS_DELTA',
  'storage.block.weighted_ms.delta',
  'ms',
  'STORAGE',
  'SUM',
  'Weighted I/O time in interval',
  'Взвешенное время I/O за интервал',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_WEIGHTED_MS_RATE',
  'storage.block.weighted_ms.rate',
  'ms/s',
  'STORAGE',
  'GAUGE',
  'Weighted I/O time per second',
  'Взвешенное время I/O в секунду',
  'Per physical block device, no sum across partitions or device mapper. Sectors are 512 bytes. Time '
  'counters describe kernel accounting, not individual request latency.',
  'Для физического блочного устройства, без суммирования разделов и device mapper. Сектор равен 512 байтам. '
  'Время отражает учёт ядра, а не задержку отдельного запроса.'),
 ('SYSTEM_PSI_AVG10',
  'storage.psi.avg10',
  '%',
  'STORAGE',
  'GAUGE',
  'I/O pressure: 10 s average',
  'Ожидание I/O: Среднее за 10 с',
  'System /proc/pressure/io. Component some means at least one task stalled; full means all non-idle tasks '
  'stalled simultaneously. It is not disk utilization.',
  'Системный /proc/pressure/io. Компонент some означает ожидание хотя бы одной задачи; full — всех не '
  'простаивающих задач одновременно. Это не загрузка диска.'),
 ('SYSTEM_PSI_AVG60',
  'storage.psi.avg60',
  '%',
  'STORAGE',
  'GAUGE',
  'I/O pressure: 60 s average',
  'Ожидание I/O: Среднее за 60 с',
  'System /proc/pressure/io. Component some means at least one task stalled; full means all non-idle tasks '
  'stalled simultaneously. It is not disk utilization.',
  'Системный /proc/pressure/io. Компонент some означает ожидание хотя бы одной задачи; full — всех не '
  'простаивающих задач одновременно. Это не загрузка диска.'),
 ('SYSTEM_PSI_AVG300',
  'storage.psi.avg300',
  '%',
  'STORAGE',
  'GAUGE',
  'I/O pressure: 300 s average',
  'Ожидание I/O: Среднее за 300 с',
  'System /proc/pressure/io. Component some means at least one task stalled; full means all non-idle tasks '
  'stalled simultaneously. It is not disk utilization.',
  'Системный /proc/pressure/io. Компонент some означает ожидание хотя бы одной задачи; full — всех не '
  'простаивающих задач одновременно. Это не загрузка диска.'),
 ('SYSTEM_PSI_TOTAL',
  'storage.psi.total',
  'us',
  'STORAGE',
  'GAUGE',
  'I/O pressure: Total stall time',
  'Ожидание I/O: Суммарное ожидание',
  'System /proc/pressure/io. Component some means at least one task stalled; full means all non-idle tasks '
  'stalled simultaneously. It is not disk utilization.',
  'Системный /proc/pressure/io. Компонент some означает ожидание хотя бы одной задачи; full — всех не '
  'простаивающих задач одновременно. Это не загрузка диска.'),
 ('SYSTEM_PSI_DELTA',
  'storage.psi.stall_delta',
  'us',
  'STORAGE',
  'SUM',
  'I/O stall in interval',
  'Ожидание I/O за интервал',
  'Difference of PSI stall counters. Components some and full overlap and must not be added.',
  'Разность счётчиков ожидания PSI. Компоненты some и full пересекаются; складывать их нельзя.'),
 ('FLASH_LIFE',
  'storage.emmc.life_time',
  '1',
  'STORAGE',
  'STATE',
  'eMMC lifetime category',
  'Категория износа eMMC',
  'JEDEC lifetime A/B: 1 means 0–10% used, 2 means 10–20%, through 10 meaning 90–100%; 11 exceeds estimated '
  'lifetime. Zero is unknown. Not an exact health percentage.',
  'Категории JEDEC A/B: 1 означает израсходовано 0–10%, 2 — 10–20%, до 10 — 90–100%; 11 — расчётный ресурс '
  'превышен. Ноль означает неизвестно. Это не точный процент здоровья.'),
 ('FLASH_EOL',
  'storage.emmc.pre_eol',
  '1',
  'STORAGE',
  'STATE',
  'eMMC reserve state',
  'Состояние резерва eMMC',
  'JEDEC pre-EOL: 1 normal, 2 warning, 3 urgent. Vendor support and access are required; not battery health.',
  'JEDEC pre-EOL: 1 норма, 2 предупреждение, 3 критично. Требуются поддержка и доступ; это не здоровье '
  'батареи.')]
p=root/'app/src/main/java/com/ahwotel/AgentMetric.kt'
s='package com.ahwotel\n\nenum class MetricAggregation { GAUGE, SUM, MAX, STATE }\nenum class AgentMetric(val wire: String, val unit: String, val group: String, val aggregation: MetricAggregation, val title: Int, val help: Int) {\n'
for name,wire,unit,group,agg,en,ru,_,_ in rows:
 prefix='device.' if group in ('BATTERY','WEAR','PASSPORT') or name.startswith(('SYSTEM_', 'FLASH_')) else 'agent.'
 s+=f'    {name}("{prefix}{wire}", "{unit}", "{group}", MetricAggregation.{agg}, R.string.at_{name.lower()}_title, R.string.at_{name.lower()}_help),\n'
s=s.rstrip(',\n')+';\n    val battery get() = group in setOf("BATTERY", "WEAR", "PASSPORT")\n    val scope get() = if (wire.startsWith("device.")) "device" else "agent_process"\n}\n'
p.write_text(s)
for locale,idx in [('values',0),('values-ru',1)]:
 out=['<resources>']
 for name,wire,unit,group,agg,en,ru,meaning_en,meaning_ru in rows:
  battery=group in ('BATTERY','WEAR','PASSPORT')
  device = battery or name.startswith(('SYSTEM_', 'FLASH_'))
  wire=('device.' if device else 'agent.')+wire
  if idx==0:
   scope = 'Entire device battery' if battery else 'Device flash storage, separated by component' if name.startswith('FLASH_') else 'Entire device storage and I/O' if name.startswith('SYSTEM_') else 'This monitoring application'
   impact = 'autonomy and battery condition' if battery else 'flash wear and the risk of exhausting storage lifetime' if name.startswith('FLASH_') else 'device-wide I/O pressure, queueing and latency' if name.startswith('SYSTEM_') else 'agent resource cost and regressions'
   state = 'This metric does not change Performance State automatically.' + (' Device battery drain is not agent energy consumption.' if battery else '')
   title=en; text=f'What it measures\\n{meaning_en}\\n\\nScope and units\\n{scope}; {unit}.\\n\\nImpact\\nUse the trend to investigate {impact}.\\n\\nInterpretation\\nCompare the same model, Android version, agent build and configuration. Aggregation: {agg}.\\n\\nExample\\nCompare two equal observation windows under similar load and screen state. A higher value alone does not prove a fault.\\n\\nLimitations\\nUnavailable, invalid or stale values are not zero. See the source, reason, observation time and quality beside the value.\\n\\nPerformance State\\n{state}\\n\\nSource\\n{wire}. The recorded source identifies Android API, verified sysfs, instrumented operation or calculation.'
  else:
   scope = 'Батарея всего устройства' if battery else 'Флеш-память устройства, отдельно по компонентам' if name.startswith('FLASH_') else 'Хранение и I/O всего устройства' if name.startswith('SYSTEM_') else 'Это приложение мониторинга'
   impact = 'автономность и состояние батареи' if battery else 'износ памяти и риск исчерпания ресурса накопителя' if name.startswith('FLASH_') else 'общесистемное давление I/O, очереди и задержки' if name.startswith('SYSTEM_') else 'стоимость агента и регрессии ресурсов'
   state = 'Метрика автоматически не меняет Performance State.' + (' Разряд устройства не равен энергопотреблению агента.' if battery else '')
   title=ru; text=f'Что измеряется\\n{meaning_ru}\\n\\nОбласть и единицы\\n{scope}; {unit}.\\n\\nВлияние\\nДинамика помогает исследовать {impact}.\\n\\nИнтерпретация\\nСравнивайте одинаковые модель, Android, сборку агента и настройки. Агрегация: '+{'GAUGE':'измерения и распределение','SUM':'сумма приращений за окно','MAX':'максимум за окно','STATE':'наблюдаемые состояния; длительности не вычисляются'}[agg]+f'.\\n\\nПример\\nСравните два равных окна при похожей нагрузке и состоянии экрана. Сам рост значения не доказывает неисправность.\\n\\nОграничения\\nНедоступные, некорректные и устаревшие значения не равны нулю. Смотрите источник, причину, время наблюдения и качество рядом со значением.\\n\\nPerformance State\\n{state}\\n\\nИсточник\\n{wire}. В записи указан Android API, проверенный sysfs, инструментированная операция или расчёт.'
  for suffix,value in [('title',title),('help',text)]:
   out.append(f'<string name="at_{name.lower()}_{suffix}" formatted="false">{escape(value).replace(chr(39),chr(92)+chr(39))}</string>')
 out.append('</resources>'); (root/f'app/src/main/res/{locale}/agent_metrics.xml').write_text('\n'.join(out)+'\n')
