# Native tool comparison (separate from common-instrument calibration)

The controlled `run.py` track supplies identical exact-find instruments to all
upstream loops. It does not evaluate the native editor implementation. Repeated
LF/CRLF exact-find failures occurred in that track; retain the raw failures.

`run_native.py` uses each pinned upstream engine's own tools instead:

- Pi 1.3.0: default read, bash, edit, write tools.
- Hermes 0.22.2: file and terminal toolsets, eager schema mode supported by config.
- OpenCode 1.19.3: native file/search/shell permissions; web and task delegation denied.

All use the same frozen tasks, local 27B GGUF Q4_K_M, 65536 context, Q8 KV cache,
no thinking, temperature 0, maximum 2048 output tokens per inference. This is one
trial per task, not a model leaderboard. Framework prompts and tool counts differ
by design. There is no custom ServerManager loop or custom competitor.

Adapters are launched with a minimal environment, no inherited cloud credentials,
private HOME/TMPDIR and a macOS sandbox. Writes are limited to the case directory
(fixture plus framework state), repository snapshot writes are denied, other user
and temp files are unreadable, and network is limited to local benchmark ports
18084–18086. This is a benchmark boundary, not the production permission design.

Raw native events and canonical engine state are retained. Automatic outcomes use
actual bytes, execution and files. Requirements that need interpretation of a
compound native shell command (read before delete, checking two Python examples)
are explicitly marked for manual audit; do not invent common-tool events. A
provisional automatic pass is not a completed manual pass. Source-location/read
evidence also needs audit because the earlier instrument oracle has a narrow
allowlist and does not fully inspect command read ranges.

Context compaction is performed by the engine's own API. Compare actual prompt
usage before/after compaction and recalled corrected facts. Transcript character
count alone is not a measure of effective compressed context. Pi's reload opens
its native session file in a fresh AgentSession object in the same process.
