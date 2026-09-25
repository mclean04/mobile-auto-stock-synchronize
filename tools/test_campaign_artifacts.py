import argparse
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import campaign_artifacts as artifacts


class ArtifactPinsTest(unittest.TestCase):
    def test_reviewed_candidate_path_is_forwarded_with_exact_pins(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "Planning/evidence"
            candidate = evidence / "DEC008-installed-272397a2/app-debug.apk"
            candidate.parent.mkdir(parents=True)
            candidate.write_bytes(b"reviewed candidate, not outputs artifact")
            test = root / "repo/test.apk"
            test.parent.mkdir()
            test.write_bytes(b"accepted test")
            pins = {"app_artifact_path": str(candidate), "test_artifact_path": str(test),
                    "app_sha256": hashlib.sha256(candidate.read_bytes()).hexdigest(),
                    "test_sha256": hashlib.sha256(test.read_bytes()).hexdigest()}
            with patch.object(artifacts, "ROOT", test.parent), patch.object(artifacts, "EVIDENCE_ROOT", evidence):
                resolved = artifacts.resolve_artifacts(pins)
                parser = argparse.ArgumentParser()
                artifacts.add_artifact_options(parser)
                args = parser.parse_args(artifacts.artifact_arguments(resolved))
                self.assertEqual(resolved, artifacts.artifacts_from_args(args, campaign=True))
                self.assertEqual(candidate.resolve(), resolved["app"][0])
                with self.assertRaisesRegex(ValueError, "unpinned_app"):
                    artifacts.resolve_artifacts(pins | {"app_sha256": "f" * 64})
                candidate.write_bytes(b"changed after review")
                with self.assertRaisesRegex(ValueError, "unpinned_app"):
                    artifacts.artifacts_from_args(args, campaign=True)

    def test_campaign_cannot_fall_back_to_unpinned_defaults(self):
        parser = argparse.ArgumentParser()
        artifacts.add_artifact_options(parser)
        for argv in ([], ["--app-apk", "example.apk"]):
            with self.assertRaisesRegex(ValueError, "complete_campaign_artifact_pins"):
                artifacts.artifacts_from_args(parser.parse_args(argv), campaign=True)

    def test_symlink_outside_reviewed_roots_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            outside = root / "unreviewed.apk"
            outside.write_bytes(b"not reviewed")
            (repo / "escape.apk").symlink_to(outside)
            with patch.object(artifacts, "ROOT", repo), patch.object(artifacts, "EVIDENCE_ROOT", root / "evidence"):
                with self.assertRaisesRegex(ValueError, "outside_reviewed_roots"):
                    artifacts.resolve_artifacts({"app_sha256": hashlib.sha256(outside.read_bytes()).hexdigest(),
                                                 "app_artifact_path": "escape.apk"})


if __name__ == "__main__":
    unittest.main()
