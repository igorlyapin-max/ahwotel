#!/usr/bin/env python3
"""Assert APK protobuf -> Collector -> stored Prometheus samples, including original timestamps."""
import argparse
import json
import time
import urllib.parse
import urllib.request
from otel_lab import ROOT, environment, compose, health

HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restart-queue',action='store_true')
    args=parser.parse_args()
    env=environment()
    directory=ROOT/'app/build/lab-wire'
    expected=json.loads((directory/'expected.json').read_text())
    t=expected['time']/1000
    endpoint=f"http://{env['LAB_BIND_ADDRESS']}:{env['OTLP_PORT']}/v1/metrics"
    prom=f"http://127.0.0.1:{env['PROMETHEUS_PORT']}"

    def send(name):
        headers={'Content-Type':'application/x-protobuf'}
        if name.endswith('.gz'): headers['Content-Encoding']='gzip'
        request=urllib.request.Request(endpoint,(directory/name).read_bytes(),headers,method='POST')
        with HTTP.open(request,timeout=15) as response:
            assert response.status==200
            # This pinned collector emits the empty partial_success message on full acceptance.
            assert response.read() in (b'',b'\x0a\x00'), 'Collector did not fully accept the fixture'

    def series():
        query='{device_id="'+expected['deviceId']+'"}[1h]'
        with HTTP.open(prom+'/api/v1/query?'+urllib.parse.urlencode({'query':query,'time':t+10}),timeout=10) as response:
            result=json.load(response)
            assert result['status']=='success', result
            return result['data']['result']

    def verify(rows, recovery=False):
        def points(name):
            found=[r for r in rows if r['metric']['__name__']==name]
            assert len(found)==1, (name,'expected one stable time series',found)
            assert found[0]['metric']['monitoring_session_id']==expected['sessionId']
            assert 'window_start' not in found[0]['metric']
            return [[float(a),float(b)] for a,b in found[0]['values']]
        assert points('device_memory_available_bytes')==[[t,123456.0],[t+2,123457.0]]+([[t+4,123458.0]] if recovery else [])
        assert points('device_battery_level_percent')==[[t,70.0],[t+2,71.0]]
        assert points('agent_cpu_delta_milliseconds')==[[t,70.0],[t+2,71.0]]
        assert points('agent_oem_agent_memory_pss_bytes')==[[t,234567.0],[t+2,234568.0]]
        assert points('device_battery_cycle_count_ratio')==[[t,100.0]]
        assert points('device_battery_full_charge_capacity_uAh')==[[t,2000000.0]]
        assert points('device_battery_design_capacity_uAh')==[[t,3000000.0]]
        assert not [r for r in rows if r['metric']['__name__']=='device_battery_soh_percent']
        assert any(r['metric'].get('metric')=='device.battery.soh' and r['metric'].get('status')=='UNSUPPORTED' for r in rows)

    def wait_for_samples(recovery=False):
        deadline=time.monotonic()+60
        while True:
            rows=series()
            try:
                verify(rows,recovery)
                return rows
            except AssertionError:
                if time.monotonic()>=deadline:
                    print(json.dumps(rows,indent=2))
                    raise
                time.sleep(1)

    # Newer base point first: delayed offline samples must not disappear as out-of-order.
    for name in ['base-1.pb','base-0.pb','battery.pb.gz','self.pb.gz','wear.pb.gz','wear-known.pb.gz','oem.pb']:
        send(name)
    rows=wait_for_samples()
    if args.restart_queue:
        compose(env,'stop','prometheus')
        try:
            send('base-2.pb')
            compose(env,'restart','collector')
        finally:
            compose(env,'start','prometheus')
        health(env)
        rows=wait_for_samples(True)
    result={**expected,'passed':True,'durableQueueRestart':args.restart_queue,'series':rows,
            'checks':['four APK streams','gzip','original timestamps','out-of-order points','stable series','unsupported != zero']}
    out=ROOT/'artifacts/otel-lab'
    out.mkdir(parents=True,exist_ok=True)
    (out/'wire-smoke.json').write_text(json.dumps(result,indent=2)+'\n')
    print(f"PASS: four APK streams, {len(rows)} series, timestamps and availability; durable restart={args.restart_queue}")


if __name__=='__main__': main()
