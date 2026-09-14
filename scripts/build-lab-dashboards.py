#!/usr/bin/env python3
"""Provision EN/RU dashboards with help sourced from the APK resource catalog."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'deploy/otel-lab/grafana/dashboards'
DS = {'type': 'prometheus', 'uid': 'ahwotel-prometheus'}
FILTER = 'device_id=~"$device",monitoring_session_id=~"$session"'
HELP_SECTIONS = ('meaning','units','impact','reading','example','limits','state','source')


def build(lang, help_dashboard=False):
    ru = lang == 'ru'
    def tr(en, russian): return russian if ru else en
    strings = {}
    for file in (ROOT / ('app/src/main/res/values-ru' if ru else 'app/src/main/res/values')).glob('*.xml'):
        for item in ET.parse(file).getroot().findall('string'):
            strings[item.attrib['name']] = ''.join(item.itertext()).replace('\\n', '\n').replace("\\'", "'")
    def help_text(prefix):
        if prefix.startswith('at_'):
            return strings[prefix + '_help']
        headings = (('Смысл','Единицы','Влияние','Интерпретация','Пример','Ограничения','Performance State','Источник') if ru else
                    ('Meaning','Units','Impact','Interpretation','Example','Limitations','Performance State','Source'))
        return '\n\n'.join('### '+heading+'\n\n'+strings[prefix+'_'+section]
                            for section,heading in zip(HELP_SECTIONS,headings))
    panels = []
    help_panels = []
    y = 0
    def row(en, russian):
        nonlocal y
        panels.append({'id':len(panels)+1,'type':'row','title':tr(en,russian),'collapsed':False,
                       'gridPos':{'x':0,'y':y,'w':24,'h':1}})
        y += 1
    def panel(en, russian, expr, unit, description='', table=False, x=0, width=12, decimals=None, maximum=None):
        nonlocal y
        help_id = len(help_panels)+1
        help_url = '/d/ahwotel-help-'+lang+'?viewPanel=panel-'+str(help_id)
        help_panels.append({'id':help_id,'type':'text','title':tr(en,russian),
            'gridPos':{'x':0,'y':(help_id-1)*20,'w':24,'h':20},
            'options':{'mode':'markdown','content':description},
            'links':[{'title':tr('Back to charts','К графикам'),'url':'/d/ahwotel-'+lang,'targetBlank':False}]})
        # A small hover disclosure plus an explicit link: full help remains scrollable on phones.
        summary = description.split('\n\n')[1] if description.startswith('### ') else description.split('\n')[0]
        if len(summary)>170: summary=summary[:167].rsplit(' ',1)[0]+'…'
        if description.startswith('### '):
            units = description.split('\n\n')[3].split('. ')[0]
            if len(units)>80: units=units[:77].rsplit(' ',1)[0]+'…'
            summary += '\n\n'+tr('Units: ','Единицы: ')+units
        short_help = summary+'\n\n['+tr('Read more','Подробнее')+']('+help_url+')'
        legend = ('{{__name__}} · {{component}} · {{monitoring_session_id}}' if expr.startswith('{__name__=~')
                  else '{{monitoring_session_id}} · {{component}} {{source}} {{status}}')
        if FILTER not in expr: legend = '{{job}} {{exporter}}'
        panels.append({'id':len(panels)+1,'type':'table' if table else 'timeseries','title':tr(en,russian),
            'description':short_help,'links':[{'title':tr('Read more','Подробнее'),'url':help_url,'targetBlank':False}],
            'datasource':DS,'gridPos':{'x':x,'y':y,'w':width,'h':8},
            'targets':[{'refId':'A','expr':expr,'legendFormat':'' if table else legend,
                        'format':'table' if table else 'time_series','instant':table,'range':not table}],
            'fieldConfig':{'defaults':{'unit':'none' if table else unit,'noValue':tr('No observations','Нет наблюдений'),
                **({'decimals':decimals} if decimals is not None else {}),
                **({'min':0,'max':maximum} if maximum is not None else {}),
                'custom':{'drawStyle':'line','lineInterpolation':'linear','lineWidth':2,'fillOpacity':5,
                          'spanNulls':False,'axisLabel':unit,'axisWidth':90,'showPoints':'auto'}},'overrides':[]},
            'options':{'legend':{'displayMode':'list','placement':'bottom'},'tooltip':{'mode':'multi'},'showHeader':True}})
        if table:
            fields=['Value','metric','__name__','status','reason','source','measurement_scope','quality','component','device_id','monitoring_session_id']
            panels[-1]['transformations']=[{'id':'labelsToFields','options':{'mode':'columns'}},
                {'id':'filterFieldsByName','options':{'include':{'names':fields}}},
                {'id':'organize','options':{'indexByName':{name:i for i,name in enumerate(fields)}}}]
            titles={'Value':tr('Observed at','Время наблюдения') if unit=='dateTimeAsIso' else tr('Value','Значение'),
                    'metric':tr('Metric','Показатель'),'__name__':tr('Metric','Показатель'),
                    'status':tr('Status','Статус'),'reason':tr('Reason','Причина'),'source':tr('Source','Источник'),
                    'measurement_scope':tr('Measurement scope','Область измерения'),
                    'quality':tr('Quality','Качество'),'component':tr('Component','Компонент'),
                    'device_id':tr('Device','Устройство'),'monitoring_session_id':tr('Session','Сессия')}
            panels[-1]['fieldConfig']['overrides']=[{'matcher':{'id':'byName','options':name},
                'properties':[{'id':'displayName','value':title},{'id':'unit','value':unit if name=='Value' else 'string'}]}
                for name,title in titles.items()]
        if x+width>=24: y+=8
    def metric(name): return name+'{'+FILTER+'}'
    row('Device resources','Ресурсы устройства')
    panel('Available RAM','Доступная RAM',metric('device_memory_available_bytes'),'bytes',help_text('help_memory_available'))
    panel('Free storage','Свободное хранилище',metric('device_storage_available_bytes'),'bytes',help_text('help_storage_available'),x=12,decimals=4)
    panel('Probe CPU wait','Ожидание CPU пробой',metric('agent_cpu_runqueue_wait_milliseconds'),'ms',help_text('help_cpu_wait'))
    panel('Probe scheduling delay','Задержка запуска пробы',metric('agent_scheduling_delay_milliseconds'),'ms',help_text('help_probe_delay'),x=12)
    panel('System CPU, when supported','CPU системы, если доступен',metric('device_cpu_utilization_percent'),'percent',help_text('help_cpu'))
    panel('Thermal status','Тепловой статус',metric('device_thermal_status_ratio'),'short',help_text('help_thermal'),x=12)
    row('Battery','Батарея')
    panel('Battery level','Заряд батареи',metric('device_battery_level_percent'),'percent',help_text('at_level'),maximum=100)
    panel('Battery temperature','Температура батареи',metric('device_battery_temperature_celsius'),'celsius',help_text('at_temp'),x=12)
    panel('Battery health / wear observations','Наблюдения состояния и износа батареи',
          'last_over_time({__name__=~"device_battery_(soh_percent|cycle_count_ratio|full_charge_capacity_uAh|design_capacity_uAh)",'+FILTER+'}[$__range])',
          'short',tr('Last observation in the selected period; see observation time below. Values have different units: SOH %, cycles count, capacity µAh. Unsupported values are absent.',
                     'Последнее наблюдение за выбранный период; время наблюдения указано ниже. Единицы: SOH %, число циклов, ёмкость мкА·ч. Недоступные значения отсутствуют.'),table=True,width=24)
    row('Monitoring the monitor','Мониторинг мониторинга')
    panel('Agent CPU estimate','Оценка CPU агента',metric('agent_cpu_percent'),'percent',help_text('at_cpu_percent'))
    panel('Agent PSS','Память PSS агента',metric('agent_memory_pss_bytes'),'bytes',help_text('at_pss'),x=12)
    panel('Agent CPU per observation window','CPU агента за окно',metric('agent_cpu_delta_sum_milliseconds'),'ms',help_text('at_cpu_delta'))
    panel('Upload outcomes per window','Результаты отправки за окно','{__name__=~"agent_upload_(success|failure|retry)_sum_ratio",'+FILTER+'}',
          'short',tr('Window aggregates are gauges, not cumulative counters. No rate() is applied.',
                     'Агрегаты за окно передаются как gauge, а не накопительные счётчики. rate() не применяется.'),x=12)
    row('Availability and OEM','Доступность и OEM')
    for expr,en,ru_title in [('agent_telemetry_availability','Battery / Self: last observation and status','Battery / Self: последнее наблюдение и статус'),
                             ('agent_oem_capability','OEM: last observation and status','OEM: последнее наблюдение и статус')]:
        panel(en,ru_title,'1000 * topk by (device_id,monitoring_session_id,metric,component,source,measurement_scope) (1, ts_of_last_over_time('+metric(expr)+'[$__range]))',
              'dateTimeAsIso',tr('Value is the last observation time for each source within (period start, period end]. Android and Knox remain separate. Labels preserve status, reason, scope and quality. UNSUPPORTED is not zero; an old observation is not proof of current availability.',
                                'Значение — время последнего наблюдения каждого источника в диапазоне (начало периода, конец периода]. Android и Knox показаны отдельно. Метки содержат статус, причину, область измерения и качество. UNSUPPORTED не равен нулю; старое наблюдение не доказывает текущую доступность.'),table=True,width=24)
    panel('OEM agent memory','OEM: память агента',metric('agent_oem_agent_memory_pss_bytes'),'bytes',
          tr('Process PSS from the reported source. Compare the same device and profile. This is not free device RAM.',
             'PSS процесса из указанного источника. Сравнивайте одно устройство и профиль. Это не свободная RAM устройства.'))
    panel('Base collector support','Поддержка базовых сборщиков','{__name__=~"device_.*_supported",'+FILTER+'}',
          'short',tr('1: available or warming up; 0: explicitly unsupported. Missing observations remain missing.',
                     '1: доступен или прогревается; 0: явно не поддерживается. Отсутствие наблюдений остаётся отсутствием данных.'),x=12)
    row('Telemetry pipeline','Доставка телеметрии')
    panel('Collector queue','Очередь Collector','otelcol_exporter_queue_size','short',
          tr('Accepted requests waiting for export. Sustained growth indicates downstream delivery delay.',
             'Принятые запросы, ожидающие экспорта. Устойчивый рост указывает на задержку доставки.'))
    panel('Collector export failures','Ошибки экспорта Collector','rate(otelcol_exporter_send_failed_metric_points[5m])','ops',
          tr('Collector counter rate, not an APK window gauge. Check queue and Prometheus readiness.',
             'Скорость роста счётчика Collector, не оконный gauge APK. Проверьте очередь и готовность Prometheus.'),x=12)
    panel('Collector / Prometheus readiness','Доступность Collector / Prometheus','up{job=~"collector|prometheus"}','short',
          tr('Scrape success only; end-to-end delivery requires device data and a draining queue.',
             'Только успешность scrape; сквозная доставка подтверждается данными устройства и опустошением очереди.'),width=24)
    variables=[]
    for name,label,query in [('device',tr('Device','Устройство'),'label_values({job="ahwotel"},device_id)'),
                             ('session',tr('Session','Сессия'),'label_values({job="ahwotel",device_id=~"$device"},monitoring_session_id)')]:
        variables.append({'name':name,'label':label,'type':'query','datasource':DS,'query':query,'refresh':2,
                          'includeAll':True,'allValue':'.*','multi':False,'current':{'text':'All','value':'$__all'}})
    if help_dashboard:
        return {'uid':'ahwotel-help-'+lang,'title':'AHWOTel · '+tr('Help','Справка'),'schemaVersion':39,
                'version':1,'editable':False,'tags':['AHWOTel',lang],'panels':help_panels,
                'links':[{'title':tr('Back to charts','К графикам'),'type':'link','url':'/d/ahwotel-'+lang}]}
    return {'uid':'ahwotel-'+lang,'title':'AHWOTel · '+('Русский' if ru else 'English'),'schemaVersion':39,
            'version':1,'editable':False,'timezone':'browser','refresh':'15s','time':{'from':'now-30m','to':'now'},
            'tags':['AHWOTel',lang],'templating':{'list':variables},'panels':panels,
            'links':[{'title':tr('Русский','English'),'type':'link','url':'/d/ahwotel-'+('en' if ru else 'ru'),'keepTime':True,'includeVars':True}]}


if __name__=='__main__':
    for lang in ('en','ru'):
        (OUT/('ahwotel-'+lang+'.json')).write_text(json.dumps(build(lang),ensure_ascii=False,indent=2)+'\n')
        (OUT/('ahwotel-help-'+lang+'.json')).write_text(json.dumps(build(lang,True),ensure_ascii=False,indent=2)+'\n')
    print('Generated EN/RU lab dashboards')
