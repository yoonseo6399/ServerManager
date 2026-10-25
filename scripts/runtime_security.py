"""Verified Java 21 downloads and October 2026 emergency trust-store mitigation."""
import hashlib
import json
import os
import pathlib
import re
import subprocess
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
MINIMUM_JAVA = (21, 0, 13)
ADVISORY = "https://www.oracle.com/security-alerts/alert-cve-2026-91403.html"
# Temurin's Windows rollout still reports 21.0.12.1. This official Zulu CA artifact is
# pinned to the SHA-256 returned by Azul's metadata API on 2026-10-26. No downgrade.
WINDOWS_ZULU = {
    "name": "zulu21.54.107-ca-jre21.0.13-win_x64.zip",
    "version": "21.0.13+9",
    "sha256": "9532e56bcf2578b4bbb82507c4e38a17c0332c5918ff6c8b25cd2f71abf42cf9",
    "url": "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jre21.0.13-win_x64.zip",
    "metadata": "https://api.azul.com/metadata/v1/zulu/packages/1953be22-1404-4175-8257-0dcb1ac0dffb",
}


def cache_archive(name, digest, url):
    if pathlib.PurePosixPath(name).name != name or "\\" in name or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid runtime package metadata")
    cache = ROOT / ".runtime-cache"
    cache.mkdir(exist_ok=True)
    target = cache / name
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        partial = target.with_suffix(".partial")
        try:
            subprocess.run(["/usr/bin/curl", "--fail", "--silent", "--show-error", "--location", "--retry", "2", "--max-time", "600", "--output", str(partial), url], check=True)
            if hashlib.sha256(partial.read_bytes()).hexdigest() != digest:
                raise RuntimeError("Java runtime checksum mismatch")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    return target


def download_windows_alternative():
    pinned = WINDOWS_ZULU
    require_security_baseline(pinned["version"])
    link = urlparse(pinned["url"])
    if link.scheme != "https" or link.netloc != "cdn.azul.com" or link.path != "/zulu/bin/" + pinned["name"]:
        raise ValueError("Unexpected alternate runtime publisher")
    return cache_archive(pinned["name"], pinned["sha256"], pinned["url"]), pinned["version"], pinned["sha256"]


def runtime_source(archive):
    return "Azul Zulu / pinned official CA package" if archive.name == WINDOWS_ZULU["name"] else "Eclipse Temurin / Adoptium"


def version_tuple(version):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.+-][0-9A-Za-z.+_-]+)?", version)
    if not match:
        raise ValueError("Unrecognized Java version: " + version)
    return tuple(map(int, match.groups()))


def require_security_baseline(version):
    value = version_tuple(version)
    if value[0] != 21 or value < MINIMUM_JAVA:
        raise RuntimeError("Java 21.0.13 or newer in the 21 LTS line is required; received " + version)


def download_runtime(arch, osname, image_type="jre"):
    if arch not in ("x64", "aarch64") or osname not in ("mac", "windows") or image_type not in ("jre", "jdk"):
        raise ValueError("Unsupported runtime request")
    url = f"https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture={arch}&image_type={image_type}&os={osname}&vendor=eclipse"
    assets = json.loads(subprocess.check_output(["/usr/bin/curl", "--fail", "--silent", "--show-error", "--location", "--retry", "2", "--max-time", "90", url]))
    # Do not silently package an older architecture while the vendor rolls out a CPU.
    eligible = [a for a in assets if version_tuple(a["version"]["semver"]) >= MINIMUM_JAVA]
    if not eligible:
        if osname == "windows" and arch == "x64" and image_type == "jre":
            return download_windows_alternative()
        raise RuntimeError(f"No security-baseline {osname}/{arch}/{image_type} published by Adoptium yet; refusing an outdated runtime")
    asset = max(eligible, key=lambda a: (version_tuple(a["version"]["semver"]), a["version"].get("build", 0)))
    require_security_baseline(asset["version"]["semver"])
    package = asset["binary"]["package"]
    name, digest = package["name"], package["checksum"]
    link = urlparse(package["link"])
    if pathlib.PurePosixPath(name).name != name or "\\" in name or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid runtime package metadata")
    if link.scheme != "https" or link.netloc != "github.com" or not link.path.startswith("/adoptium/temurin21-binaries/releases/download/"):
        raise ValueError("Unexpected runtime publisher")
    return cache_archive(name, digest, package["link"]), asset["version"]["semver"], digest


def build_trust_tool():
    java_home = pathlib.Path(os.environ.get("JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip())
    classes = ROOT / "build/runtime-security-classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(java_home / "bin/javac"), "--release", "21", "-d", str(classes), str(ROOT / "packaging/security/RuntimeTrust.java")], check=True)
    return java_home / "bin/java", classes


def harden_runtime(home, tool=None):
    home = pathlib.Path(home)
    text = (home / "release").read_text()
    version = re.search(r'^JAVA_VERSION="([^"]+)"$', text, re.MULTILINE)
    if not version:
        raise RuntimeError("Missing extracted Java release version")
    require_security_baseline(version[1])
    java, classes = tool or build_trust_tool()
    stores = [home / "lib/security/cacerts"]
    override = home / "lib/security/jssecacerts"
    if override.exists():
        stores.append(override)
    for store in stores:
        subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", "harden", str(store)], check=True)
        subprocess.run([str(java), "-cp", str(classes), "RuntimeTrust", "check", str(store)], check=True)
    return {"java": version[1], "securityBaseline": "21.0.13", "trustMitigation": "CVE-2026-91403: affected root certificates removed", "advisory": ADVISORY}
