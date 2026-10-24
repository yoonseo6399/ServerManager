#!/usr/bin/env python3
"""Focused offline baseline tests and a real staged trust-store regression."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import runtime_security as security


class RuntimeSecurityTests(unittest.TestCase):
    def test_supported_java_21_patch(self):
        for version in ("21.0.13", "21.0.13+9.0.LTS", "21.0.14+1-LTS"):
            security.require_security_baseline(version)

    def test_old_patch_and_different_major_refused(self):
        for version in ("21.0.12.1+1", "21.0.12+101.0.LTS", "17.0.21", "25.0.5"):
            with self.assertRaises((ValueError, RuntimeError)):
                security.require_security_baseline(version)

    def test_malformed_version_refused(self):
        for version in ("latest", "21", "21.0.13;touch /tmp/no", "21.0.13\n"):
            with self.assertRaises(ValueError):
                security.require_security_baseline(version)

    def test_vendor_rollout_cannot_fall_back_to_unpatched_windows(self):
        data = [{"version": {"semver": "21.0.12+101.0.LTS"}}]
        with patch.object(subprocess, "check_output", return_value=json.dumps(data).encode()):
            with self.assertRaisesRegex(RuntimeError, "refusing an outdated runtime"):
                security.download_runtime("x64", "windows")

    def test_unexpected_publisher_refused_before_download(self):
        data = [{"version": {"semver": "21.0.13+9.0.LTS"}, "binary": {"package": {
            "name": "java.tar.gz", "checksum": "0" * 64, "link": "https://example.com/java.tar.gz"}}}]
        with patch.object(subprocess, "check_output", return_value=json.dumps(data).encode()):
            with self.assertRaisesRegex(ValueError, "publisher"):
                security.download_runtime("aarch64", "mac")

    def test_real_trust_store_check_harden_idempotence(self):
        java, classes = security.build_trust_tool()
        source = java.parent.parent / "lib/security/cacerts"
        with tempfile.TemporaryDirectory() as directory:
            store = pathlib.Path(directory) / "cacerts"
            shutil.copy2(source, store)
            def run(mode):
                return subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", mode, str(store)], capture_output=True, text=True)
            before = run("check")
            self.assertEqual(run("harden").returncode, 0)
            self.assertEqual(run("check").returncode, 0)
            once = store.read_bytes()
            again = run("harden")
            self.assertEqual(again.returncode, 0, again.stderr)
            self.assertIn("removed=0", again.stdout)
            self.assertEqual(once, store.read_bytes())
            # A compromised original must become safe; an already hardened JDK remains safe.
            self.assertIn(before.returncode, (0, 1))

    def test_missing_and_linked_store_refused(self):
        java, classes = security.build_trust_tool()
        with tempfile.TemporaryDirectory() as directory:
            missing = pathlib.Path(directory) / "missing"
            linked = pathlib.Path(directory) / "linked"
            linked.symlink_to(java.parent.parent / "lib/security/cacerts")
            for store in (missing, linked):
                result = subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", "harden", str(store)], capture_output=True)
                self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
