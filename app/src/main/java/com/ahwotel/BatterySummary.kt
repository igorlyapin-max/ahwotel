package com.ahwotel

data class BatteryDaySummary(val minTemp: Double?,val maxTemp: Double?,val minLevel: Double?,val maxLevel: Double?,
    val observedMs: Long,val powerMs: Long,val dischargeMs: Long)

/** Only observed adjacent intervals; never extrapolates a last value across missing collection. */
fun summarizeBattery(rows: List<TelemetryRecord>, maxGapMs: Long): BatteryDaySummary {
    val good=rows.filter { it.status=="AVAILABLE" && it.value?.isFinite()==true }
    val temps=good.filter { it.metric=="TEMP" }.map { it.value!! }
    val levels=good.filter { it.metric=="LEVEL" }.map { it.value!! }
    val frames=rows.groupBy { Triple(it.sessionId,it.segment,it.time) }.entries.sortedBy { it.key.third }
    var observed=0L; var power=0L; var discharge=0L
    frames.zipWithNext().forEach { (a,b) ->
        val dt=b.key.third-a.key.third
        if(a.key.first==b.key.first && a.key.second==b.key.second && dt in 1..maxGapMs) {
            val av=a.value.filter { it.status=="AVAILABLE" && it.value?.isFinite()==true }.associate { it.metric to it.value!! }; val bv=b.value.filter { it.status=="AVAILABLE" && it.value?.isFinite()==true }.associate { it.metric to it.value!! }
            if(listOf("LEVEL","TEMP","PLUG","STATUS").all { it in av && it in bv }) {
                observed+=dt
                if(av["PLUG"]!!>0 && bv["PLUG"]!!>0) power+=dt
                if(av["PLUG"]==0.0 && bv["PLUG"]==0.0 && av["STATUS"]==3.0 && bv["STATUS"]==3.0) discharge+=dt
            }
        }
    }
    return BatteryDaySummary(temps.minOrNull(),temps.maxOrNull(),levels.minOrNull(),levels.maxOrNull(),observed,power,discharge)
}
