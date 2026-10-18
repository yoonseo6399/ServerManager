#!/usr/bin/env python3
"""Export the actual source templates without reading any runtime profile, conversation or credential."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/kotlin/local/servermanager'


def source(file):
    return (SOURCE / file).read_text()


def raw(file, pattern):
    match = re.search(pattern, source(file), re.S)
    if not match:
        raise RuntimeError(f'Prompt layout changed: {file}: {pattern}')
    return match.group(1).strip()


def literal(file, prefix):
    pattern = r'"(' + re.escape(prefix) + r'(?:[^"\\]|\\.)*)"'
    match = re.search(pattern, source(file))
    if not match:
        raise RuntimeError(f'Prompt missing: {file}: {prefix}')
    return json.loads('"' + match.group(1) + '"')


def document():
    items = [
        ('공식 Ollama 도구 에이전트 system 템플릿', 'agent/ProjectAgentEngine.kt', raw('agent/ProjectAgentEngine.kt', r'val baseSystem=if\(native\)ProjectAgentMessage\("system","""(.*?)"""\)')),
        ('레거시 JSON 에이전트 system 템플릿', 'agent/ProjectAgentEngine.kt', raw('agent/ProjectAgentEngine.kt', r'val system=ProjectAgentMessage\("system","""(.*?)"""\)')),
        ('도구 목록', 'agent/ProjectAgentTools.kt', raw('agent/ProjectAgentTools.kt', r'val description="""(.*?)"""\+harness')),
        ('검색·부분 수정 작업 요령', 'agent/ProjectAgentTools.kt', raw('agent/ProjectAgentTools.kt', r'val harness="""(.*?)"""')),
        ('독립 위험 검토 system', 'agent/ProjectAgentEngine.kt', raw('agent/ProjectAgentEngine.kt', r'ProjectAgentMessage\("system","""(Independent command-risk reviewer.*?)"""\)')),
        ('문맥 압축 system', 'agent/ProjectAgentMemory.kt', literal('agent/ProjectAgentMemory.kt', 'Context compressor.')),
        ('명령 결과 요약 system', 'agent/ProjectAgentMemory.kt', literal('agent/ProjectAgentMemory.kt', 'Tool-result summarizer.')),
        ('요구사항 추출 system', 'agent/ProjectAgentLedger.kt', literal('agent/ProjectAgentLedger.kt', 'Requirement extractor.')),
        ('레거시 라우팅 분류 system', 'agent/ProjectAgentEngine.kt', literal('agent/ProjectAgentEngine.kt', 'You are a routing classifier,')),
    ]
    result = ['# AI 문맥 지시문 원문', '',
              '생성: `python3 scripts/export-agent-context.py`. 검증: `python3 scripts/export-agent-context.py --check`.', '',
              '소스 템플릿을 그대로 추출한다. `${...}`는 실행 시 Kotlin이 치환하는 자리이며 실제 계정·경로·대화는 포함하지 않는다. 전체 메시지 순서·권한별 차이·예산은 [agent-context.md](agent-context.md)를 참고한다.', '',
              '도구 목록에는 작업 요령이 뒤이어 붙는다. 친구 READ/EDIT에는 `descriptionFor`의 짧은 권한별 목록이 사용된다. 레거시 라우팅 분류기는 자연어 채널의 기본 경로가 아니다.', '']
    for label, file, text in items:
        result += [f'## {label}', '', f'소스: `{file}`', '', '```text', text, '```', '']
    result += ['## 권한별 도구 목록 선택 코드', '', '```kotlin',
               raw('agent/ProjectAgentTools.kt', r'(fun descriptionFor\(tier:String\).*?)\n    }'), '```', '',
               '## 레거시·보조 응답 JSON 스키마 생성 코드', '', '```kotlin', source('agent/ProjectAgentFormats.kt').strip(), '```', '', '## 공식 함수 등록·네이티브 응답 변환 코드', '', '```kotlin', source('agent/ProjectAgentNativeTools.kt').strip(), '```', '']
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
