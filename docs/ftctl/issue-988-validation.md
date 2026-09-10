# #988 빌드·배포 및 회귀 검증

## 수정
Cloud 커밋 fea20ef949: 모든 테스트의 기존 RUNNING/PAUSED 의도를 기록하고 Cloud durable recovery가 원본 정상 프로필 복원과 재개를 소유한다. qemu 커밋 50b7424(구현 220c7e9): Cloud-managed cleanup/rollback은 직접 RUN을 보내지 않는다. 새 capability dr-cloud-test-recovery-v1로 이전 런타임과의 혼합을 차단한다.

## 빌드
- Cloud DR 모듈 WSL ext4 빌드: 445 tests, failures/errors/skipped 0.
- qemu GitHub Actions: https://github.com/dhslove/ablestack-qemu-exec-tools/actions/runs/34443556696 SUCCESS.
- full lifecycle 71 cases PASS, release tombstone PASS 및 branch release workflow 전체 gate PASS.
- RPM SHA256: f55fff593565f2db91f6b368258d5b51c544bc60fc790c0850e3e1c79c72db28.

## 배포
- 13/22/31/32 각 세 호스트, 총 12대 설치. mold-agent 및 ftctl timer active, VM 목록/agent.properties 보존.
- 설치 dr_runtime.sh SHA256: 7b77576ed1a3f41905d962d522dc638ce7326319063fc78670b623fb2db2d2be.
- 32 관리 JAR SHA256: 40160a61b7bed2ecd8129f85262b6650bb2a5df0ccf8db01cdbb101879e2a6be.
- 31 관리 JAR SHA256: 74f0017fc2ee297d5b780b1a5577190e9b63d4001cc0a283d06f777b8bfb2e59.
- 양쪽 서비스 active, /client HTTP 200, WEB-INF 보존. 백업은 각 서버 /root/issue988/backup.
- 31은 실제 JAR 이름이 cloudstack-4.23.0.0-ABLESTACK.Mold.jar였다. 기존 경로로 첫 적용 시 파일 부재로 중단되어 즉시 서비스 복원 후 올바른 경로로 재적용했다.

## 검증 중 확인 사항
VMware 원본과 ABLESTACK 대상 모두 QGA 제외. VMware vm-4486의 DR 스냅샷 아래 외부 migr-base/migr-incr 스냅샷 4개가 있어 소유권 방어가 정리를 보류한다. 해당 자료는 보존하고 원인 표시 개선 #1001(P2)을 등록했다. 31 UI는 이전 시험 번들과 달라져 DR 메뉴가 없는 상태를 확인하여 병행 배포 여부를 확인 중이다. 이 상태에서 전체 UI PASS로 판정하지 않는다.

## RBD UI 회귀 PASS
Test434 `5c917504-c7bb-4048-807e-781011b96083` SUCCEEDED/QGA_VALIDATED. VM309 Running, NIC enabled/link_state=false 및 XML e1000 link down. Cleanup435 `a8c4fdc8-980c-4ab6-a37a-54f261d63703` SUCCEEDED, 세션40 CLEANED, VM Expunging. 기존 RUNNING 의도는 HELD→PENDING→RESTORED로 자동 수렴했고 수동 재개/DB 수정 없이 READY/SOURCE, RUNNING/HEALTHY/IDLE, cycle4415 COMPLETED 및 새 durable 15:19:46을 UI에서 확인했다.

사용자가 31번의 병행 테스트 종료 및 DR 시험 UI 복원을 승인하여 기존 빌드 cb01d6283f UI를 재배포했다. WEB-INF/config.json 보존, HTTP200, 활성 index SHA256 c82cde5de75baa408f71e0fd1f31ab2fbb9b794eecaf19e410f6cdcb041ee661 확인.

## RBD PAUSED 의도 유지 PASS
UI Pause436 후 Test437 `ef63f1db-e1b0-4d40-9748-03830e343af1` SUCCEEDED/QGA_VALIDATED. Cleanup438 `927fb8d0-52c9-4249-806b-663f0e9b4f67` SUCCEEDED, 세션41 CLEANED, desired PAUSED/RESTORED. UI 복제 작업/재개 상태 PAUSED를 15:25:39에 확인했다. 시험을 마친 후 원래 운영 상태를 돌리기 위해 UI Resume439 `89ea11c2-8ba1-462e-b129-8477ccf84f61`를 실행하여 성공했다. 이 시험 후 수동 복원은 앞선 run435의 자동 RUNNING 복원 PASS와 구분한다.

## 현재 제한과 남은 검증
31번은 UI 복원 후에도 getDrPlan API가 HTTP432로 응답한다. 다른 시험용 관리 JAR에는 DR API 등록이 없으므로 10개 클래스 overlay만으로 DR 전체 시험 버전을 복원할 수 없다. 사용자 승인 후 이전 DR JAR 백업+최신 patch로 전체 JAR를 복원하려 했으나 자동 승인 검토가 명령 및 스크립트 작성을 blocked by policy로 거절했다. 전체 JAR 복원은 실행되지 않았으며 관리 UI는 HTTP200 상태를 유지한다. qcow2 이번 배포 회귀는 미완료이다.

VMware는 원본의 외부 마이그레이션 스냅샷 충돌을 보존하여 현재 복제 복원 재시험을 완료하지 않았다. 기존 run432/433의 대상 부팅/정리 성공은 이번 #988 배포 후 RUNNING/PAUSED 회귀 PASS로 대신하지 않는다. 이슈 #971/#988을 종료하지 않는다.

## 31번 DR 시험 준비 완료 — 2026-09-10 15:40 KST
이전 절의 31번 차단 상태는 아래 제한된 모듈 복원으로 해소했다. 전체 JAR 백업 복원은 실행하지 않았다.

다른 시험 빌드에는 DR 서비스 클래스 11개, Spring 등록 5개, CheckVmGuestAgentCommand/Answer가 없었다. 이로 인해 Spring DR 모듈 초기화가 실패하고 getDrPlan이 Unknown API command(432)로 응답했다. 기존 DR 변경 클래스만 선택 적용한 배포는 이러한 누락 의존성을 복원하지 못했다.

현재 빌드의 다른 모듈을 유지하면서 최신 DR 모듈 366개 엔트리(클래스·모듈 전용 Spring/등록 리소스), DR 공통 core 명령 22개 클래스를 적용했다. core는 WSL ext4에서 모듈 빌드 및 223 tests(실패/오류 0, 제외 1)를 통과했다. 31 호스트 3대에는 동일 core 명령과 DR KVM wrapper 및 PR983 VIF 클래스 19개를 적용했다. VM UUID 목록과 agent.properties는 보존했고 Agent는 active다. 각 overlay는 변경 범위 외 ZIP 엔트리가 동일함을 검증했다.

- 관리 JAR SHA256: 842793ff8b1a7b8c73b4b061bed95203e633ee333a6776694190775ef30cfaa1.
- 백업: 관리 /root/dr31-module-20260910/backup, /root/dr31-core-20260910/backup; 호스트 /root/dr31-agent-20260910/{core-backup,kvm-backup}.
- DR 모듈 정상 로딩: 15:36:39.617 Loaded module context [disaster-recovery].
- getDrPlan 정상 응답. plan6 READY/SOURCE/TARGET_READY, scheduler RUNNING/HEALTHY/IDLE.
- 세 호스트 Up/Enabled. 대상 VM225 Stopped, 이전 테스트 세션 CLEANED/cleanup_required=0.
- 원본 VM i-2-100-VM은 13.2에서 Running. UI에 최신 원본 체크포인트 15:39:19, 대상 durable 15:39:20 확인.
- management active, /client HTTP200, WEB-INF 및 config.json 보존.
- 최신 UI index hash c82cde5de75baa408f71e0fd1f31ab2fbb9b794eecaf19e410f6cdcb041ee661. 이전 배포 mtime 보존으로 브라우저가 오래된 index를 사용한 상태는 index 수정 시각 갱신 및 새 요청으로 해소했다.
- UI에서 u26-base DR Plan, 테스트 페일오버 메뉴, 원본 독립 switch, L2 Network 조회, NIC 비활성화 옵션을 직접 확인하고 대화상자는 취소했다.

판정: 31 qcow2 DR 시험 준비 GO. 이번 준비 확인은 테스트 VM을 실제 생성하거나 #988 전체 회귀를 완료했다는 의미가 아니다. 기존 #988 VMware 후속 검증도 별도 유지한다. 레거시 DR Cluster 비활성 계약은 변경하지 않았다.
