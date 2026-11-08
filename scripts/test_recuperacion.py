import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('recuperacion', Path(__file__).with_name('recuperacion.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

class RecoverySafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def backup(self, name):
        folder = self.root / name
        folder.mkdir()
        for f in ('database.tar.age', 'code.tar.age'):
            (folder / f).write_bytes(b'ciphertext-' + f.encode())
        (folder / 'manifest.json').write_text(json.dumps({'files': {f: r.sha(folder / f) for f in ('database.tar.age', 'code.tar.age')}}))
        return folder

    def test_detects_tampering(self):
        folder = self.backup('source')
        (folder / 'database.tar.age').write_bytes(b'alterado')
        with self.assertRaises(ValueError): r.verify_outer(folder)

    def test_rejects_unexpected_manifest_paths(self):
        folder = self.backup('source')
        (folder / 'manifest.json').write_text(json.dumps({'files': {'../otro': '0'}}))
        with self.assertRaises(ValueError): r.verify_outer(folder)

    def test_new_copy_is_verified_before_promotion(self):
        folder = self.backup('source')
        (folder / 'code.tar.age').write_bytes(b'alterado')
        with self.assertRaises(ValueError): r.promote(folder, self.root / 'daily', '20260101T020000Z')
        self.assertFalse((self.root / 'daily/20260101T020000Z').exists())

    def test_existing_corrupt_copy_is_not_accepted(self):
        source = self.backup('source')
        target = r.promote(source, self.root / 'weekly', '2026-W01')
        (target / 'code.tar.age').write_bytes(b'alterado')
        with self.assertRaises(ValueError): r.promote(source, self.root / 'weekly', '2026-W01')

    def test_retention_keeps_newest_and_ignores_unrecognized_paths(self):
        for n in range(1, 11): (self.root / f'202601{n:02}T020000Z').mkdir()
        (self.root / '.pending-20260111T020000Z').mkdir()
        (self.root / 'documentos').mkdir()
        r.rotate(self.root, 7, r'\d{8}T\d{6}Z')
        remaining = sorted(p.name for p in self.root.iterdir())
        self.assertEqual(9, len(remaining))
        self.assertIn('20260104T020000Z', remaining)
        self.assertNotIn('20260103T020000Z', remaining)

    def test_weekly_and_monthly_retention(self):
        for bucket, keep, pattern, names in [('weekly',4,r'\d{4}-W\d{2}',[f'2026-W{x:02}' for x in range(1,7)]),('monthly',3,r'\d{4}-\d{2}',[f'2026-{x:02}' for x in range(1,7)])]:
            directory = self.root / bucket; directory.mkdir()
            for name in names: (directory / name).mkdir()
            r.rotate(directory, keep, pattern)
            self.assertEqual(names[-keep:], sorted(p.name for p in directory.iterdir()))

    def test_rotation_rejects_symlinks_outside_bucket(self):
        bucket = self.root / 'daily'; bucket.mkdir()
        outside = self.root / 'outside'; outside.mkdir()
        (bucket / '20260101T020000Z').symlink_to(outside, target_is_directory=True)
        (bucket / '20260102T020000Z').mkdir()
        with self.assertRaises(ValueError): r.rotate(bucket, 1, r'\d{8}T\d{6}Z')
        self.assertTrue(outside.exists())

if __name__ == '__main__': unittest.main()
