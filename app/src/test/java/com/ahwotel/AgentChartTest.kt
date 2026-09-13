package com.ahwotel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class AgentChartTest {
    private fun row(time: Long,value: Double,component: String="",metric: String="LEVEL") =
        TelemetryRecord(sessionId="s",stream="battery",metric=metric,component=component,time=time,start=time,durationMs=0,
            segment=0,value=value,low=value,high=value,count=1,source="test",metadata="{}")
    @Test fun pagedHistoryRetainsBothEndsAndEveryComponentBeyondOneThousandRows()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MonitorDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.agentDao().insert((0..1440).flatMap { i->listOf(row(i*60000L,i.toDouble(),"a"),row(i*60000L,i.toDouble(),"b")) })
            val series=loadAgentChart(db.agentDao(),AgentMetric.LEVEL,0,86400000)
            assertEquals(setOf("a","b"),series.map { it.component }.toSet())
            series.forEach { s->
                assertEquals(240,s.points.size); assertEquals(1441,s.points.sumOf { it.count })
                assertEquals(0.0,s.points.first().low!!,0.0); assertEquals(1440.0,s.points.last().high!!,0.0)
            }
        } finally { db.close() }
    }
    @Test fun gaugeIsWeightedWhileSumAndMaximumKeepTheirOwnSemantics() {
        fun point(metric: AgentMetric): ChartBucket {
            val a=AgentChartAccumulator(metric,0,1000,1)
            a.add(row(1,100.0).copy(sum=200.0,count=2,low=90.0,high=110.0))
            a.add(row(2,300.0).copy(sum=900.0,count=3,low=200.0,high=400.0))
            return a.finish().single().points.single()
        }
        assertEquals(220.0,point(AgentMetric.LEVEL).mean!!,0.0)
        assertEquals(400.0,point(AgentMetric.CPU_DELTA).mean!!,0.0)
        assertEquals(300.0,point(AgentMetric.WAKE_MAX).mean!!,0.0)
        assertEquals(90.0,point(AgentMetric.CPU_DELTA).low!!,0.0)
    }
    @Test fun unavailableSegmentsAndNominalTransitionsCannotBecomeContinuousLines() {
        val state=AgentChartAccumulator(AgentMetric.PLUG,0,1000,1)
        state.add(row(1,1.0));state.add(row(2,2.0))
        val mixed=state.finish().single();assertEquals(setOf(0L),mixed.mixed);assertNull(mixed.points.single().mean)
        for(other in listOf(row(2,10.0).copy(status="UNAVAILABLE",value=null),row(2,10.0).copy(segment=1),row(2,10.0).copy(sessionId="next"))) {
            val gauge=AgentChartAccumulator(AgentMetric.LEVEL,0,1000,1)
            gauge.add(row(1,9.0));gauge.add(other)
            val result=gauge.finish().single();assertEquals(setOf(0L),result.gaps);assertEquals(-1,result.points.single().segment)
        }
    }
}
