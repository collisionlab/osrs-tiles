import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class UploadTest(unittest.TestCase):
    def test_missing_matching_and_conflicting_remote_manifests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = root / 'candidate'
            candidate.mkdir()
            manifest = json.dumps(dict(version='a' * 24))
            (candidate / 'manifest.json').write_text(manifest)
            remote, calls = root / 'remote', root / 'calls'
            rclone = root / 'rclone'
            # rclone cat can succeed without output when no remote file matches.
            rclone.write_text('#!/usr/bin/env bash\n'
                              'echo "$1" >> "$CALLS"\n'
                              'if [ "$1" = cat ]; then cat "$REMOTE_MANIFEST"; fi\n')
            rclone.chmod(0o755)
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}',
                       CALLS=str(calls), REMOTE_MANIFEST=str(remote),
                       R2_ENDPOINT='https://example.invalid', R2_ACCESS_KEY_ID='test', R2_SECRET_ACCESS_KEY='test')
            for contents, expected_calls, succeeds in [
                    ('', ['cat', 'copy', 'check', 'copyto'], True),
                    (manifest, ['cat'], True),
                    ('different release', ['cat'], False)]:
                with self.subTest(remote=contents):
                    remote.write_text(contents)
                    calls.write_text('')
                    result = subprocess.run(['bash', str(ROOT / 'scripts/upload-insets'), str(candidate)],
                                            env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, succeeds, result.stderr)
                    self.assertEqual(calls.read_text().splitlines(), expected_calls)
