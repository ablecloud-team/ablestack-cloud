# #988 빌드·배포 및 회귀 검증

## 최종 검증 요약 — 2026-09-10 16:46 KST
중단됐던 정상 경로 회귀를 완료했다. 아래 과거 시점의 차단 기록은 이 최종 결과와 구분한다.

| 경로/검증 | 결과 |
|---|---|
| 31 qcow2: 원본13개별주소 모두 단절 중 정리, 복구 후 자동RUNNING | PASS,343/344 |
| 32 RBD: RUNNING 자동 복원 및 PAUSED 유지 | PASS,434–439 |
| VMware 전체·CBT 복제본 실제 Rocky10.1/virtio 부팅 | PASS,444/447 (#1003 sh 수정) |
| VMware vCenter 단절 중 cleanup, 복구 후 자동RUNNING·새 증분 | PASS,444/445 |
| VMware PAUSED 의도 유지 | PASS,447/448 |
| VMware vCenter 단절 전부터 source-independent 테스트·실제부팅·정리 | PASS,449/450 |

VMware 양단 QGA 제외. NIC 존재/비활성화 유지. 이번 VMware 장애 주입은 vCenter21.10의 정확한 IP 차단이며 ESXi 전체 전원 장애나 모든 혼합 경로 검증으로 확대 해석하지 않는다. #1004 비동기 준비 실패의 잔여 clone/HELD intent 문제는 미수정 P1 후속이다. 따라서 실패경로를 포함한 모든 DR 계약 및 이슈 전체가 무조건 해결됐다는 판정은 하지 않는다.

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

## 31 qcow2 회귀 및 JSON 이력 복원 — 2026-09-10
다른 시험 빌드에서 기존 #965 `ApiResponseSerializer` 수정도 없어져 `listDrRuns` 중첩 JSON의 `enabled\=false` 응답이 파싱되지 않았다. 기존 커밋 e28fe4cef0의 해당 클래스만 다시 배포했다. WSL server 모듈 targeted ApiResponseSerializerTest 3건 PASS, API JSON 파싱 및 UI 작업 이력 정상 표시 확인. 새로운 구현이 아니라 기존 수정의 누락 복원이다.
- 관리 JAR 최종 SHA256: 2b450df182305861d877e554c08aa9a5fc6dac81727b52075c0806d135b67684.
- 백업 /root/dr31-restore965/backup. 관리 서비스 active, WEB-INF 및 /client HTTP200 확인.
- Test343 `f77b86fe-cb5b-422b-9374-424aa55e51cd`: 15:46:06–15:46:54 SUCCEEDED/QGA_VALIDATED. 세션57, VM264, NIC enabled=0/link_state=0.
- 테스트 ACTIVE 중 관리 서비스 재시작 뒤 복제 의도 유지.
- 31 관리·호스트 4대에서 원본 13.1/2/3/10 정확한 IP에 INPUT/OUTPUT DROP. 원본 SSH timeout/HTTP000 확인.
- 단절 중 UI Cleanup344 `080b1448-78df-4203-8130-227c96ae4036`: 15:54:16–15:54:25 SUCCEEDED. 세션57 CLEANED, VM264 Expunging/NIC 없음. RUNNING 복원 의도 PENDING.
- 모든 시험 DROP 규칙/복원 타이머 해제 뒤 수동 Resume/API/DB 수정 없이 RESTORED, READY/SOURCE, RUNNING/HEALTHY/IDLE, cycle1435 COMPLETED 및 새 durable 15:56:21 확인. PASS.

## VMware 준비 실패 발견 및 추적
외부 마이그레이션 시험 종료를 사용자에게 확인했다. DR 소유 snapshot62741만 non-recursive 제거하고 외부 snapshot62742/62744/62745/62747 네 개 ID/이름 보존 확인. 이는 시험 fixture 정리이며 자동 복원 PASS 근거가 아니다.

cycle9 증분 331 extents/93,046,898,688 bytes, durable15:55:15 후 Test440 `b7a6fa42-2642-4695-8050-40636457daf1`이 DR_GUEST_PREPARATION_FAILED. 실패 clone에서 진단 로그를 켜 재현하자 bootstrap rc80, root_partition_not_found, XFS totally zeroed log/LSN(13:91749) ahead(1:0) 확인. 원본/기준 디스크 수리하지 않았다. VMware 양단 QGA는 호출하지 않았다.

- P1 #1003: 증분 완료 후 복제본 파일시스템 무결성 원인 분석/방어. 외부 snapshot 변경과의 인과관계 미확정.
- P1 #1004: 비동기 TEST_PREPARE 실패가 active session 발행 전 run session artifacts를 누락하여 cleanup 성공 오판, 원래 RUNNING 의도 HELD/실제 PAUSED 잔류. UI 정리 메뉴 누락과 상세 로그/원래 종료 코드 누락 포함.
- 실패한 RBD clone과 seal은 진단 증거로 남아 있다. 새 전체 재동기화 UI Run441은 기준 복구 시험이며 run440 자동 복구 성공으로 취급하지 않는다.

## VMware RUNNING 정리 및 자동 복원 PASS (#1003 수정 후)
실측 원인은 nbdkit VDDK single-link=true의 부모 체인 읽기 누락이었다. 같은 snapshot62747/path의 첫1MiB 비교에서 true는0, false는 정상GPT. qemu 90aeb14로 양 full/CBT read를 false로 수정했다. 사용자 지시에 따라 Actions34449382529는 취소하고 32.1/2/3에 변경 sh 한 파일을 직접 배포했다. RPM 전체 릴리즈 완료가 아니다. 설치 mover SHA256 0892274ffff79add112b8e27d56655599cd640eabb7cd768d44dc7c555b4005a, VMware snapshot/TLS 모듈 스모크 PASS. 나머지 #988 runtime 코드는 기존 검증된 배포와 같다.

UI Full-reseed443 `8b6e3ed5-0c77-4988-a6f1-4e9ea73785d8` 완료 후 Test444 `94601df4-1bc7-4473-9b1a-9a87c1d7c51a` 16:30:25–16:32:06 SUCCEEDED/POWER_STATE_VALIDATED. VM311 실제 Rocky Linux10.1 로그인 화면, virtio-scsi DRIVER_OK/FEATURES_OK/broken=false 확인. NIC DB enabled/link_state=0, XML e1000 link down. VMware 원본/대상 QGA 호출 없음.

32 관리/호스트4대에서21.10/32 INPUT+OUTPUT DROP, vCenter HTTPS timeout/000 확인. Cleanup445 `58953ac3-c92b-4231-b16f-bfd8b06c8920` 16:33:52–16:34:03 SUCCEEDED를 UI 이력에서 확인. 세션44 CLEANED/VM311 Expunging/NIC없음. RUNNING 의도 PENDING.

모든 시험 규칙과 timer 제거 후 수동Resume/API/DB수정 없이 RESTORED, READY/SOURCE, RUNNING/HEALTHY/IDLE. 새 cycle12 CBT_INCREMENTAL/LOCAL_DURABLE,39extents/2,883,584bytes, sourceReadBytes=targetWrittenBytes, targetdurable16:34:47 확인. UI에서도 같은 새 체크포인트/주기 완료를 확인했다. 전체seed수동재실행은 #1003 이전 데이터 복구를 위한 fixture 작업이며 이 cleanup445 자동 복원에는 사용하지 않았다.

## VMware PAUSED 보존 / 증분 복제본 부팅 PASS
UI Pause446 `f743ce09-92ac-476e-9976-b746eb548d9b` 후 Test447 `67d3dd21-78c8-462a-b688-e7b98b326d81` SUCCEEDED/POWER_STATE_VALIDATED. VM312의 실제 Rocky Linux10.1 로그인 화면과 virtio-scsi DRIVER_OK 확인. 전체seed뿐 아니라 cycle12의 CBT 증분 적용 복제본도 부팅 검증했다.
Cleanup448 `8ec52402-a038-4b96-9cad-63f56af1bcd8` SUCCEEDED, 세션45 CLEANED, desired PAUSED/RESTORED. UI 복제 작업/재개 상태 PAUSED 유지. QGA는 어느 쪽에도 호출하지 않았다.

## VMware 원본 독립 테스트 PASS / 종료 상태
32 관리·호스트4대에21.10/32 차단을 먼저 적용하고 UI source-independent switch를 켠 Test449 `35ae8cba-0fee-4014-abe9-3adc69bfaacc` 16:40:01–16:41:38 SUCCEEDED. VM313 NIC enabled/link_state=0, 실제 Rocky Linux10.1 로그인 화면 및 virtio-scsi DRIVER_OK 확인. 최초 콘솔은 커널 시작 화면이었으므로 OS 준비 후 다시 확인했다. 부팅 이후에도 vCenter HTTP000으로 단절 유지 확인.
Cleanup450 `52c4dc14-f49d-4d26-b0d8-b1d93ccf1456` 16:43:31–16:43:41 SUCCEEDED, 세션46 CLEANED/VM313 Expunging. 원본 단절 중 PAUSED/RESTORED 유지. UI 이력 확인 후 차단 규칙/timer 제거.

시험 전 RUNNING 상태로 되돌리는 UI Resume451 `49f0309d-866b-4c53-8e8f-d38c9f7930ba` SUCCEEDED. 이는 PAUSED 시험 종료 후 fixture 복원이며 cleanup445 자동RUNNING PASS와 구분한다. 최종 UI cycle13 incremental COMPLETED, RUNNING/HEALTHY/IDLE, durable16:44:31.

31·32 관리/호스트8대 iptables에 issue971/issue988 규칙 없음. 두 관리 서버 mold.service active, WEB-INF 존재, /client HTTP200. plan6/51/52 READY/SOURCE, ACTIVE test session 없음. #1004의 실패 세션42/43 및 진단용 clone 두 개/HELD intent는 별도로 보존되어 있으며 정상 세션44/45/46 정리 완료와 혼동하지 않는다. 직접 DB 수정 없음.
