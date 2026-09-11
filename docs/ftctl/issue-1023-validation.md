# #1023 검증 기록 (2026-09-11)

## 원인과 감사 범위
13 관리 서버에서 00:00 active202 → 01:00 active250 및 API 실패를 확인했다. 임시 재시작 후에도 약 1분마다 하나씩 active 연결이 증가했고 배포 전 다시40에 도달했다. Hikari pool entry는 수십 분 동안 IN_USE이나 일반 Transaction MBean/GlobalLock 목록에는 없었다.

60초 누수 탐지 경고는 설치 통합 JAR의 SLF4J NOPLogger 때문에 출력되지 않았다. 비공개 네트워크 포트를 열지 않는 JVM 로컬 진단으로 3분 한정 스택을 수집했고 09:17:48, 09:18:48, 09:19:48에 같은 HA 결과 처리 stack을 확보했다. BaseHATask → HealthCheckTask → HAManagerImpl → KVMHostActivityChecker → ResourceManagerImpl → TransactionLegacy.getConnection이다. 주기와 누수 스택이 일치한다.

DR runtime/원격 저널, 공통 TransactionLegacy, GlobalLock/DbUtil, ConnectionConcierge, VM IP 수집, Guest OS 및 관리 HA task를 조사했다. standalone JDBC와 transaction-owned 연결의 반환 책임을 구분했다.

## 코드 및 회귀
- BaseHATask 외부 call/processResult 및 내부 performAction 각각의 thread에 try-with-resources TransactionLegacy context 추가. timeout 직후 사용 중인 내부 연결을 외부에서 닫지 않고 해당 작업 종료 때 반환한다.
- #1026 findDoubleNames Connection/Statement/ResultSet의 성공·실패 반환 수정.
- 기존 HA 코드: 신규 4개 회귀 모두 FAIL. 수정본: 4 PASS. 기존 GuestOS 코드: 신규5 모두 FAIL. 수정본: 5 PASS.
- server Maven package: 3125 tests, 실패0/오류0/기존skip5, BUILD SUCCESS.
- engine/schema Maven package: 390 tests, 실패0/오류0/skip0, BUILD SUCCESS.
- disaster-recovery Maven package: 451 tests, 실패0/오류0/skip0, BUILD SUCCESS.
- server 초기 전체 실행에서 신규 테스트가 JUnit caller의 기존 DB context를 상속해 실패했다. 실제 HA executor처럼 fresh thread에서 시험하도록 격리 후 전체 suite를 재실행해 통과했다.
- 로컬 API 의존 모듈이 오래돼 BackupQosBandwidthLimitMbps가 없어 최초 server compile이 실패했다. 현재 API 모듈 install로 의존 버전을 맞춘 후 server 전체 빌드를 통과했다. 전체 Cloud build는 실행하지 않았다.

## 배포 원칙
통합 JAR를 백업한 뒤 BaseHATask.class/BaseHATask$1.class/GuestOSDaoImpl.class 세 항목만 변경한다. 다른 모든 항목을 byte 단위 비교하고 원래 소유권·권한을 보존한다. UI webapp은 변경하지 않고 WEB-INF 존재를 확인한다. qemu 변경/패키지 배포는 없다.

## 후속 이슈
- #1026: 추가 Guest OS JDBC 누수. 이번 수정에 포함.
- #1027: 통합 JAR Hikari NOPLogger. 별도 packaging/로그 검증 필요.
- #1028: checkConnection validation 실패 시 참조 유실 및 연결 소유권. 공통 transaction 의미를 별도로 설계·검증.
- #1029: Diplo P1 이식. upstream 678835c200 동일 HA 경로 확인, 아직 Diplo 배포하지 않음.

증거 위치: `/home/ablecloud/work/issue1023-evidence/`의 long-borrows.txt, pool-details.txt, pre-deploy-pool.txt, ha-baseline-regression.log, guest-baseline-regression.log, server-build.log, schema-build.log, dr-build.log. JVM 진단 파일은 테스트 환경에만 두고 저장소에 비밀정보를 넣지 않는다.

## 배포 후 확인
- 13/22/31/32 관리 서버에 세 클래스 적용 완료. 각 서버 `/root/issue1023-20260911/`에 원본 JAR와 deployment.json 보존. 모든 다른 JAR 항목 byte 동일, WEB-INF 보존, mold active, /client HTTP200 확인.
- SHA256 BaseHATask: e92589dd2d7b272c3b0340551b8862649f632f40197fb8902860b831bad208d8; inner class: a2820447a175681e912e382206e0cb84093fa49665989f926b2d87664753eed6; GuestOSDaoImpl: 3f0e1dd61f4502fef1a8fc0d035505bd9febd3df4a7665958e999ebdf8f57f74.
- 13번 09:32:05~09:38:08, 6분간7회 active: 2,2,1,3,1,1,1. 모든 waiting0. HA-InnerTask의 host 검사 지속을 확인했다. DB pool 크기와 HA 정책을 변경하지 않았다. 진단용 leak threshold는 관리 서비스 재기동으로 기본0으로 돌아왔다.
- 31 UI 로그인과 qcow2 pause d10b3964-67f0-4fb7-83d8-27b84364a9d6(09:32:42~44), resume c16156bd-002e-4073-bd61-110dec35007e(09:33:25~30) 모두 SUCCEEDED. 31 관리 재기동 뒤 UI 재로그인과 이력 확인도 성공.
- RBD checkpoint4633 및 VMware199 READY/RUNNING/HEALTHY 확인. VMware QGA 호출 없음.

## 별도 DR 후속 결함
qcow2 다음1555에서 candidate.runUuid(새 Resume)와 pending.producerRunUuid(기존 worker)가 달라 IDENTITY_MISMATCH/exit108이 발생했다. #1030 P1로 등록했다. 이번 qemu 코드 변경은 없으며 DB 연결 반환 수정과 별도 producer 계약 문제다. 기존1554는 보존됐다. UI 전체 재동기화 a6e75ded-3300-4a09-887e-99815ebe7fd4는 Run이 SUCCEEDED여도 후속1556에서 같은 불일치가 재발했다. 이를 PASS로 계산하지 않는다. 해당 DR scheduler만 수동 종료한 뒤 UI 전체 재동기화 562a81d3-1ce4-4e6c-a0a1-90d64a6fb751로 새 작업자를 시작했고1557 전송을 진행했다. VM/스토리지 데이터나 체크포인트 메타데이터를 수동 수정하지 않았다. 작업 이력 SUCCEEDED만으로 전체 복제 데이터 완료를 주장하지 않는다.

## 범위
이번 DB 반환 PASS는 확인된 HA 주기 누수, 단위 예외/timeout 정리, 변경 모듈 및 API/UI 제어 검증이다. 모든 유형의 연결 누수 제거 또는 장시간 물리 호스트 장애/HA fencing 전체 시험을 의미하지 않는다. 추가 #1027/#1028/#1029/#1030은 별도 후속이다. Cloud PR #1022의 기존 upstream 충돌도 별도 통합 범위다.
