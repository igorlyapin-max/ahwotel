import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('checker', Path(__file__).with_name('check-mtls-android.py'))
checker = importlib.util.module_from_spec(spec); spec.loader.exec_module(checker)

class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.run = dict(passed=True, startedAt=1, finishedAt=2, runId='new', serial='device', endpoint='https://collector')
        self.expected = dict(runId='new', endpoint='https://collector', deviceId='d', sessionId='s',
                             points=[dict(time=1000*i,value=float(i)) for i in range(1,7)])
        self.result = dict(status='success',data=dict(result=[dict(metric=dict(device_id='d',monitoring_session_id='s'),
                                      values=[(i,str(i)) for i in range(1,7)])]))
    def test_current_complete_run(self):
        checker.validate_run(self.run,self.expected,'device')
        self.assertTrue(checker.matches(self.expected,self.result))
    def test_old_failed_wrong_endpoint_and_incomplete_evidence(self):
        for key,value in [('passed',False),('runId','old'),('serial','other'),('endpoint','https://other')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                checker.validate_run(dict(self.run,**{key:value}),self.expected,'device')
        with self.assertRaises(AssertionError):
            checker.validate_run(self.run,dict(self.expected,points=self.expected['points'][:1]),'device')
    def test_missing_changed_or_wrong_session_points_fail(self):
        for kind in ('missing','value','session','failed'):
            result=copy.deepcopy(self.result); row=result['data']['result'][0]
            if kind=='missing': row['values'].pop()
            if kind=='value': row['values'][2]=(3,'999')
            if kind=='session': row['metric']['monitoring_session_id']='other'
            if kind=='failed': result['status']='error'
            self.assertFalse(checker.matches(self.expected,result),kind)

if __name__=='__main__': unittest.main()
