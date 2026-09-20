package com.ahwotel

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File

/** Bounded file reads with errno preserved; no shell or privileged helper is used by the APK. */
object TelemetryFiles {
    fun read(path: String): String {
        val fd = Os.open(path, OsConstants.O_RDONLY, 0)
        try {
            val bytes = ByteArray(65537)
            var n = 0
            while (n < bytes.size) {
                val count = Os.read(fd, bytes, n, bytes.size - n)
                if (count <= 0) break
                n += count
            }
            require(n < bytes.size) { "oversize" }
            return String(bytes, 0, n, Charsets.US_ASCII)
        } finally { Os.close(fd) }
    }
    fun failure(e: Exception): Pair<String, String> = when {
        e is SecurityException || e is ErrnoException && e.errno in listOf(OsConstants.EACCES, OsConstants.EPERM) -> "PERMISSION_DENIED" to "PERMISSION_DENIED"
        e is ErrnoException && e.errno in listOf(OsConstants.ENOENT, OsConstants.ENOTDIR) -> "UNSUPPORTED" to "NOT_FOUND"
        e is IllegalArgumentException -> "UNAVAILABLE" to "INVALID_VALUE"
        else -> "ERROR" to "READ_FAILED"
    }
}

class IoTelemetry(
    private val read: (String) -> String = TelemetryFiles::read,
    private val devices: () -> List<String> = { if(!Os.access("/sys/block",OsConstants.R_OK or OsConstants.X_OK)) throw SecurityException()
        File("/sys/block").list()?.toList() ?: throw java.io.IOException("directory_unreadable") }
) {
    private val failures = mutableMapOf<String, Pair<Long, Pair<String,String>>>()
    private val previous = mutableMapOf<String, Pair<Long,Long>>()
    private val selectedSources=mutableMapOf<String,String>()
    private var maxGapMs=180_000L
    private var flash: Pair<Long,List<AgentReading>>? = null
    fun reset() { previous.clear(); selectedSources.clear() }
    fun recheck() { failures.clear(); flash=null; reset() }

    private fun probe(path: String, metrics: List<AgentMetric>, now: Long, component: String = "",
                      parse: (String) -> List<AgentReading>): List<AgentReading> {
        val entry = failures[path]?.takeIf { now < it.first }
        val cached = entry?.second
        if (cached != null) return metrics.map { AgentReading.missing(it,cached.second,path,cached.first).copy(component=component,checkedAt=System.currentTimeMillis()-(now-(entry!!.first-900_000))) }
        return try { parse(read(path)).also { failures.remove(path) } }
        catch(e: Exception) {
            previous.keys.removeAll { it.startsWith("$path|") }
            val failure = TelemetryFiles.failure(e)
            failures[path] = now + 900_000 to failure
            metrics.map { AgentReading.missing(it,failure.second,path,failure.first).copy(component=component) }
        }
    }
    private fun counter(metric: AgentMetric, value: Long, now: Long, source: String, component: String, scale: Double = 1.0): List<AgentReading> {
        require(value >= 0)
        val out = mutableListOf(AgentReading(metric,value*scale,source=source,component=component))
        val old = previous.put("$source|$component|$metric",now to value)
        val valid = old != null && now-old.first in 1..maxGapMs && value >= old.second
        val delta = AgentMetric.entries.find { it.name == metric.name+"_DELTA" }
        val rate = AgentMetric.entries.find { it.name == metric.name+"_RATE" }
        for (m in listOfNotNull(delta,rate)) {
            out += if (valid) {
                val diff = (value-old!!.second)*scale
                AgentReading(m,if(m==rate) diff*1000/(now-old.first) else diff,source=source,component=component)
            } else AgentReading.missing(m,if(old!=null && value<old.second) "COUNTER_RESET" else "BASELINE",source,"UNAVAILABLE").copy(component=component)
        }
        return out
    }
    fun sample(now: Long, process: Boolean, system: Boolean, wear: Boolean, expectedMs: Long = 60_000): List<AgentReading> {
        maxGapMs=expectedMs*3
        val out = mutableListOf<AgentReading>()
        if(process) {
            val base = listOf("read_bytes","write_bytes","cancelled_write_bytes","rchar","wchar","syscr","syscw")
            val all = AgentMetric.entries.filter { it.name.startsWith("IO_") }
            out += probe("/proc/self/io",all,now) { raw ->
                val values = parseProcess(raw)
                base.flatMap { name -> counter(AgentMetric.valueOf("IO_"+name.uppercase()),values.getValue(name),now,"/proc/self/io","") }
            }
        }
        var discoveryFailure: Pair<String,String>?=null
        val discovered=if(system || wear) try { devices() } catch(e: Exception) {
            discoveryFailure=TelemetryFiles.failure(e); emptyList()
        } else emptyList()
        if(system) {
            val metrics = AgentMetric.entries.filter { it.name.startsWith("SYSTEM_PSI_") }
            out += probe("/proc/pressure/io",metrics,now) { raw ->
                val parsed = parsePsi(raw)
                parsed.flatMap { (component,fields) ->
                    val values = listOf("avg10","avg60","avg300").map { field ->
                        AgentReading(AgentMetric.valueOf("SYSTEM_PSI_"+field.uppercase()),fields.getValue(field),source="/proc/pressure/io",component=component)
                    }.toMutableList()
                    val total = fields.getValue("total").toLong()
                    val old = previous.put("/proc/pressure/io|$component|total",now to total)
                    values += AgentReading(AgentMetric.SYSTEM_PSI_TOTAL,total.toDouble(),source="/proc/pressure/io",component=component)
                    values += if(old!=null && now-old.first in 1..maxGapMs && total>=old.second)
                        AgentReading(AgentMetric.SYSTEM_PSI_DELTA,(total-old.second).toDouble(),source="/proc/pressure/io",component=component)
                    else AgentReading.missing(AgentMetric.SYSTEM_PSI_DELTA,"BASELINE","/proc/pressure/io","UNAVAILABLE").copy(component=component)
                    values
                }
            }
            val blockMetrics = AgentMetric.entries.filter { it.name.startsWith("SYSTEM_") && !it.name.startsWith("SYSTEM_PSI_") }
            val names = discovered.filter(::physicalDevice).sorted().take(32)
            if(names.isEmpty()) {
                // Directory listing and explicit proc reads have independent SELinux rules.
                out += probe("/proc/diskstats",blockMetrics,now) { raw ->
                    val lines=raw.lineSequence().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }.toList()
                    require(lines.all { it.size>=14 })
                    lines.filter { physicalDevice(it[2]) }.take(32).flatMap { fields ->
                        block(parseBlock(fields.drop(3).joinToString(" ")),now,"/proc/diskstats",fields[2])
                    }.ifEmpty { blockMetrics.map { AgentReading.missing(it,"NOT_FOUND","/proc/diskstats") } }
                }
            }
            for(name in names) {
                val path = "/sys/block/$name/stat"
                var rows = probe(path,blockMetrics,now,name) { raw -> block(parseBlock(raw),now,path,name) }
                if(rows.none { it.status=="AVAILABLE" }) {
                    // Fallback keeps its provenance; only whole physical devices are selected.
                    val fallback = probe("/proc/diskstats",blockMetrics,now,name) { raw ->
                        val line = raw.lineSequence().map { it.trim().split(Regex("\\s+")) }
                            .firstOrNull { it.size>=14 && it[2]==name } ?: throw IllegalArgumentException("missing_device")
                        block(parseBlock(line.drop(3).joinToString(" ")),now,"/proc/diskstats",name)
                    }
                    if(fallback.any { it.status=="AVAILABLE" }) rows=fallback
                    else rows=rows+fallback
                }
                out+=rows
            }
        }
        if(wear) {
            val stored = flash?.takeIf { now<it.first }
            val result = stored?.second ?: discovered.filter { it.matches(Regex("mmcblk[0-9]+")) }.sorted().take(8).flatMap { name ->
                val path="/sys/block/$name/device/"
                probe(path+"life_time",listOf(AgentMetric.FLASH_LIFE),now,name) { raw ->
                    val values=hexValues(raw); require(values.size==2 && values.all { it in 0..11 })
                    values.mapIndexed { i,v -> if(v==0) AgentReading.missing(AgentMetric.FLASH_LIFE,"MISSING_VALUE",path+"life_time","UNAVAILABLE").copy(component="$name:${if(i==0) "A" else "B"}")
                        else AgentReading(AgentMetric.FLASH_LIFE,v.toDouble(),source=path+"life_time",component="$name:${if(i==0) "A" else "B"}") }
                } + probe(path+"pre_eol_info",listOf(AgentMetric.FLASH_EOL),now,name) { raw ->
                    val v=hexValues(raw).single(); require(v in 0..3)
                    listOf(if(v==0) AgentReading.missing(AgentMetric.FLASH_EOL,"MISSING_VALUE",path+"pre_eol_info","UNAVAILABLE").copy(component=name)
                    else AgentReading(AgentMetric.FLASH_EOL,v.toDouble(),source=path+"pre_eol_info",component=name))
                }
            }.ifEmpty { listOf(AgentMetric.FLASH_LIFE,AgentMetric.FLASH_EOL).map { AgentReading.missing(it,discoveryFailure?.second ?: "NOT_FOUND","/sys/block",discoveryFailure?.first ?: "UNSUPPORTED") } }
            // Retry refusals after 15 minutes, successful wear observations only daily.
            if(stored==null) flash=(now+if(result.any { it.status!="AVAILABLE" }) 900_000 else 86_400_000) to result
            if(stored==null) out+=result
        }
        return out
    }
    private fun block(fields: List<Long>, now: Long, source: String, name: String): List<AgentReading> {
        val old=selectedSources.put(name,source)
        if(old!=null && old!=source) previous.keys.removeAll { it.contains("|$name|") }
        return listOf(Triple(AgentMetric.SYSTEM_READ_BYTES,2,512.0),Triple(AgentMetric.SYSTEM_WRITE_BYTES,6,512.0),
            Triple(AgentMetric.SYSTEM_READ_OPS,0,1.0),Triple(AgentMetric.SYSTEM_WRITE_OPS,4,1.0),
            Triple(AgentMetric.SYSTEM_IO_MS,9,1.0),Triple(AgentMetric.SYSTEM_WEIGHTED_MS,10,1.0))
            .flatMap { (m,i,scale) -> counter(m,fields[i],now,source,name,scale) }
    }
    companion object {
        fun physicalDevice(name: String) = name.matches(Regex("mmcblk[0-9]+|sd[a-z]+|nvme[0-9]+n[0-9]+"))
        fun parseProcess(raw: String): Map<String,Long> {
            val values=raw.lineSequence().filter { it.isNotBlank() }.associate {
                val pair=it.split(':',limit=2); require(pair.size==2)
                pair[0] to pair[1].trim().toLong().also { v-> require(v>=0) }
            }
            require(values.keys.containsAll(listOf("read_bytes","write_bytes","cancelled_write_bytes","rchar","wchar","syscr","syscw")))
            return values
        }
        fun parseBlock(raw: String): List<Long> = raw.trim().split(Regex("\\s+")).map { it.toLong().also { v-> require(v>=0) } }.also { require(it.size>=11) }
        fun hexValues(raw: String) = raw.trim().split(Regex("\\s+")).map { it.removePrefix("0x").toInt(16) }
        fun parsePsi(raw: String): Map<String,Map<String,Double>> {
            val result=raw.lineSequence().filter { it.isNotBlank() }.associate { line ->
                val parts=line.trim().split(Regex("\\s+")); require(parts.first() in listOf("some","full"))
                val fields=parts.drop(1).associate { entry -> val p=entry.split('='); require(p.size==2); p[0] to p[1].toDouble() }
                require(listOf("avg10","avg60","avg300","total").all { fields[it]?.let { v-> v.isFinite() && v>=0 && (it=="total" || v<=100) }==true })
                parts.first() to fields
            }
            require(result.containsKey("some")); return result
        }
    }
}
