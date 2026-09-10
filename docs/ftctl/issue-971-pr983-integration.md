# #971 / PR #983 통합 검증

## 병합과 중복 방지

PR #983은 2026-09-10 13:32:21 KST에 ablestack-europa로 병합되었다. 실제 병합 커밋은 `014895d8f3dc2f062f379b51ee62d36a0adae88a`이다. 현재 작업 브랜치와 git merge-tree 검사 및 실제 병합 모두 충돌 없이 완료했다. 통합 커밋은 `10f9e78019`이다.

#971은 DR 모듈/API/UI를 수정한다. #983의 5개 KVM VIF 드라이버 수정은 원본 커밋을 병합하여 사용하며 재구현하지 않았다. #982는 이 PR로 코드 수정이 병합되어 종료했다. #981은 DR 테스트 VM 생성 시 NIC 유지와 명시적 비활성화라는 별도 역할이므로 유지한다. 두 값(enabled/link_state)을 false로 맞춘 뒤 시작하므로 #983의 linkState 기준 동작과 일치한다.

## 빌드와 배포

- DR 모듈: 442 tests, failures/errors 0.
- #983 포함 KVM 모듈: 729 tests, failures/errors 0, skipped 3.
- 31/32 관리 서버: DR 변경 클래스 배포 후 mold active, /client HTTP 200, WEB-INF 보존.
- 31.1~3 / 32.1~3: #983의 Bridge/Direct/Ivs/Ovs/VRouter 클래스 배포. 기존 JAR 백업 및 선택된 클래스 외의 내용 보존 검증. VM UUID 목록, agent.properties 해시 유지, mold-agent active.
- 32에서는 Agent stop이 외부 재시작과 충돌하여 취소되었다. 배포 중에만 runtime mask를 적용했으며 완료 후 모두 unmask했다.
- #983의 PR CI에는 RAT/pre-commit 실패와 진행 중 작업이 있어 전체 CI PASS로 판단하지 않는다. 통합 모듈 검증 결과와 구분한다.

## 원본 단절 중 통합 기능 검증

장애 주입은 대상 관리 서버에서 원본 Mold TCP 8080 연결만 차단했다. source VM이나 전체 사이트 전원 장애 시험과 동일시하지 않는다. 자동 해제 타이머를 설정했다.

| 경로 | Run | 테스트 VM | 결과 |
|---|---|---|---|
| qcow2 → qcow2, 13 → 31 | 315 / 6341ecbe-9609-4618-81b6-7d8ed1ec6446 | i-2-260-VM | 원본 단절 중 SUCCEEDED, QGA_VALIDATED |
| RBD → RBD, 22 → 32 | 424 / 5c94dc29-ecc6-4e9e-9ac4-b1545d61550f | i-2-305-VM | 원본 단절 중 SUCCEEDED, QGA_VALIDATED |

두 VM 모두 NIC가 존재하고 DB enabled=false/link_state=false이며, 실제 libvirt XML에서도 link state=down을 확인했다. qcow2는 virtio, RBD는 e1000 모델이다. NIC 삭제나 수동 링크 교정 없이 통과했다.

정리 단계에서 DB getDrPlan 응답은 stopTestFailover.enabled=true이지만 오래된 보호 스냅샷이 UI 버튼을 disabled로 덮어쓰는 별도 문제가 재현되어 #985로 등록했다. fetchProtectionView가 최신 DB 계획 응답을 함께 읽고 작업 가능 상태를 최종 병합하도록 수정했다. 아래 최종 검증 결과와 전체 범위의 완료 판정을 구분한다.
## 최종 UI 재검증 및 자동 복제 복원 (2026-09-10)

- #985 수정 후 최신 UI의 정리 메뉴가 원본 Mold 단절 중에도 유지되었다. qcow2 cleanup run `14415b8c-efd2-4bd0-aaf3-ad644554d767`(316), RBD cleanup run `8f9349f3-08eb-4bc5-bbf0-f4955d1f514e`(425)가 단절 해제 전 SUCCEEDED로 완료되었다. 테스트 세션은 CLEANED이며 테스트 VM의 NIC/VM 정리 상태도 확인했다.
- RUNNING 의도 복원 시험: qcow2 normal test run `6e7fcb85-a077-43d4-a347-735fbace4dbf`(318) 후 원본 연결을 차단하고 cleanup `8436dae9-ce5c-4eb2-9f64-e6ccb0f82c78`(319)을 UI에서 실행했다. Cleanup은 성공했으며 durable recovery row는 desired=RUNNING/PENDING을 유지했다. 연결 복구 뒤 UI 재개/API 재개/DB 수정을 하지 않고 RESTORED, READY/SOURCE, scheduler RUNNING/HEALTHY로 수렴했다. 새로운 대상 durable checkpoint 시각은 14:09:40이다.
- PAUSED 의도는 앞의 독립 테스트 정리에서 유지되었다. 테스트 정리 성공과 원본 복제 재개 대기를 별개로 검증했다.
- RBD 재해 전환 run `c335263b-05b4-4bed-b4ab-270646031a3c`(426): 원본 VM332를 안전하게 종료하고 Cloud UI에서도 Stopped로 정리한 뒤, 대상 관리 서버→원본 Mold 8080 차단 중 UI에서 disaster failover 실행. 14:10:49 시작, 14:11:07 SUCCEEDED. 대상 VM287 Running 및 직접 QGA guest-ping 성공을 차단 해제 전에 확인했다. 원본 VM의 실제 정지 증거를 확보한 통제 시험이며 원본 전체 사이트 장애와 동일시하지 않는다.
- UI unit test 26 PASS, production UI build PASS. #985 변경은 `cb01d6283f`이며 31/32 활성 webapp에 배포했다.
- 사이트 연결 복구 직후 페일백 사전 점검에 과거 DISCONNECTED가 남는 현상은 별도 P2 #986으로 등록했다. 기본 300초 주기 점검은 존재한다. 영구적인 자동 복구 불능으로 분류하지 않는다.

## 검증 범위와 남은 제약

실환경 단절은 원본 Mold TCP 8080 차단 및 아래 qcow2 원본 관리/컴퓨트 전체 통신 차단으로 구분한다. 전체 사이트 전원 단절과 동일시하지 않는다. VMware→RBD 및 혼합 스토리지 경로 전체의 완료 여부도 별도 기록한다. 미검증 항목은 #971/#922 회귀 완료 기준에 남긴다.

원본 독립 테스트는 대상에 이미 존재하는 일치하는 immutable sealed checkpoint를 사용한다. 매 복제 주기의 checkpoint 자동 발행/보존/GC는 #979(P1) 범위이며, 유효 seal이 없으면 명확하게 실패해야 한다. #980(P2)의 RESUME 응답 유실/세대 재시도 문제도 별도 후속이다. #971 이슈를 이 두 경로의 결과만으로 전체 완료/종료하지 않는다.
### RBD 페일백 완료

Run `7c4054e3-9e8d-4d24-8f4c-0ff6645f9229`(427)는 14:17:45 시작, 14:22:34 SUCCEEDED. UI의 target-stop/source-start/source-boot-validation/authority-commit/scheduler-resume/post-checkpoint가 모두 SUCCEEDED였다. 원본 i-2-332-VM은 Running 및 직접 QGA guest-ping 성공, 대상 i-2-287-VM은 종료되어 호스트 도메인이 제거되었다. 계획은 READY/SOURCE, scheduler RUNNING/HEALTHY, recovery SUCCEEDED, 새 대상 durable 시각 14:22:26으로 복원되었다.

복원 과정에서 이전 scheduler의 incremental transfer 실패 이벤트 이후 새 scheduler run으로 full seed가 자동 진행되는 과도 상태가 있었다. 수동 재시작/DB 교정 없이 새 checkpoint와 정상 상태로 수렴했다. 이 과정의 ERROR/DEAD 순간 응답을 최종 실패 또는 정상 완료로 잘못 판단하지 않았다.
### qcow2 원본 관리/컴퓨트 전체 통신 차단 추가 시험

13→31 경로에서 31 관리 서버 및 세 컴퓨트 호스트 모두에 원본 13.10/13.1/13.2/13.3의 INPUT/OUTPUT DROP 규칙을 적용했다. 각 노드에 10분 후 자동 해제 타이머를 먼저 설치했다. 대상 호스트에서 원본 Mold 8080 및 세 호스트 SSH가 모두 도달 불가임을 확인했다. 원본 내부 전원/서비스는 유지한 네트워크 partition 시험이다.

- Normal test 준비 run321 `d6b19518-443f-445c-ae7c-85e8c07560e1`, cleanup322 `9712cc63-22c4-493c-99d7-ac032aec2c60` SUCCEEDED. Cleanup은 전체 통신 차단 중 수행했다.
- 같은 단절 중 sourceIndependent test323 `134b1b50-5117-456d-9b89-c156c95d5359` SUCCEEDED, session56 QGA_VALIDATED. i-2-263-VM의 직접 QGA 성공과 libvirt NIC link=down을 14:26에 확인했다.
- Cleanup324 `31b576fc-d807-4550-bffc-e20d55ac5a2d`도 단절 해제 전에 SUCCEEDED, session56 CLEANED로 완료했다.
- 이후 네 노드에서 해당 규칙을 전부 제거하고 자동 해제 타이머를 중지했다. 준비를 위해 일시 중지했던 계획은 UI 동기화 재개로 원래 RUNNING 의도로 돌렸다. 앞서 run319에서 검증한 자동 복제 복원과 이 수동 시험 준비 복원을 구분한다.