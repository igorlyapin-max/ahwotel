package com.ahwotel

import android.app.Activity
import android.app.Application
import android.os.*
import android.net.TrafficStats
import java.io.File
import java.util.Random

/** Exact moments; bounded uniform reservoir for approximate quantiles (never averages of quantiles). */
class MetricAccumulator {
    var count = 0L; private set
    var sum = 0.0; private set
    var low = Double.POSITIVE_INFINITY; private set
    var high = Double.NEGATIVE_INFINITY; private set
    private val reservoir = ArrayList<Double>(512)
    private val random = Random(1)
    fun add(v: Double) {
        if (!v.isFinite()) return
        count++; sum += v; low = minOf(low,v); high = maxOf(high,v)
        if (reservoir.size < 512) reservoir.add(v) else {
            val i = (random.nextDouble() * count).toLong(); if (i < 512) reservoir[i.toInt()] = v
        }
    }
    fun percentile(q: Double): Double? = reservoir.sorted().let { if (it.isEmpty()) null else it[(kotlin.math.ceil(q * it.size).toInt()-1).coerceIn(0,it.lastIndex)] }
    fun value(aggregation: MetricAggregation): Double = when (aggregation) {
        MetricAggregation.SUM -> sum
        MetricAggregation.MAX -> high
        else -> sum / count.coerceAtLeast(1)
    }
}
class AgentCosts : Application.ActivityLifecycleCallbacks {
    private val cells = linkedMapOf<Pair<AgentMetric,String>,MetricAccumulator>()
    @Volatile var enabled = false
    @Volatile var foreground = false; private set
    private var activities = 0
    private var stateAt = SystemClock.elapsedRealtime()
    private var fg = 0L; private var bg = 0L
    private var heldAt: Long? = null
    private var heldUntil = 0L; private var accountedUntil = 0L
    private var heldMaximum = 0L
    private var wakeTracking = true
    private var ownCpu = 0.0
    private var lastProcessCpu = Process.getElapsedCpuTime()
    @Synchronized fun add(m: AgentMetric, value: Double, component: String = "") {
        if (!enabled || component.length > 64) return
        if (cells.size >= 256 && (m to component) !in cells) return
        cells.getOrPut(m to component) { MetricAccumulator() }.add(value)
    }
    fun operation(name: String, started: Long, success: Boolean, samples: Long = 0, bytes: Long = 0, cancelled: Boolean = false, internal: Boolean = false) {
        if (internal) return // Self work is accounted by dedicated bounded counters, never recursively.
        add(AgentMetric.CALLS,1.0,name); add(AgentMetric.DURATION,(SystemClock.elapsedRealtime()-started).toDouble(),name)
        add(AgentMetric.SAMPLES,samples.toDouble(),name); add(AgentMetric.GENERATED,bytes.toDouble(),name)
        if (!success) add(if (cancelled) AgentMetric.CANCELLED else AgentMetric.ERRORS,1.0,name)
    }
    @Synchronized fun own(cpu: Double, duration: Long) { ownCpu += cpu; add(AgentMetric.SELF_CPU,cpu.toDouble()); add(AgentMetric.SELF_DURATION,duration.toDouble()) }
    @Synchronized fun takeOwnCpu(): Double = ownCpu.also { ownCpu = 0.0 }
    @Synchronized fun drain(): Map<Pair<AgentMetric,String>,MetricAccumulator> = cells.toMap().also { cells.clear() }
    @Synchronized fun start() {
        cells.clear(); ownCpu = 0.0; fg = 0; bg = 0
        stateAt = SystemClock.elapsedRealtime(); lastProcessCpu=Process.getElapsedCpuTime()
        heldAt=null; heldUntil=0; accountedUntil=0; heldMaximum=0; wakeTracking=true
    }
    @Synchronized fun setWakeTracking(value: Boolean, now: Long) {
        if (value == wakeTracking) return
        if (wakeTracking) wakeObserve(now, Int.MAX_VALUE/1000, Int.MAX_VALUE/1000)
        wakeTracking=value; heldMaximum=0
        // Enabling observation halfway through a physical hold is not another acquisition.
        if (heldAt != null) {
            heldAt=if(now<heldUntil) now else null
            accountedUntil=minOf(now,heldUntil)
        }
    }
    @Synchronized private fun advance(now: Long) {
        val processCpu=Process.getElapsedCpuTime()
        if(enabled && processCpu>=lastProcessCpu) add(if(foreground) AgentMetric.CPU_FOREGROUND else AgentMetric.CPU_BACKGROUND,(processCpu-lastProcessCpu).toDouble(),"activity_lifecycle")
        lastProcessCpu=processCpu
        if (enabled) { if (foreground) fg += (now-stateAt).coerceAtLeast(0) else bg += (now-stateAt).coerceAtLeast(0) }; stateAt = now
    }
    @Synchronized fun checkpoint(now: Long) { advance(now) }
    @Synchronized fun runtime(now: Long): Pair<Long,Long> { advance(now); return (fg to bg).also { fg=0; bg=0 } }
    @Synchronized fun wakeAcquire(now: Long, timeout: Long) {
        wakeRelease(now); heldAt=now; accountedUntil=now; heldUntil=now+timeout; if(wakeTracking) add(AgentMetric.WAKE_COUNT,1.0,"AHWOTel:Monitoring")
    }
    @Synchronized fun wakeObserve(now: Long, warning: Int, critical: Int): Int {
        val start = heldAt ?: return 0
        val end = minOf(now,heldUntil)
        val duration = (end-start).coerceAtLeast(0)
        if (!enabled || !wakeTracking) {
            accountedUntil=end
            if(now>=heldUntil) heldAt=null
            return 0
        }
        add(AgentMetric.WAKE_DURATION,(end-accountedUntil).coerceAtLeast(0).toDouble(),"AHWOTel:Monitoring")
        accountedUntil = end
        heldMaximum = maxOf(heldMaximum,duration)
        if (now>=heldUntil) { add(AgentMetric.WAKE_AVERAGE,duration.toDouble(),"AHWOTel:Monitoring"); heldAt=null }
        val severity = if (duration>critical*1000L) 2 else if (duration>warning*1000L) 1 else 0
        return severity
    }
    @Synchronized fun wakeMaximum(): Long = heldMaximum.also { heldMaximum=0 }
    @Synchronized fun wakeRelease(now: Long) {
        val start=heldAt; val until=heldUntil
        wakeObserve(now,Int.MAX_VALUE/1000,Int.MAX_VALUE/1000); if(wakeTracking && start!=null && now<until) add(AgentMetric.WAKE_AVERAGE,(now-start).coerceAtLeast(0).toDouble(),"AHWOTel:Monitoring"); heldAt=null }
    override fun onActivityResumed(activity: Activity) { synchronized(this) { advance(SystemClock.elapsedRealtime()); activities++; foreground=true } }
    override fun onActivityPaused(activity: Activity) { synchronized(this) { advance(SystemClock.elapsedRealtime()); activities=(activities-1).coerceAtLeast(0); foreground=activities>0 } }
    override fun onActivityCreated(a: Activity,b: Bundle?) {} override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {} override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {} override fun onActivityDestroyed(a: Activity) {}
}
class SelfSampler(private val app: MonitorApp) {
    private var cpu: Pair<Long,Long>? = null
    private var net: Pair<Long,Long>? = null
    private var priorForeground: Boolean? = null
    fun reset() { cpu=null; net=null; priorForeground=null }
    fun fast(groups: Set<SelfGroup>, elapsed: Long): List<AgentReading> {
        val rows=mutableListOf<AgentReading>()
        fun add(m: AgentMetric,v: Double?, source: String="process_api") {
            rows += if (v != null && v.isFinite()) AgentReading(m,v,source=source) else AgentReading.missing(m,"MISSING_VALUE",source,"UNAVAILABLE")
        }
        if (SelfGroup.CPU in groups) {
            app.costs.checkpoint(elapsed)
            val t=Process.getElapsedCpuTime(); val old=cpu; cpu=elapsed to t
            add(AgentMetric.CPU_TIME,t.toDouble())
            val own=app.costs.takeOwnCpu()
            if (old != null && elapsed>old.first && t>=old.second) {
                val delta=(t-old.second).toDouble(); val dt=(elapsed-old.first).toDouble()
                add(AgentMetric.CPU_DELTA,delta); add(AgentMetric.CPU_PERCENT,100*delta/dt)
                add(AgentMetric.CPU_MINUTE,60000*delta/dt); add(AgentMetric.CPU_HOUR,3600000*delta/dt)
                rows += if (delta>=10) AgentReading(AgentMetric.SELF_OVERHEAD,(100*own/delta).coerceAtMost(100.0),source="thread_cpu",quality="INCOMPLETE")
                    else AgentReading.missing(AgentMetric.SELF_OVERHEAD,"INSUFFICIENT_WINDOW","thread_cpu","UNAVAILABLE")
            } else listOf(AgentMetric.CPU_DELTA,AgentMetric.CPU_PERCENT,AgentMetric.CPU_MINUTE,AgentMetric.CPU_HOUR,AgentMetric.SELF_OVERHEAD).forEach { rows+=AgentReading.missing(it,"BASELINE","process_api","UNAVAILABLE") }
        }
        if (SelfGroup.MEMORY in groups) {
            add(AgentMetric.PSS,runCatching { Debug.getPss()*1024.0 }.getOrNull())
            add(AgentMetric.RSS,runCatching { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toDoubleOrNull()?.times(1024) } }.getOrNull(),"proc_self_status")
            Runtime.getRuntime().let { add(AgentMetric.JAVA_USED,(it.totalMemory()-it.freeMemory()).toDouble()); add(AgentMetric.JAVA_MAX,it.maxMemory().toDouble()) }
            add(AgentMetric.NATIVE,Debug.getNativeHeapAllocatedSize().toDouble())
            add(AgentMetric.GC_COUNT,runCatching { Debug.getRuntimeStat("art.gc.gc-count")?.toDoubleOrNull() }.getOrNull(),"art_runtime")
            add(AgentMetric.GC_TIME,runCatching { Debug.getRuntimeStat("art.gc.gc-time")?.toDoubleOrNull() }.getOrNull(),"art_runtime")
            rows+=AgentReading.missing(AgentMetric.OOM,"NO_VERIFIED_SOURCE")
        }
        return rows
    }
    fun medium(groups: Set<SelfGroup>, now: Long): List<AgentReading> {
        val rows=mutableListOf<AgentReading>()
        if (SelfGroup.NETWORK in groups) {
            val tx=TrafficStats.getUidTxBytes(Process.myUid()); val rx=TrafficStats.getUidRxBytes(Process.myUid())
            listOf(AgentMetric.TX to tx,AgentMetric.RX to rx).forEach { (m,v)-> rows+=if(v>=0) AgentReading(m,v.toDouble(),source="uid_traffic") else AgentReading.missing(m,"UNSUPPORTED_COUNTER","uid_traffic") }
            val old=net; net=if(tx>=0&&rx>=0) tx to rx else null
            listOf(Triple(AgentMetric.TX_DELTA,tx,old?.first),Triple(AgentMetric.RX_DELTA,rx,old?.second)).forEach { (m,v,b)->
                rows+=if(b!=null&&v>=b) AgentReading(m,(v-b).toDouble(),source="uid_traffic") else AgentReading.missing(m,"BASELINE","uid_traffic","UNAVAILABLE") }
        }
        if (SelfGroup.RUNTIME in groups) {
            val (fg,bg)=app.costs.runtime(now)
            rows+=AgentReading(AgentMetric.FOREGROUND,fg.toDouble(),source="activity_lifecycle")
            rows+=AgentReading(AgentMetric.BACKGROUND,bg.toDouble(),source="activity_lifecycle")
            rows+=AgentReading(AgentMetric.FG_SERVICE,(fg+bg).toDouble(),source="service_lifecycle")
            rows+=AgentReading(AgentMetric.WAKE_MAX,app.costs.wakeMaximum().toDouble(),source="owned_wakelock",component="AHWOTel:Monitoring")
            rows+=AgentReading.missing(AgentMetric.WAKEUPS,"NO_VERIFIED_SOURCE")
        }
        return rows
    }
    suspend fun storage(): List<AgentReading> {
        val file=app.getDatabasePath("monitor.db")
        return listOf(AgentReading(AgentMetric.DB_SIZE,listOf(file,File(file.path+"-wal"),File(file.path+"-shm")).sumOf { it.length() }.toDouble(),source="file_size"),
            AgentReading(AgentMetric.BUFFER,app.db.dao().outboxBytes().toDouble(),source="room"), AgentReading(AgentMetric.PENDING,app.db.dao().outboxCount().toDouble(),source="room"))
    }
}
