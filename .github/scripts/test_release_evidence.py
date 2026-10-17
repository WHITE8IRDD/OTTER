import hashlib
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import verify_release_evidence as gate


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = pathlib.Path(self.tmp.name) / 'candidate.apk'
        self.apk.write_bytes(b'synthetic APK, not a real release')
        self.report = {
            'release': 'Outter 28', 'source_sha256': 'source',
            'apk_sha256': hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            'tested_by': 'test fixture', 'device': 'synthetic test fixture',
            'scenarios': {s: {'status': 'passed', 'evidence': 'synthetic gate test'} for s in gate.SCENARIOS},
        }

    @patch.object(gate, 'source_digest', return_value='source')
    def test_pending_or_failed_scenario_blocks_release(self, _):
        for status in ['not_run', 'failed', 'blocked']:
            self.report['scenarios']['tiktok_video']['status'] = status
            self.assertTrue(gate.verify(self.report, self.apk))

    @patch.object(gate, 'source_digest', return_value='source')
    def test_different_apk_blocks_release(self, _):
        self.apk.write_bytes(b'changed APK')
        self.assertTrue(gate.verify(self.report, self.apk))

    @patch.object(gate, 'source_digest', return_value='different source')
    def test_different_source_blocks_release(self, _):
        self.assertTrue(gate.verify(self.report, self.apk))

    @patch.object(gate, 'source_digest', return_value='source')
    def test_full_matching_evidence_passes_structural_gate(self, _):
        self.assertEqual([], gate.verify(self.report, self.apk))


if __name__ == '__main__':
    unittest.main()
