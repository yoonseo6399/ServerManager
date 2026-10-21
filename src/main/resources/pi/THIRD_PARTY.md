ServerManager Pi integration

SDK: @earendil-works/pi-coding-agent 1.3.0
Upstream: https://github.com/earendil-works/pi/tree/main/packages/coding-agent
Author: Mario Zechner (package metadata)
License: MIT (package metadata; see upstream repository license)

Node.js: 24.22.1 official distributions, verified against node.json SHA-256.
Source: https://nodejs.org/dist/v24.22.1/
Node's full LICENSE is included in the official downloaded distribution.

Dependencies and integrity are pinned in package-lock.json. Installed packages retain
upstream license/notice files where provided. No upstream source is modified.
The Pi SDK is downloaded at first agent execution, not vendored in this JAR.

The HTTP timeout adapter uses undici 8.10.2, pinned in this runtime lockfile
as a Pi dependency. No upstream SDK sources are patched.
