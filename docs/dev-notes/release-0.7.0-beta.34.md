상태: 관련29개 및 실행기 CI45003579446 통과, 릴리즈 CI45004028801 서명 게시 성공
# release/0.7.0-beta.34
- 공개 beta33 소스6fd910b에 fix/log-search-file-identity915add4를 포함한다. 기존 버전 태그·자산·stable main/latest는 보존한다.
- 별도 실행기 CI의 null fileKey 실패를 생성시각 대체 식별값으로 처리한다. 해당 상황의 검색 완료·append snapshot·교체 거부를 회귀로 검증했다.
- 관련6개 클래스29테스트 실패0/스킵0. 이전 browser19·공식 Mojang 격리 교체·TS/Vite 검증은 동일 코드 결과를 유지한다. 기존 실행기 CI를 재실행하고 결과 확인 후 서명 릴리즈한다.
- 전체 suite·다른OS 수동 실행·실제 모드 로더 교체·실제 월드 로드 미실행. 운영 서버 버전/프로세스/데이터는 변경하지 않는다. 공개 자산 재다운로드 해시 비교 없음.

- 실행기 CI45003579446 macos-15/windows-latest 모두 success. 알려진 null fileKey 실패는 해소했다. release CI45004028801 success,2026-11-22T08:48:09Z 공개 beta34 게시. source/tag868a20c2fa1f9c116a1c104dd6ccb7edf7505908 일치, 자산9개 이름·크기>0·uploaded를 API 메타데이터로 검증했다. stable latest v0.6.3 유지. 공개 파일 다운로드/추가 해시 검사와 운영 적용은 하지 않았다.
