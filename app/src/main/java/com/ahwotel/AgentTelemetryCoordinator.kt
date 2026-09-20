package com.ahwotel

import android.os.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.TimeZone

/** Session-owned, serial scheduler. No alarms, independent foreground service or recursive telemetry. */
class AgentTelemetryCoordinator(private val app: MonitorApp) {
    private val sampler=SelfSampler(app)
    private val drain=BatteryDrain()
    private val io=IoTelemetry()
    private val pendingSlowChecks=mutableSetOf("wear","passport")
    val probes=kotlinx.coroutines.flow.MutableStateFlow<List<TelemetryRecord>>(emptyList())
    suspend fun checkNow() {
        recheck()
        val s=app.settings.value
        val b=s.batterySettings
        val values=mutableListOf<AgentReading>()
        if(b.enabled) {
            if(b.current) values+=app.battery.current(b.extended).values
            if(b.wear) values+=app.battery.wear(b.fallback)
            if(b.passport) values+=app.battery.passport(b.fallback)
        }
        if(s.selfTelemetry.enabled && SelfGroup.STORAGE in s.selfTelemetry.groups)
            values+=io.sample(SystemClock.elapsedRealtime(),s.selfTelemetry.processIo,s.selfTelemetry.systemIo,s.selfTelemetry.flashWear,s.selfTelemetry.ioSeconds*1000L)
        values.forEach(::logStatus)
        reportBattery(values,b.enabled && b.current,System.currentTimeMillis())
    }
    fun recheck() { io.recheck(); due.clear(); pendingSlowChecks.addAll(listOf("wear","passport")) }
    private fun reportBattery(values: List<AgentReading>,enabled: Boolean,time: Long) {
        app.sources.record(listOf(Metric.BATTERY,Metric.TEMPERATURE,Metric.CHARGING).map { metric ->
            val r=values.find { it.metric==metric.batteryMetric() }
            val status=if(!enabled) Availability.DISABLED else when(r?.status) {
                "AVAILABLE" -> Availability.AVAILABLE
                "UNSUPPORTED","PERMISSION_DENIED" -> Availability.UNSUPPORTED
                "ERROR" -> Availability.ERROR
                else -> Availability.WARMING_UP
            }
            val reason=if(!enabled) SourceReason.DISABLED else runCatching { SourceReason.valueOf(r?.reason ?: "MISSING_VALUE") }.getOrDefault(SourceReason.MISSING_VALUE)
            SourceResult(metric,SourceId.BATTERY_BROADCAST,status,reason,time)
        })
    }
    private var session: SessionRow?=null
    private var startSettings=Settings()
    private var lastSettings: Settings?=null
    private var signature=""
    private var metadata="{}"
    private var startWall=0L; private var startElapsed=0L; private var segment=0
    private val due=mutableMapOf<String,Long>()
    private val slowLast=mutableMapOf<String,Long>()
    private val sendDue=mutableMapOf<String,Long>()
    private val readings=linkedMapOf<Triple<AgentMetric,String,String>,Pair<AgentReading,MetricAccumulator>>()
    private val missing=linkedMapOf<Triple<AgentMetric,String,String>,AgentReading>()
    private val batteryRows=mutableListOf<TelemetryRecord>()
    private val statuses=mutableMapOf<String,String>()
    private var lastElapsed=0L; private var lastWall=0L; private var wasPaused=false
    private var powerGeneration=0L
    private var lastWakeSeverity=0
    private var debugActive=false
    suspend fun start(s: SessionRow, snapshot: Settings = SettingsCodec.decode(s.configuration)) {
        probes.value=emptyList()
        startSettings=snapshot
        session=s; metadata="{}"; lastSettings=null; signature=""; due.clear(); sendDue.clear(); readings.clear(); missing.clear(); batteryRows.clear(); statuses.clear()
        io.recheck(); pendingSlowChecks.addAll(listOf("wear","passport")); slowLast.clear(); app.db.agentDao().schedules().forEach { slowLast[it.name]=it.last }
        sampler.reset(); drain.reset(); io.reset(); segment=0; lastElapsed=0; wasPaused=false; app.costs.start(); app.costs.enabled=app.settings.value.selfTelemetry.enabled
        startWall=System.currentTimeMillis(); startElapsed=SystemClock.elapsedRealtime(); powerGeneration=app.battery.generation
        val settings=app.settings.value
        signature=configSignature(settings); lastSettings=settings
        lastWall=startWall; lastElapsed=startElapsed; lastWakeSeverity=0
        debugActive=startWall<settings.selfTelemetry.debugUntil
        app.costs.setWakeTracking(settings.selfTelemetry.enabled && SelfGroup.RUNTIME in settings.selfTelemetry.groups,startElapsed)
        metadata=context(settings)
    }
    fun configSignature(s: Settings): String = MeasurementConfiguration.signature(startSettings,s,System.currentTimeMillis())
    private fun context(s: Settings): String {
        val prefs=app.getSharedPreferences("installation",0)
        val installation=prefs.getString("id",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("id",it).commit()) }
        val power=app.getSystemService(PowerManager::class.java)
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val net=cm.activeNetwork?.let(cm::getNetworkCapabilities)
        val network=when { net==null->"none"; net.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"wifi"; net.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"cellular"; net.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"ethernet";else->"other" }
        return JSONObject().put("device.id",s.deviceId).put("installation.id",installation).put("agent.version",BuildConfig.VERSION_NAME)
            .put("agent.build",BuildConfig.VERSION_CODE).put("os.version",Build.VERSION.RELEASE).put("android.sdk",Build.VERSION.SDK_INT)
            .put("device.manufacturer",Build.MANUFACTURER).put("device.model",Build.MODEL).put("device.product",Build.PRODUCT)
            .put("timezone",TimeZone.getDefault().id).put("config.version",signature).put("process.id",Process.myPid())
            .put("screen.on",power.isInteractive).put("doze",power.isDeviceIdleMode).put("power.save",power.isPowerSaveMode)
            .put("network.type",network).put("foreground",app.costs.foreground).put("monitoring.profile",s.oem.profile).toString()
    }
    suspend fun tick(paused: Boolean, now: Long=SystemClock.elapsedRealtime()) {
        if(session==null) return
        val s=app.settings.value; val wall=System.currentTimeMillis()
        val debugNow=wall<s.selfTelemetry.debugUntil
        val nextSignature=if(s!=lastSettings || debugNow!=debugActive) configSignature(s) else signature
        debugActive=debugNow
        val discontinuity=lastElapsed>0 && (now-lastElapsed>120_000 || kotlin.math.abs((wall-lastWall)-(now-lastElapsed))>2000)
        if(signature!=nextSignature || paused!=wasPaused || discontinuity) {
            if(lastSettings!=null) flush(lastSettings!!,lastWall.takeIf { it>0 } ?: wall,lastElapsed.takeIf { it>0 } ?: now)
            if(signature!=nextSignature) { io.recheck(); pendingSlowChecks.addAll(listOf("wear","passport")) }
            signature=nextSignature; segment++; sampler.reset(); drain.reset(); io.reset(); due.clear(); sendDue.clear()
            startWall=wall; startElapsed=now
            app.costs.drain(); app.costs.runtime(now)
        }
        lastSettings=s; lastElapsed=now; lastWall=wall; wasPaused=paused
        app.costs.setWakeTracking(s.selfTelemetry.enabled && SelfGroup.RUNTIME in s.selfTelemetry.groups && !paused,now)
        app.costs.enabled=s.selfTelemetry.enabled && !paused
        if(paused) return
        val self=s.selfTelemetry
        if(self.enabled) {
            val severity=app.costs.wakeObserve(now,self.warningSeconds,self.criticalSeconds)
            if(severity!=lastWakeSeverity) { if(severity>0) app.logs.event("agent_wakelock_threshold",number=severity.toLong()); lastWakeSeverity=severity }
        }
        if(due.isNotEmpty() && due.values.all { now<it } && powerGeneration==app.battery.generation &&
            now-startElapsed<self.windowSeconds*1000L) return
        // Context changes split windows so foreground, network and device states remain comparable.
        val nextContext=context(s)
        if(metadata!="{}" && metadata!=nextContext) {
            flush(s,wall,now); segment++; sampler.reset(); drain.reset(); io.reset(); startWall=wall; startElapsed=now
        }
        metadata=nextContext
        fun ready(key: String,seconds: Int): Boolean { if(now<(due[key] ?: 0L)) return false; due[key]=now+seconds*1000L; return true }
        val b=s.batterySettings
        val batteryWanted=b.enabled && b.current
        var batteryRead=false
        if(!batteryWanted) reportBattery(emptyList(),false,wall)
        if(powerGeneration!=app.battery.generation) { powerGeneration=app.battery.generation; due.remove("battery"); drain.reset() }
        if(batteryWanted && ready("battery",b.currentSeconds)) {
            val began=SystemClock.elapsedRealtime()
            val snapshot=app.battery.current(b.extended); batteryRead=true
            reportBattery(snapshot.values,true,snapshot.wall)
            persist((snapshot.values+drain.update(snapshot,b.currentSeconds*1000L)).map { record(it,"battery",snapshot.wall,snapshot.wall,0) },s)
            app.costs.operation("battery_collector",began,true,snapshot.values.size.toLong())
        }
        for((name,enabled,seconds) in listOf(Triple("wear",b.enabled&&b.wear,b.wearSeconds),Triple("passport",b.enabled&&b.passport,b.passportSeconds))) {
            if(!ready(name,60)) continue
            val previous=slowLast[name]
            if(enabled && (name in pendingSlowChecks || previous==null || wall<previous || wall-previous>=seconds*1000L)) {
                val began=SystemClock.elapsedRealtime()
                val values=if(name=="wear") app.battery.wear(b.fallback) else app.battery.passport(b.fallback)
                val rows=values.map { record(it,"wear",wall,wall,0) }
                if(persist(rows,s)) {
                    pendingSlowChecks.remove(name)
                    val scheduleAt=if(values.any { it.status!="AVAILABLE" }) wall-seconds*1000L+minOf(seconds*1000L,900_000L) else wall
                    slowLast[name]=scheduleAt; app.db.agentDao().schedule(TelemetrySchedule(name,scheduleAt))
                }
                app.costs.operation("battery_$name",began,true,values.size.toLong())
            }
        }
        if(self.enabled) {
            val begin=SystemClock.elapsedRealtime(); val cpu=Debug.threadCpuTimeNanos()
            if(ready("fast",self.fast(wall))) {
                sampler.fast(self.groups,now).forEach(::observe)
                if(SelfGroup.BATTERY in self.groups && !batteryRead) {
                    val snapshot=app.battery.current(b.extended)
                    (snapshot.values+drain.update(snapshot,self.fast(wall)*1000L)).forEach { batteryRows+=record(it,"self",snapshot.wall,snapshot.wall,0) }
                }
            }
            if(SelfGroup.STORAGE in self.groups && ready("io",self.ioSeconds)) {
                val ioRows=io.sample(now,self.processIo,self.systemIo,self.flashWear,self.ioSeconds*1000L).map { record(it,"self",wall,wall,0) }
                if(ioRows.isNotEmpty()) persist(ioRows,s)
            }
            if(ready("medium",self.mediumSeconds)) sampler.medium(self.groups,now).forEach(::observe)
            app.costs.own((Debug.threadCpuTimeNanos()-cpu)/1_000_000.0,(SystemClock.elapsedRealtime()-begin))
            if(SelfGroup.STORAGE in self.groups && ready("slow",self.slowSeconds)) sampler.storage().forEach(::observe)
        }
        if(now-startElapsed>=self.windowSeconds*1000L || batteryRows.size>=128) flush(s,wall,now)
    }
    private fun observe(r: AgentReading) {
        val key=Triple(r.metric,r.component,r.source)
        if(r.value!=null && r.status=="AVAILABLE") {
            readings.getOrPut(key) { r to MetricAccumulator() }.second.add(r.value)
            missing.remove(key)
        } else { missing[key]=r }
        logStatus(r)
    }
    private fun logStatus(r: AgentReading) {
        val wall=r.checkedAt ?: System.currentTimeMillis()
        val row=TelemetryRecord(sessionId=session?.id.orEmpty(),stream=if(r.metric.battery) "battery" else "self",
            metric=r.metric.name,component=r.component,time=wall,start=wall,durationMs=0,segment=segment,
            value=r.value,text=r.text,status=r.status,reason=r.reason,source=r.source,quality=r.quality,metadata=JSONObject(metadata).put("source.checked_at",r.checkedAt ?: wall).toString())
        probes.value=probes.value.filterNot { it.metric==row.metric && it.component==row.component }+row
        val key=r.metric.name+":"+r.source+":"+r.component
        val value=r.status+":"+r.reason
        if(statuses.put(key,value)!=value) app.logs.event("telemetry_source",diagnostic=true,probeMetric=r.metric.wire,probeStatus="${r.source}:$value")
    }
    private fun record(r: AgentReading, stream: String, wall: Long, from: Long, duration: Long): TelemetryRecord {
        logStatus(r)
        return TelemetryRecord(sessionId=session!!.id,stream=stream,metric=r.metric.name,component=r.component,time=wall,start=from,
            durationMs=duration,segment=segment,value=r.value,low=r.value,high=r.value,count=if(r.value==null) 0 else 1,
            text=r.text,status=r.status,reason=r.reason,source=r.source,quality=r.quality,metadata=JSONObject(metadata).put("source.checked_at",r.checkedAt ?: wall).toString())
    }
    suspend fun stop() {
        if(session==null) return
        val s=lastSettings ?: app.settings.value
        app.costs.wakeRelease(SystemClock.elapsedRealtime())
        flush(s,System.currentTimeMillis(),SystemClock.elapsedRealtime(),final=true)
        app.costs.enabled=false; app.costs.drain(); session=null
    }
    private suspend fun flush(s: Settings, wall: Long, now: Long, final: Boolean=false) {
        val begin=SystemClock.elapsedRealtime()
        if(s.selfTelemetry.enabled && !wasPaused) sampler.medium(s.selfTelemetry.groups.intersect(setOf(SelfGroup.RUNTIME)),now).forEach(::observe)
        app.costs.checkpoint(now)
        val rows=batteryRows.toMutableList()
        if(s.selfTelemetry.enabled) {
            val allowed=s.selfTelemetry.groups.map { it.name }.toSet()
            app.costs.drain().forEach { (key,a) ->
                if(key.first.group in allowed) {
                    // Cost accumulators already contain moments; preserve their distribution instead of averaging p95.
                    val r=AgentReading(key.first,source="instrumented",component=key.second,reason=if(key.first==AgentMetric.ERRORS) "OPERATION_FAILED" else "NONE")
                    rows+=aggregate(r,a,wall,now)
                }
            }
            readings.values.forEach { (r,a) -> rows+=aggregate(r,a,wall,now) }
            missing.values.forEach { r-> rows+=record(r,"self",wall,startWall,(now-startElapsed).coerceAtLeast(0)) }
        } else app.costs.drain()
        if(rows.isNotEmpty()) persist(rows,s,final)
        readings.clear(); missing.clear(); batteryRows.clear(); startWall=wall; startElapsed=now
        app.costs.add(AgentMetric.SELF_RECORDS,rows.count { it.stream=="self" }.toDouble())
        app.costs.add(AgentMetric.SELF_BYTES,rows.filter { it.stream=="self" }.sumOf { AgentHistoryExport.json(it).toString().toByteArray().size }.toDouble())
        app.costs.add(AgentMetric.SELF_DURATION,(SystemClock.elapsedRealtime()-begin).toDouble())
    }
    private fun aggregate(r: AgentReading,a: MetricAccumulator,wall: Long,now: Long): TelemetryRecord {
        val value=a.value(r.metric.aggregation)
        return record(r,"self",wall,startWall,(now-startElapsed).coerceAtLeast(0)).copy(value=value,low=a.low,high=a.high,sum=a.sum,count=a.count,
            p50=a.percentile(.5),p95=a.percentile(.95),quality=if(a.count>512) "ESTIMATED" else r.quality)
    }
    private suspend fun persist(rows: List<TelemetryRecord>,s: Settings,final: Boolean=false): Boolean {
        val packets=mutableListOf<OutboxRow>()
        if(s.otlpEnabled) for((stream,group) in rows.groupBy { it.stream }) {
            val interval=when(stream) { "self"->s.selfTelemetry.uploadSeconds; "wear"->s.batterySettings.wearUploadSeconds; else->s.batterySettings.uploadSeconds }
            val wall=System.currentTimeMillis()
            val deadline=sendDue[stream]?.takeIf { it>=wall } ?: (wall+interval*1000L).also { sendDue[stream]=it }
            group.chunked(32).forEach { chunk ->
                val began=SystemClock.elapsedRealtime()
                val raw=AgentTelemetryWire.encode(chunk)
                val compressAt=SystemClock.elapsedRealtime()
                val compressed=AgentTelemetryWire.gzip(raw)
                check(raw.size<=1024*1024 && compressed.size<=1024*1024) { "telemetry_packet_too_large" }
                packets+=OutboxRow(createdAt=wall,endpoint=s.endpoint,payload=compressed,stream=stream,dueAt=deadline,compressed=true)
                // Self encoding statistics are buffered; they never cause immediate self records/uploads.
                app.costs.add(AgentMetric.RAW,raw.size.toDouble(),stream); app.costs.add(AgentMetric.COMPRESSED,compressed.size.toDouble(),stream)
                app.costs.add(AgentMetric.RATIO,compressed.size.toDouble()/raw.size.coerceAtLeast(1),stream)
                app.costs.operation("compression",compressAt,true,bytes=compressed.size.toLong(),internal=stream=="self")
                app.costs.operation("serialization",began,true,bytes=raw.size.toLong(),internal=stream=="self")
            }
        }
        val accepted=app.mutex.withLock {
            val timing=app.sessionTiming
            if(timing == null || timing.sessionId!=session?.id || !final && !timing.canRecord(session!!.id,SystemClock.elapsedRealtime())) return@withLock false
            app.db.withTransaction {
                app.db.agentDao().insert(rows)
                if(app.settings.value.otlpEnabled && app.settings.value.endpoint==s.endpoint) packets.forEach { app.db.dao().enqueue(it) }
            }
            true
        }
        if(accepted && packets.isNotEmpty()) app.scheduleUpload()
        return accepted
    }
}
