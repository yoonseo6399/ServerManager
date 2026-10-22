# YouTube source 1.18.2 build bootstrap

Upstream: https://github.com/lavalink-devs/youtube-source/releases/tag/1.18.2 (MIT).

2026-10-22: the Maven artifact URLs returned MinIO directory HTML (`xl.meta`) instead of POM/JAR data; two clean release CI attempts failed before compilation. `python3 scripts/prepare-youtube-source.py` prepares a build-local runtime Maven repository from the official GitHub release with fixed SHA-256 pins. CI runs this before Gradle. No binary is committed; no dependency version or runtime code is changed.

POM and module metadata originated from the previously used Gradle cache. All uncompressed entries of the existing Maven JARs and official GitHub JARs are byte-identical (v2:21, common:73); only ZIP timestamps differ. GitHub API digests agree with the downloaded release assets. Module runtime artifact checksums are adjusted to those official container bytes; dependency graphs are unchanged. Source/Javadoc artifacts are not prepared by this runtime-only bootstrap.

Maven v2 SHA256:53aa51ac0aca44c2f6f0ffc839458a0f10c4d9eba16a45a39a352f5bbac9533c
Official GitHub v2 SHA256:0f328732150cc2b2ea791b180b32e1eae520944603cccecd4002af9a0c3f39d6
Maven common SHA256:39d69799576f35497ce4fc70c40f0b052b511dcc77c510f08e6e1fa0e3bd64e8
Official GitHub common SHA256:22f56b8632aec8b081fb7ab6d9adad012cd2a1b7a5f77167d308a6f1b3f27a35

Prepared files live under ignored build/verified-youtube-source-maven. Existing unexpected files, modified metadata, bad hashes or insecure downloads stop preparation. Published ServerManager assets are never downloaded by this helper.
