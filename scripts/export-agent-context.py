#!/usr/bin/env python3
"""Export current Pi host instructions without reading private runtime data."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/kotlin/local/servermanager'


def literal(file, prefix):
    match = re.search(r'"(' + re.escape(prefix) + r'(?:[^"\\]|\\.)*)"', (SOURCE / file).read_text())
    if not match:
        raise RuntimeError(f'Prompt missing: {file}: {prefix}')
    return json.loads('"' + match.group(1) + '"')


def document():
    engine = (SOURCE / 'agent/ProjectAgentEngine.kt').read_text()
    risk = re.search(r'ProjectAgentMessage\("system","""(Independent (?:request-aware )?command-risk reviewer.*?)"""\)', engine, re.S)
    bridge = (ROOT / 'src/main/resources/pi/bridge.mjs').read_text()
    compact = re.search(r"session\.compact\('([^']*)'\)", bridge)
    if not risk or not compact:
        raise RuntimeError('Pi prompt layout changed')
    items = [
        ('Pi 기본 지시문에 추가되는 system', 'agent/ProjectAgentEngine.kt', literal('agent/ProjectAgentEngine.kt', 'You are 루오노타르,')),
        ('독립 위험 검토 system', 'agent/ProjectAgentEngine.kt', risk.group(1).strip()),
        ('수동 압축 추가 지시문', 'src/main/resources/pi/bridge.mjs', compact.group(1)),
        ('레거시 대화 최초 이관 안내', 'agent/ProjectAgentEngine.kt', literal('agent/ProjectAgentEngine.kt', 'Legacy conversational handover only;')),
        ('호환 라우팅 분류 system', 'agent/ProjectAgentEngine.kt', literal('agent/ProjectAgentEngine.kt', 'Routing only.')),
    ]
    result = ['# AI 문맥 지시문 원문 — Pi 엔진', '',
              '생성: `python3 scripts/export-agent-context.py`. 검증: `python3 scripts/export-agent-context.py --check`.', '',
              '운영 프로필·대화·비밀 값 없이 현재 소스 템플릿만 추출한다. `$home` 등은 실행 시 치환된다. [문맥 구성](agent-context.md)과 [Pi 엔진 구조](pi-agent-runtime.md)를 참고한다.', '',
              'Pi의 기본 system·도구 설명·자동 압축 프롬프트는 고정 SDK 1.3.0이 생성한다. 아래는 앱이 추가하는 부분이며 전체 SDK 프롬프트를 대체하지 않는다. 이전 custom actor/요구사항 추출/압축 프롬프트는 생산 경로에서 사용하지 않는다.', '']
    for label, file, value in items:
        result += [f'## {label}', '', f'소스: `{file}`', '', '```text', value, '```', '']
    return '\n'.join(result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    target = ROOT / 'docs/architecture/agent-context-prompts.md'
    expected = document()
    if args.check:
        if not target.exists() or target.read_text() != expected:
            raise SystemExit('Prompt document is stale; run scripts/export-agent-context.py')
        print('Agent prompt document matches source templates.')
    else:
        target.write_text(expected)
        print(target.relative_to(ROOT))
