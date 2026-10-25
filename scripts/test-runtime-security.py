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

    def test_windows_uses_only_pinned_patched_alternative_during_rollout(self):
        data = [{"version": {"semver": "21.0.12+101.0.LTS"}}]
        pinned = security.WINDOWS_ZULU
        with patch.object(subprocess, "check_output", return_value=json.dumps(data).encode()), patch.object(security, "cache_archive", return_value=pathlib.Path(pinned["name"])) as cache:
            path, version, digest = security.download_runtime("x64", "windows")
        self.assertEqual(version, "21.0.13+9")
        self.assertEqual(digest, pinned["sha256"])
        self.assertIn("Azul Zulu", security.runtime_source(path))
        cache.assert_called_once_with(pinned["name"], pinned["sha256"], pinned["url"])

    def test_alternative_does_not_allow_unpatched_java_or_another_publisher(self):
        for changes in ({"version": "21.0.12.1+1"}, {"url": "https://example.com/java.zip"}):
            with patch.dict(security.WINDOWS_ZULU, changes), patch.object(security, "cache_archive") as cache:
                with self.assertRaises((ValueError, RuntimeError)):
                    security.download_windows_alternative()
                cache.assert_not_called()

    def test_alternative_failure_is_not_replaced_by_an_old_runtime(self):
        data = [{"version": {"semver": "21.0.12+101.0.LTS"}}]
        with patch.object(subprocess, "check_output", return_value=json.dumps(data).encode()), patch.object(security, "download_windows_alternative", side_effect=RuntimeError("unavailable")):
            with self.assertRaisesRegex(RuntimeError, "unavailable"):
                security.download_runtime("x64", "windows")

    def test_corrupt_archive_is_not_published_to_cache(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(security, "ROOT", pathlib.Path(folder)):
            def corrupt(args, **kwargs):
                pathlib.Path(args[args.index("--output") + 1]).write_bytes(b"corrupt")
            with patch.object(subprocess, "run", side_effect=corrupt):
                with self.assertRaisesRegex(RuntimeError, "checksum"):
                    security.cache_archive("fixture.zip", "0" * 64, "https://cdn.azul.com/zulu/bin/fixture.zip")
            self.assertFalse((pathlib.Path(folder) / ".runtime-cache/fixture.zip").exists())
            self.assertFalse((pathlib.Path(folder) / ".runtime-cache/fixture.partial").exists())

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

    def test_compromised_subject_is_removed_independent_of_alias(self):
        java, classes = security.build_trust_tool()
        keytool = java.parent / "keytool"
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            store = root / "cacerts"
            shutil.copy2(java.parent.parent / "lib/security/cacerts", store)
            subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", "harden", str(store)], check=True, capture_output=True)
            for number, cn in enumerate(("emSign ECC Root CA - G3", "AAA Certificate Services")):
                keys, cert = root / f"key{number}.p12", root / f"cert{number}.der"
                subprocess.run([str(keytool), "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", f"CN={cn},O=Test Fixture", "-validity", "1", "-keystore", str(keys), "-storepass", "changeit", "-keypass", "changeit"], check=True, capture_output=True)
                subprocess.run([str(keytool), "-exportcert", "-alias", "fixture", "-keystore", str(keys), "-storepass", "changeit", "-file", str(cert)], check=True, capture_output=True)
                subprocess.run([str(keytool), "-importcert", "-noprompt", "-alias", f"unfamiliar-vendor-alias-{number}", "-file", str(cert), "-keystore", str(store), "-storepass", "changeit"], check=True, capture_output=True)
            def run(mode):
                return subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", mode, str(store)], capture_output=True, text=True)
            self.assertNotEqual(run("check").returncode, 0)
            hardened = run("harden")
            self.assertEqual(hardened.returncode, 0, hardened.stderr)
            self.assertIn("removed=2", hardened.stdout)
            self.assertEqual(run("check").returncode, 0)

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
