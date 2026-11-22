상태: null fileKey 대체 식별 구현, 커밋 후 회귀 검증 예정
# fix/log-search-file-identity
- beta33 별도 실행기 CI45003026642의 Windows RunnerWorkspaceTest가 BasicFileAttributes.fileKey null로 실패한 원문을 확인했다.
- 기본 파일시스템이 식별키를 제공하지 않으면 생성시각을 대체 식별값으로 사용한다. 크기·gzip 수정시각·심볼릭 링크 검사는 유지한다.
- 테스트 계획: fileKey 없는 속성으로 전체 검색/append snapshot/생성 식별 변경 거부를 검사하고 기존 LogSearch/RunnerWorkspace 및 관련28개 회귀를 실행한다. 전체 suite·다른 OS 수동 실행은 하지 않는다.
- 게시된 beta32/33 태그·자산은 보존하고 beta34로 제공한다.
