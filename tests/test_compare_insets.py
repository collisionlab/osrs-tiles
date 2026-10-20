import importlib.machinery
import importlib.util
from pathlib import Path
import unittest
loader = importlib.machinery.SourceFileLoader('compare_insets', str(Path(__file__).resolve().parents[1] / 'scripts/compare-insets'))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)

class CompareTest(unittest.TestCase):
    def test_names_are_advisory_but_substantial_geometry_changes_need_review(self):
        previous = dict(planes=[list(range(100)), [], [], []], componentCount=100)
        self.assertFalse(module.needs_review({**previous, 'reviewCount': 50}, previous))
        self.assertTrue(module.needs_review({**previous, 'componentCount': 111}, previous))
        self.assertTrue(module.needs_review({**previous, 'planes': [list(range(89)), [], [], []]}, previous))

if __name__ == '__main__':
    unittest.main()
