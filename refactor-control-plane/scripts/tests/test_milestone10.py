import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[2]


class Milestone10BoundaryTest(unittest.TestCase):
    def load(self):
        sys.path.insert(0,str(ROOT/'scripts'))
        try:
            spec=importlib.util.spec_from_file_location('verify_milestone10',ROOT/'scripts/verify_milestone10.py')
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
        finally:sys.path.pop(0)

    def test_external_app_is_rejected_before_git_or_database_side_effects(self):
        module=self.load()
        with patch.object(module.sample,'git',side_effect=AssertionError('Unconfined Git operation')),patch.object(module.mysql_sample,'Fixture',side_effect=AssertionError('Unconfined database')):
            with self.assertRaises(ValueError):module.check(ROOT.parent,'unused','unused')

    def test_existing_governed_destination_is_never_overwritten(self):
        module=self.load()
        with patch.object(module.sample,'prepare',side_effect=AssertionError('Existing project overwritten')):
            with self.assertRaises(ValueError):module.governed(SimpleNamespace(destination=ROOT/'.runtime'))


if __name__=='__main__':unittest.main()
