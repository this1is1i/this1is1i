import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('article_sample', ROOT / 'scripts/article_sample.py')
sample = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sample)


class ArticleSampleTest(unittest.TestCase):
    @unittest.skipUnless(sample.os.name == 'nt', 'Windows Git long-path regression')
    def test_prepared_repository_supports_nested_task_worktree_paths(self):
        # Retain this owned fixture for diagnosis; do not remove worktrees outside Git.
        folder = Path(tempfile.mkdtemp(prefix='sample-longpath-', dir=ROOT / '.runtime'))
        sample.prepare(folder / 'managed')
        repo = folder / 'managed/repository'
        tree = folder / 'managed/.refactor-worktrees' / ('exec-scaffold-' + 'a' * 49)
        config = folder / 'simulated-system.gitconfig'
        config.write_text('[core]\n autocrlf = true\n', encoding='utf-8')
        environment = sample.os.environ.copy()
        environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=str(config))
        sample.subprocess.run(['git', '-C', str(repo), 'worktree', 'add', '--detach', str(tree), sample.PIN],
                              env=environment, check=True, capture_output=True, timeout=30)
        self.assertTrue((tree / 'Blog/src/main/java/com/springframework/mvc/configuration/MaskFormatAnnotationFormatterFactory.java').is_file())
        self.assertEqual(sample.PIN, sample.git(repo, 'rev-parse', 'main'))
        self.assertEqual('', sample.git(tree, 'status', '--porcelain'))

    def test_existing_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='sample-test-', dir=ROOT / '.runtime') as folder:
            target = Path(folder)
            marker = target / 'user.txt'
            marker.write_text('keep me', encoding='utf-8')
            with self.assertRaises(ValueError):
                sample.prepare(target)
            self.assertEqual('keep me', marker.read_text(encoding='utf-8'))

    def test_materialization_requires_real_owned_worktree_and_empty_destination(self):
        with tempfile.TemporaryDirectory(prefix='sample-test-', dir=ROOT / '.runtime') as folder:
            with self.assertRaises(ValueError):
                sample.materialize(Path(folder))
            self.assertFalse((Path(folder) / 'modern').exists())

    def test_only_project_runtime_and_sample_workspaces_are_allowed(self):
        for path in [ROOT.parent, ROOT / 'backend', ROOT / '.runtime/../../outside']:
            with self.assertRaises(ValueError):
                sample.owned_destination(path)

    def test_resolved_build_directory_escape_is_rejected_before_execution(self):
        app = ROOT / '.runtime/link-fixture'
        original = Path.resolve
        def resolve(path, *args, **kwargs):
            if path == app / 'frontend':
                return ROOT.parent / 'outside'
            return original(path, *args, **kwargs)
        with patch.object(Path, 'resolve', resolve), patch.object(sample, 'run') as execute:
            with self.assertRaises(ValueError):
                sample.verify(app, 'all')
            execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
