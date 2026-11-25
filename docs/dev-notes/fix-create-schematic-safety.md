상태: Create 설계도 파일 로딩 경계 패치·테스트 계획 구현, 커밋 후 검증 예정
# fix/create-schematic-safety
- Survival21:56:24 크래시: Schematicannon(-1378,242,4075)이 업로드된RudeoduD/_.nbt(135168bytes)를 읽다 EOFException으로 종료. 실제 gzip 해제에서 끝마커 누락 재현. 원본과 월드는 보존했다.
- Create6.0.10 loadSchematic는 IOException만 처리한다. Minecraft NbtIo의 래핑된 NBT 예외가 빠져나오므로 전체 메서드 WrapMethod에서 원인 체인의 IO/NBT 파일 오류만 빈 새 템플릿으로 처리한다. 정상파일·무관한 예외·JVM Error·입력파일은 그대로다. 로그는 파일별60초/최대1024개 제한.
- 서버 전용 별도 애드온 소스는 server-patches/schematic-safety. ServerManager 설치판에 자동 패키징하지 않는다. VERSION/공개 릴리즈 변경 없음.
- 선커밋 검증 계획: 순수Java cause-chain/정상반환/손상EOF/무관예외/JVM Error/cycle/수정후재시도; 실제모드팩 격리서버에서 기존 코드와 패치코드의 실제 손상파일/정상파일/파일교체/설계도프린터 호출을 비교. 운영월드는 테스트에 쓰지 않는다. 전체 ServerManager suite/다른OS 미실행.
