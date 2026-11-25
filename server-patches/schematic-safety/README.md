# Survival Schematic Safety 1.0.0

Server-only add-on for Minecraft 1.21.1 / NeoForge 21.1 / Create 6.0.10.
Does not replace Create, Railways, schematic files or world blocks.

The 2026-11-25 crash involved a truncated gzip schematic. Minecraft NbtIo wrapped
an IOException in ReportedException, escaping Create's IOException handler. This
add-on wraps the outer SchematicItem.loadSchematic method, including Railways'
read wrapper, and returns a fresh empty template only for IOException-caused or
known NBT format/accounting failures. Create's existing empty-template behavior
rejects the cannon job. Unrelated exceptions and JVM Errors are rethrown.

A bounded, rate-limited warning identifies owner/file and preserves the cause.
A repaired/re-uploaded file is retried normally (no permanent failure cache).
This does not reconstruct missing bytes or fix interrupted upload handling.

Install into the dedicated server mods folder and restart normally. No client
installation is required. Keep manager and runner copies consistent.
Remove the add-on while stopped to uninstall. No ServerManager release bundles
this server-specific add-on automatically.

Validation plan (commit before execution): Java cause-chain tests for recoverable
wrapped EOF/NBT errors, valid return values, unrelated exceptions, JVM Errors,
and cyclic causes. Isolated full modpack boot compares unpatched and patched
loads of the actual corrupt file, valid files, and file replacement; tests the
schematic printer path and verifies input bytes are unchanged. Production world
is never used for testing. Operational installation/restart is reported separately.
