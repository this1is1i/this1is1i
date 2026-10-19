import json
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import release_preview as release


class ReleasePreviewTest(unittest.TestCase):
    def partial_fixture(self, case):
        fixture = release.mysql_sample.Fixture.__new__(release.mysql_sample.Fixture)
        fixture.path, fixture.server, fixture.port = case, case / 'mysqld.exe', 12345
        fixture.process = Mock(pid=123)
        fixture.process.args = [str(fixture.server), '--no-defaults', '--datadir=' + str(case / 'data'),
                                '--port=12345', '--bind-address=127.0.0.1', '--mysqlx=OFF']
        fixture.process.poll.side_effect = lambda: None if fixture.process.terminate.call_count == 0 else 1
        fixture.manifest = {'id': 'owned-attempt', 'state': 'STARTING', 'pid': 123, 'port': 12345}
        fixture.save_manifest()
        fixture.log = io.StringIO()
        fixture.require_identity = Mock(side_effect=RuntimeError('SQL not ready'))
        return fixture

    def test_unavailable_mysql_identity_never_kills_only_the_launcher_or_claims_cleanup(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.runtime') as folder:
            case = Path(folder)
            fixture = self.partial_fixture(case)
            with self.assertRaisesRegex(RuntimeError, 'cleanup unconfirmed'):
                release.close_database(fixture)
            fixture.process.terminate.assert_not_called()
            fixture.process.wait.assert_not_called()
            self.assertEqual('STARTING', fixture.manifest['state'])
            self.assertTrue((case / 'manifest.json').is_file())
            self.assertEqual({'cleanupState': 'UNCONFIRMED', 'databaseStopped': None},
                             json.loads((case / 'cleanup-unknown.json').read_text(encoding='utf-8')))

    def test_failed_start_never_reports_the_absence_of_returned_resources_as_stopped(self):
        for invalid in [ROOT, ROOT / '.runtime', ROOT.parent / 'external-case', ROOT / 'samples/workspaces/unused-case']:
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                release.owned_case(invalid)
        with tempfile.TemporaryDirectory(dir=ROOT / '.runtime') as folder:
            case = Path(folder)
            self.assertEqual(case.resolve(), release.owned_case(case))
            with patch.object(release, 'start_sample', side_effect=RuntimeError('cleanup uncertain')):
                with self.assertRaisesRegex(RuntimeError, 'cleanup uncertain'):
                    release.serve_sample(case, SimpleNamespace(candidate='candidate'))
            state = json.loads((case / 'sample-stopped.json').read_text(encoding='utf-8'))
            self.assertIsNone(state['sampleStopped'])

    def test_owned_database_is_closed_even_if_start_is_partial(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.runtime') as folder:
            case = Path(folder)
            (case / 'urls.json').write_text(json.dumps({'platformOrigin': 'http://127.0.0.1:1'}), encoding='utf-8')
            fixture = Mock()
            fixture.start.side_effect = RuntimeError('partial startup')
            detail = {'validation': {'state': 'PASSED', 'checks': [{'stdout': json.dumps({'app': str(ROOT / 'samples/article-app')})}]}, 'currentlyValid': True}
            args = SimpleNamespace(candidate='exact-candidate', mysqld='mysqld', mysql='mysql')
            with patch.object(release, 'request', return_value=detail), patch.object(release.mysql_sample, 'Fixture', return_value=fixture):
                with self.assertRaisesRegex(RuntimeError, 'partial startup'):
                    release.start_sample(case, args)
            fixture.close.assert_called_once()

    def test_cleanup_runs_all_callbacks_when_one_stop_fails(self):
        events = []
        fixture, child, log, web = Mock(), Mock(), Mock(), Mock()
        child.poll.return_value = None
        for obj, name, method in [(web, 'web shutdown', 'shutdown'), (web, 'web close', 'server_close'),
                                  (child, 'terminate', 'terminate'), (child, 'wait', 'wait'),
                                  (log, 'log close', 'close'), (fixture, 'database close', 'close')]:
            getattr(obj, method).side_effect = lambda *a, event=name, **k: events.append(event)
        web.shutdown.side_effect = lambda: (events.append('web shutdown'), (_ for _ in ()).throw(RuntimeError('stop failed')))
        with self.assertRaisesRegex(RuntimeError, 'stop failed'):
            release.close_business((fixture, child, log, web))
        self.assertEqual(['web shutdown', 'web close', 'terminate', 'wait', 'log close', 'database close'], events)

    def test_proxy_start_failure_cleans_up_the_already_started_article_child(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.runtime') as folder:
            case = Path(folder)
            (case / 'urls.json').write_text(json.dumps({'platformOrigin': 'http://127.0.0.1:1'}), encoding='utf-8')
            db = case / 'database'
            db.mkdir()
            (db / 'application.properties').write_text('\n'.join(key + '=fixture' for key in [
                'article.fixture.source.url', 'article.fixture.source.username', 'article.fixture.source.password',
                'spring.datasource.url', 'spring.datasource.username', 'spring.datasource.password']), encoding='utf-8')
            fixture = Mock(path=db)
            child = Mock()
            child.poll.return_value = None
            def launch(*args, **kwargs):
                kwargs['stdout'].write('Tomcat started on port 12345\n')
                kwargs['stdout'].flush()
                return child
            detail = {'validation': {'state': 'PASSED', 'checks': [{'stdout': json.dumps({
                'app': str(ROOT / 'samples/article-app')})}]}, 'currentlyValid': True}
            args = SimpleNamespace(candidate='exact-candidate', mysqld='mysqld', mysql='mysql', java='java')
            with patch.object(release, 'request', return_value=detail), patch.object(release.mysql_sample, 'Fixture', return_value=fixture), \
                 patch.object(Path, 'glob', side_effect=lambda *a: iter([case / 'library.jar'])), \
                 patch.object(release.sample, 'run', side_effect=['hash', '{"fingerprint":"snapshot"}', '{"imported":7}']), \
                 patch.object(release.shutil, 'copy2'), patch.object(release.subprocess, 'Popen', side_effect=launch) as popen, \
                 patch.object(release, 'proxy', side_effect=RuntimeError('proxy startup')):
                with self.assertRaisesRegex(RuntimeError, 'proxy startup'):
                    release.start_sample(case, args)
            fixture.close.assert_called_once()
            child.terminate.assert_called_once()
            child.wait.assert_called_once_with(timeout=20)
            self.assertTrue(popen.call_args.kwargs['stdout'].closed)


if __name__ == '__main__':
    unittest.main()
