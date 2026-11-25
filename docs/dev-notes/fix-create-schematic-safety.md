상태: 패치 빌드·단위·실제 모드팩 기존9/패치10개 검사 통과, 운영 미적용
# fix/create-schematic-safety
- Survival21:56:24 크래시: Schematicannon(-1378,242,4075)이 업로드된RudeoduD/_.nbt(135168bytes)를 읽다 EOFException으로 종료. 실제 gzip 해제에서 끝마커 누락 재현. 원본과 월드는 보존했다.
- Create6.0.10 loadSchematic는 IOException만 처리한다. Minecraft NbtIo의 래핑된 NBT 예외가 빠져나오므로 전체 메서드 WrapMethod에서 원인 체인의 IO/NBT 파일 오류만 빈 새 템플릿으로 처리한다. 정상파일·무관한 예외·JVM Error·입력파일은 그대로다. 로그는 파일별60초/최대1024개 제한.
- 서버 전용 별도 애드온 소스는 server-patches/schematic-safety. ServerManager 설치판에 자동 패키징하지 않는다. VERSION/공개 릴리즈 변경 없음.
- 선커밋 검증 계획: 순수Java cause-chain/정상반환/손상EOF/무관예외/JVM Error/cycle/수정후재시도; 실제모드팩 격리서버에서 기존 코드와 패치코드의 실제 손상파일/정상파일/파일교체/설계도프린터 호출을 비교. 운영월드는 테스트에 쓰지 않는다. 전체 ServerManager suite/다른OS 미실행.

- 실제 원인 확정: gzip 미완성 EOF와 실제 Create+Railways+Pattern/Sable 모드 로딩 경로의 net.minecraft.nbt.ReportedNbtException을 동일 파일 복사본으로 재현했다. 파일이 잘린 구체적인 업로드 원인은 미확정이다.
- Java 단위 검사 통과. 기존 실제모드팩9개/패치10개 비교 체크 모두 통과. 정상블록1개/손상 gzip/손상NBT/파일없음/정상재업로드/프린터/실제 Schematicannon.initializePrinter를 검증했다. 기존은 예외, 패치는 빈템플릿 및 STOPPED/schematicExpired. 파일 바이트 보존, 기존 Railways·Pattern 등의 mixin과 실제 함께 로드됨을 확인했다.
- 초기 테스트 하네스의 금지된 Files 클래스/ItemStack JSON 변환 문제와 테스트 컴파일 Registrate 의존성은 테스트 전용 Java 도우미로 정리했다. 초기 결과는 로컬 작업 기록에 보존했다. 테스트 전용 클래스는 배포JAR에 포함하지 않았다.
- 테스트는 stop 명령 후 저장 완료 및 결과 기록을 확인했으며, 남은 테스트 JVM을 종료(143)했다. 정상 종료코드0으로 기록하지 않는다. 운영 프로세스/월드/설계도 파일은 변경하지 않았다. 전체 ServerManager suite/다른OS/실제 플레이어 로그인 미실행.
- 서버 전용 패치JAR 및 ZIP은 로컬 deployments/schematic-safety-20261125에 제공한다. 운영에 설치/재시작하거나 ServerManager 새 릴리즈를 게시하지 않았다.
