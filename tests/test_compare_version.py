import io
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch
import urllib.error

fetch = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'scripts/compare-version'))['fetch']


class CompareFetchTest(unittest.TestCase):
    def test_identifies_publisher_and_only_tolerates_missing_assets(self):
        url = 'https://tiles.example/tactical/version/index.json'
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'{"tile": "hash"}')) as opened:
            self.assertEqual(fetch(url), {'tile': 'hash'})
            request = opened.call_args.args[0]
            self.assertEqual(request.full_url, url)
            self.assertEqual(request.get_header('User-agent'), 'collisionlab-tiles/1.0')
        for code in (404, 403):
            with self.subTest(code=code), patch('urllib.request.urlopen', side_effect=urllib.error.HTTPError(url, code, 'error', {}, None)):
                if code == 404:
                    self.assertIsNone(fetch(url))
                else:
                    with self.assertRaises(urllib.error.HTTPError):
                        fetch(url)
