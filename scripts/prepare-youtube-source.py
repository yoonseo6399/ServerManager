#!/usr/bin/env python3
"""Prepare the unchanged YouTube 1.18.2 dependency from pinned official release assets.

The Maven endpoint currently serves MinIO directory HTML instead of artifacts.
No binaries are committed; original Maven metadata and every downloaded byte are pinned.
"""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.18.2"
PINS = {
    "v2": {
        "jar": "0f328732150cc2b2ea791b180b32e1eae520944603cccecd4002af9a0c3f39d6",
        "pom": "05261c70964656d33957f3b410099bd503002b035b11696ae33c1096a6e32479",
        "module": "b0bfc97589a6cab3aec70aa516fe1149f40d0f2fd1eea6d0715ddfef240eb4eb",
    },
    "common": {
        "jar": "22f56b8632aec8b081fb7ab6d9adad012cd2a1b7a5f77167d308a6f1b3f27a35",
        "pom": "4a52753d5281544a00cbdaf68768ed8b50d4ddff3cd154a92b1022934a7d0110",
        "module": "528752131dfb67627e84bc4fa93200ee524bb5119df7609e7977bfac4d1a6d39",
    },
}


def verify(data, expected):
    if len(data) > 1_000_000 or hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("YouTube dependency checksum/size mismatch; refusing the build")


def prepare():
    build = ROOT / "build"
    if build.is_symlink():
        raise ValueError("Build directory must not be a symlink")
    build.mkdir(exist_ok=True)
    target = build / "verified-youtube-source-maven"
    if target.exists() or target.is_symlink():
        if target.is_symlink():
            raise ValueError("Prepared repository must not be a symlink")
        for module, pins in PINS.items():
            for ext, expected in pins.items():
                file = target / "dev/lavalink/youtube" / module / VERSION / f"{module}-{VERSION}.{ext}"
                if file.is_symlink() or not file.resolve().is_relative_to(target.resolve()):
                    raise ValueError("Unexpected dependency path")
                if file.stat().st_size > 1_000_000:
                    raise ValueError("Unexpected dependency size")
                verify(file.read_bytes(), expected)
        print("Verified YouTube dependency repository already prepared")
        return
    with tempfile.TemporaryDirectory(prefix="youtube-source-", dir=build) as stage_name:
        stage = Path(stage_name) / "repository"
        for module, pins in PINS.items():
            directory = stage / "dev/lavalink/youtube" / module / VERSION
            directory.mkdir(parents=True)
            for ext in ("pom", "module"):
                name = f"{module}-{VERSION}.{ext}"
                data = (ROOT / f"gradle/youtube-source-{VERSION}" / name).read_bytes()
                verify(data, pins[ext])
                (directory / name).write_bytes(data)
            url = f"https://github.com/lavalink-devs/youtube-source/releases/download/{VERSION}/youtube-{module}-{VERSION}.jar"
            request = urllib.request.Request(url, headers={"User-Agent": "ServerManager dependency preparation"})
            with urllib.request.urlopen(request, timeout=60) as response:
                if not response.geturl().startswith("https://"):
                    raise ValueError("Dependency download requires HTTPS")
                data = response.read(1_000_001)
            verify(data, pins["jar"])
            (directory / f"{module}-{VERSION}.jar").write_bytes(data)
            print(f"Verified official youtube-{module}-{VERSION}.jar")
        (stage / "verified.json").write_text(json.dumps(PINS), encoding="utf-8")
        os.rename(stage, target)


if __name__ == "__main__":
    prepare()
