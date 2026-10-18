#!/usr/bin/env python3
"""Summarize preserved local fixtures, API counters and memory samples without running a model."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent / 'build/beta11-evidence'
rows = []
for mode in ('native', 'json'):
    for window in (8192, 16384):
        suffix = f'{mode}-14b-{window}'
        xml = root / f'live-{suffix}.xml'
        if not xml.exists():
            continue
        result = ET.parse(xml).getroot()
        stdout = result.findtext('system-out', '')
        def lines(name):
            path = root / name
            return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        calls = lines(f'actor-{suffix}.jsonl')
        memory = lines(f'memory-{suffix}.jsonl')
        wire = lines(f'wire-{suffix}.jsonl')
        before, after = root / f'fixture-{suffix}.before.bin', root / f'fixture-{suffix}.after.bin'
        source = 'saved file bytes'
        file_ok = None
        if before.exists() and after.exists():
            file_ok = after.read_bytes() == before.read_bytes().replace(b'PATCH_TARGET_9431=old', b'PATCH_TARGET_9431=new')
        elif 'exact_bytes_preserved=true' in stdout:
            file_ok, source = True, 'JUnit exact-byte assertion and verbatim final read (before snapshot capture was added)'
        sequence = re.findall(r'BETA11 LIVE tool_end turn=\d+ tool=(\w+)', stdout)
        main = [call for call in calls if call['purpose'].startswith('You are')]
        def swap(sample):
            return float(re.search(r'used = ([0-9.]+)M', sample['swap']).group(1))
        row = dict(mode=mode, model='qwen3:14b', think=True, output_reserved=4096, window=window,
                   junit_seconds=float(result.get('time')), full_workflow_pass=int(result.get('failures', '0')) == 0,
                   requested_edit_and_original_preserved=file_ok, file_evidence=source, executed_tools=sequence,
                   completed_main_calls=len(main), completed_helper_calls=len(calls)-len(main),
                   # Partial/cancelled calls have no final provider usage counter; never call these totals complete.
                   completed_calls_input_tokens=sum(call.get('input_tokens') or 0 for call in calls),
                   completed_calls_output_tokens=sum(call.get('output_tokens') or 0 for call in calls),
                   max_completed_main_input_tokens=max((call.get('input_tokens') or 0 for call in main), default=0),
                   recorded_requests=sum(item['kind']=='request' for item in wire),
                   provider_done_frames=sum(item['kind']=='chunk' and item['json'].get('done') is True for item in wire),
                   failure=result.find('.//failure').get('message') if result.find('.//failure') is not None else None)
        if memory:
            row.update(memory_samples=len(memory), max_pressure=max(sample['pressure'] for sample in memory),
                       swap_start_mib=swap(memory[0]), swap_end_mib=swap(memory[-1]),
                       swap_peak_increase_mib=round(max(swap(sample) for sample in memory)-swap(memory[0]), 2),
                       model_peak_vram_gib=round(max((model.get('size_vram',0) for sample in memory for model in sample['models']['models']), default=0)/1024**3, 3),
                       observed_context_lengths=sorted({model.get('context_length') for sample in memory for model in sample['models']['models']}),
                       final_pressure=memory[-1]['pressure'], final_models=memory[-1]['models']['models'])
        rows.append(row)
summary = dict(note='One fixture per configuration, not a general model ranking. Completed-call token counts exclude cancelled calls. JUnit time excludes Gradle startup.', results=rows)
(root / 'SUMMARY.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
manifest = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(root.rglob('*')) if path.is_file() and path.name != 'SHA256.json'}
(root / 'SHA256.json').write_text(json.dumps(manifest, indent=2))
for row in rows:
    print(json.dumps({key:row.get(key) for key in ('mode','window','junit_seconds','requested_edit_and_original_preserved','full_workflow_pass','max_pressure','swap_peak_increase_mib','model_peak_vram_gib')}, ensure_ascii=False))
