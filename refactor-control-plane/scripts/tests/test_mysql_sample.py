import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class MySqlFixtureBoundaryTest(unittest.TestCase):
    def load(self):
        script = ROOT / 'scripts/mysql_sample.py'
        self.assertTrue(script.is_file(), 'Project-scoped MySQL fixture runner is required')
        spec = importlib.util.spec_from_file_location('mysql_sample', script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_rejects_external_existing_and_parent_destinations_before_side_effects(self):
        module = self.load()
        with tempfile.TemporaryDirectory(dir=ROOT / '.runtime') as existing:
            for destination in [ROOT, ROOT / '.runtime', Path(existing), Path(existing).parent.parent.parent / 'external-mysql']:
                with self.assertRaises(ValueError):
                    module.new_destination(destination)
        destination = ROOT / '.runtime/mysql-boundary-new'
        self.assertEqual(destination.resolve(), module.new_destination(destination))
        self.assertFalse(destination.exists())

    def test_extracts_only_the_requested_statement_without_splitting_quoted_semicolons(self):
        module=self.load()
        sql="CREATE TABLE IF NOT EXISTS `posts` (id INT);\nINSERT INTO `posts` VALUES (1,'a;b''c'),(2,'d\\\'e;f');\nDROP TABLE other;"
        self.assertEqual("INSERT INTO `posts` VALUES (1,'a;b''c'),(2,'d\\\'e;f');",module.statement(sql,'INSERT INTO `posts`'))
        with self.assertRaises(ValueError):
            module.statement("INSERT INTO `posts` VALUES ('unclosed",'INSERT INTO `posts`')

    def test_client_probe_uses_a_project_local_login_file_and_a_bounded_timeout(self):
        module=self.load()
        with patch.object(module.subprocess,'run') as run:
            module.execute(['mysql','--version'])
            environment=run.call_args.kwargs['env']
            self.assertTrue('MYSQL_TEST_LOGIN_FILE' in environment,'Child client must ignore unrelated login configuration')
            self.assertTrue(Path(environment['MYSQL_TEST_LOGIN_FILE']).is_relative_to(ROOT/'.runtime'))

    def test_runtime_junction_cannot_redefine_the_workspace_boundary(self):
        module=self.load();original=Path.resolve;outside=ROOT.parent/'simulated-external'
        def resolve(path,*args,**kwargs):
            if path.is_relative_to(ROOT/'.runtime'):
                return outside/path.relative_to(ROOT/'.runtime')
            return original(path,*args,**kwargs)
        with patch.object(Path,'resolve',resolve):
            with self.assertRaises(ValueError): module.new_destination(ROOT/'.runtime/new-mysql')

    def test_external_build_output_is_rejected_before_database_initialization(self):
        module=self.load();original=Path.resolve;output=ROOT/'samples/article-app/backend/target'
        def resolve(path,*args,**kwargs):
            return ROOT.parent/'simulated-external-output' if path==output else original(path,*args,**kwargs)
        with tempfile.TemporaryDirectory(dir=ROOT/'.runtime') as folder:
            with patch.object(Path,'resolve',resolve), patch.object(module,'execute',return_value=SimpleNamespace(returncode=0,stdout='Ver 8.0.31 --defaults-file')), patch.object(module.subprocess,'run',side_effect=AssertionError('Unsafe initialization was attempted')):
                with self.assertRaises(ValueError):
                    fixture=module.Fixture(Path(folder)/'instance',sys.executable,sys.executable)
                    try: fixture.start()
                    finally: fixture.close()

    def test_stale_green_reports_cannot_turn_a_skipped_run_into_pass_evidence(self):
        module=self.load()
        suite=ET.ElementTree(ET.fromstring('<testsuite tests="3" failures="0" errors="0" skipped="0"/>'))
        with tempfile.TemporaryDirectory(dir=ROOT/'.runtime') as folder:
            fixture=SimpleNamespace(path=Path(folder),app=ROOT/'samples/article-app',manifest={'serverVersion':'fixture','sourceSqlSha256':'a','sourceRows':7})
            with patch.dict(sys.modules,{'article_sample':SimpleNamespace(run=lambda *args:None)}), patch.object(module.ET,'parse',return_value=suite):
                with self.assertRaises(RuntimeError): module.verify(fixture)


if __name__ == '__main__':
    unittest.main()
