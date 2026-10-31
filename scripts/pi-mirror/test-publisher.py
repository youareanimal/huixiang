"""Run only in GitHub Actions; never build or execute APK/source artifacts."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('publisher',Path(__file__).with_name('sync-release.py'))
publisher=importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublisherSafeguards(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.dest=self.root/'public';self.dest.mkdir()
        self.state=self.root/'state';self.state.mkdir()
        baseline=self.stage(14,'0.5.1','original')
        self.baseline=json.loads((baseline/'latest.json').read_text())
        for item in baseline.iterdir():
            if item.name!='deployment.json':(self.dest/item.name).write_bytes(item.read_bytes())
        self.original={p.name:p.read_bytes() for p in self.dest.iterdir()}

    def stage(self, code, version, suffix):
        stage=self.root/('stage-'+str(code)+'-'+suffix);stage.mkdir()
        hashes={};artifacts={}
        for abi in ['arm64-v8a','armeabi-v7a','universal']:
            name='HuiXiang-'+version+'-preview'+('' if abi=='universal' else '-'+abi)+'.apk'
            data=(abi+suffix).encode();(stage/name).write_bytes(data)
            digest=hashlib.sha256(data).hexdigest();hashes[name]=digest
            artifacts[abi]={'url':publisher.BASE+'/'+name,'bytes':len(data),'sha256':digest}
        source='HuiXiang-'+version+'-source.zip';(stage/source).write_bytes(b'verified-source-fixture')
        hashes[source]=publisher.sha(stage/source)
        feed={'schemaVersion':1,'packageName':'app.shengqiao.ts.debug','versionCode':code,'versionName':version+'-preview','artifacts':artifacts,'sourceUrl':publisher.BASE+'/'+source,'minSdk':29,'notes':'fixture'}
        (stage/'latest.json').write_text(json.dumps(feed))
        proof={'repository':'youareanimal/huixiang','workflow':'.github/workflows/huixiang-build.yml','runId':1234,'commit':'a'*40,'manifestSha256':publisher.sha(stage/'latest.json'),'assetHashes':hashes}
        (stage/'deployment.json').write_text(json.dumps(proof))
        return stage

    def run_publish(self, stage):
        with contextlib.redirect_stdout(io.StringIO()):publisher.publish(stage,self.dest,self.state)

    def assert_original_intact(self):
        for name,data in self.original.items():self.assertEqual((self.dest/name).read_bytes(),data)

    def test_new_release_keeps_old_assets_and_history(self):
        stage=self.stage(15,'0.5.2','new')
        self.run_publish(stage)
        self.assertEqual(json.loads((self.dest/'latest.json').read_text())['versionCode'],15)
        for name,data in self.original.items():
            if name!='latest.json':self.assertEqual((self.dest/name).read_bytes(),data)
        self.assertEqual(len(list((self.state/'history').glob('*.json'))),2)
        self.run_publish(stage)  # Identical retry is idempotent.

    def test_rollback_is_rejected_without_writes(self):
        with self.assertRaisesRegex(ValueError,'rollback'):self.run_publish(self.stage(13,'0.5.0','older'))
        self.assert_original_intact()

    def test_same_version_with_different_hash_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'same versionCode'):self.run_publish(self.stage(14,'0.5.1','different'))
        self.assert_original_intact()

    def test_conflicting_immutable_filename_preflight(self):
        stage=self.stage(15,'0.5.2','new')
        name=next(p.name for p in stage.glob('*.apk'))
        (self.dest/name).write_bytes(b'existing-must-survive')
        with self.assertRaisesRegex(ValueError,'immutable'):self.run_publish(stage)
        self.assertEqual((self.dest/name).read_bytes(),b'existing-must-survive')
        self.assert_original_intact()
        self.assertEqual(len(list(self.dest.glob('*.apk'))),4)

    def test_source_hash_mismatch_is_rejected(self):
        stage=self.stage(15,'0.5.2','new');next(stage.glob('*-source.zip')).write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError,'digest mismatch'):self.run_publish(stage)
        self.assert_original_intact()

    def test_crash_recovery_respects_archived_high_water(self):
        history=self.state/'history';history.mkdir()
        future=json.loads((self.stage(16,'0.5.3','future')/'latest.json').read_text())
        (history/'future.json').write_text(json.dumps(future))
        with self.assertRaisesRegex(ValueError,'high-water'):self.run_publish(self.stage(15,'0.5.2','middle'))
        self.assert_original_intact()


if __name__=='__main__':unittest.main()
