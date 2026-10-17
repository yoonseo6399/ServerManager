export const waitLabels:Record<string,string>={
 RELAY_STREAMS_ACTIVE:'플레이어 연결 종료 대기',GAME_RUNNING:'실행 중인 서버 종료 대기',LEGACY_GAME_RUNNING:'이전 방식 서버 종료 대기',
 SERVER_PREPARING:'서버 준비 작업 대기',INSTALL_ACTIVE:'서버 설치 대기',TRANSFER_ACTIVE:'파일 전송 대기',STOP_IN_PROGRESS:'서버 종료 대기',
 DIRTY_HANDOFF_PENDING:'파일 인계 대기',HOST_REPLACED_WHEN_IDLE:'유휴 시 상주 서비스 교체 대기',APPLY_WHEN_SAFE:'안전한 시점에 적용 대기',
 HOST_UNREACHABLE:'상주 서비스 연결 대기',HOST_PROTOCOL_INCOMPATIBLE:'상주 서비스 호환 확인 대기',RELAY_SERVICE_ACTIVE:'이전 상주 서비스의 중계 정리 필요'};
export const waitLabel=(reason:string|null)=>reason?(waitLabels[reason]||reason):'조건 확인 대기';
