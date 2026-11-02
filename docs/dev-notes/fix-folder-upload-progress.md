상태: 구현·검증 완료, backend10/UI12/TS·Vite 통과, 릴리즈 통합 대기
# fix/folder-upload-progress

- v0.7.0-beta.28 기반, solo. 파일 관리자 폴더 업로드의 개별 파일 카드와 중복 배치 카드를 폴더 하나의 진행 카드로 합친다.
- 폴더 이름만 표시하며 하위 폴더/파일 목록을 확장하지 않는다. 전체 파일 수/완료 수/바이트 가중 진행률·현재 단계·실패 수 유지.
- 그룹 ID/이름/파일 수/바이트를 서버 상태에 저장하여 새로고침 뒤에도 하나의 카드로 복원한다. 기존 단일 파일 업로드 API와 저장 상태는 기본 null로 호환.
- 폴더 취소는 현재 취소 가능한 작업과 남은 파일을 중단한다. APPLYING 파일은 되돌린다고 표시하지 않는다. 완료/부분완료 그룹 알림은 함께 닫는다.

## 검증 계획 (구현과 함께 선커밋)
- FileUploadsTest: 그룹 메타데이터 영속 복원·다른 이름/크기 혼입 거부·기존 파일 작업 회귀.
- 브라우저: 수십 파일/하위 폴더가 폴더 하나의 카드로 표시, 새로고침 복원, 취소 후 다음 파일 미생성, 서로 다른 폴더 분리·완료 알림 닫기. 기존 cancellable-uploads/uploads-pack-update 회귀.
- TS/Vite 빌드, 관련 backend. 전체 suite/운영 서버 변경은 생략.

## 검증 결과
- backend FileUploadsTest9 + FileUploadAccessTest1:10통과/실패0. 그룹 저장·재시작 복원, 크기·이름·파일 수 혼입 거부, 기존 ACL/취소 검증.
- UI12/12통과(18초): 폴더40개 파일 단일 카드·새로고침·일괄 닫기, 실제 directory-entry drop 후 취소/잔여 파일 생성0, 다른 폴더 분리·모바일, 기존 업로드/팩 회귀. 첫 drop fixture의 DataTransferItem 래퍼 교체 문제는 fixture만 수정한 후 재실행 성공.
- npm ci·TS/Vite·bootJar 성공. 전체 suite·다른OS 실기·운영 수정 없음.
