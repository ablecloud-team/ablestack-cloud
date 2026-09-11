# #1023: 공통 HA 작업 DB 연결 수명 및 추가 누수 수정

## 안전한 기준
PR #1022의 `1269c4066b`를 보존한 `codex/fix-1023-db-connection-lifecycle`에서 진행한다. 기존 DR/qemu 개선은 재작성하지 않는다. qemu production 변경은 없다.

## 조사 증거
13 관리 서버에서 00:00 active202 → 01:00 active250 고갈. 재시작 후에도 약 1분마다 한 연결의 장기 점유가 증가했다. JVM pool entry와 transaction/global-lock 목록을 비교했고 제한된 leak task stack 수집에서 `BaseHATask.call → HealthCheckTask.processResult → HAManagerImpl.transitionHAState → KVMHostActivityChecker.getNeighbors → ResourceManagerImpl.listHostsInClusterByStatus → TransactionLegacy.getConnection`을 확인했다. 기본 Hikari 누수 로그는 NOPLogger로 출력되지 않았다(#1027).

## 구현
- server `BaseHATask.call` 외부 실행 전체(결과 처리 포함)를 `try (TransactionLegacy.open(...))`로 감싼다.
- 별도 innerExecutor의 performAction도 자신의 try-with-resources DB context를 갖는다. 외부 timeout 이후에도 내부 thread가 종료할 때 그 thread가 자신의 연결을 정리한다. timeout 이후 다른 thread의 연결을 강제로 닫지 않는다.
- HA 판정/복구/펜싱 정책과 DR source-unreachable failover 조건은 변경하지 않는다. DB pool 크기 증가는 하지 않는다.
- 추가 확인된 #1026 GuestOSDaoImpl.findDoubleNames standalone connection/statement/resultset을 try-with-resources로 정리하고 SQL 취득 실패 원인을 보존한다. 업그레이드 조회 누수이며 주기 HA 누수와 구분한다.
- 공통 TransactionLegacy.checkConnection의 invalid connection 참조 유실과 connection ownership 전환은 #1028로 등록했으며 전역 잠금도 조사했다. 이번 재현과 직접 연결되지 않는 공통 트랜잭션 의미 변경은 별도 검토 대상으로 기록한다.

## 검증 및 배포
외부/내부 정상·예외·timeout 종료의 연결 close 테스트, Guest OS 성공·prepare/query/read/취득 실패 테스트. WSL ext4 변경 server/engine-schema 모듈 빌드와 DR 회귀. 설치 통합 JAR는 백업하고 변경 클래스만 교체하여 다른 시험·DR 수정 클래스를 보존한다. 13 관리 서버에서 pool active의 분당 누적이 멈추는지 관찰하고 31 UI에서 source capability/DR pause/resume 및 체크포인트 확정을 확인한다. 관리 서비스 재기동은 배포 절차로 기록하며 무개입 장애 복원 시험으로 계산하지 않는다.

## Diplo 후속
P1 #1029: 최신 upstream ablestack-diplo 678835c200에도 동일 BaseHATask 경로를 확인했다. 필요한 공통 수정만 별도 브랜치/PR로 이식하고 Diplo 빌드·배포물·UI 및 연결 반환을 검증한다. 이번 Europa 작업에서 Diplo 배포 완료를 주장하지 않는다.
