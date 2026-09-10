## #968 코드 수준 상세 설계

기준 Cloud b987aab7b4 (#964 누적), qemu 2e78369 (#961 코드 포함). 각각 codex/fix-968-export-ownership 후속 브랜치에서 구현한다. upstream 미병합 수정은 보존한다.

### 확인된 원인

DrPlanOwnedTransportServiceImpl의 forward START/STOP은 resolveWorkerHostId(TARGET)가 고른 한 호스트만 호출한다. 과거 worker의 RUNNING intent는 남아 있고 dr_ablestack.sh reconcile이 이를 다시 기동할 수 있다. stop_item은 systemctl/kill 오류를 무시하고 종료 검증 없이 manifest를 삭제한다.

### Cloud 변경

- DrExportOwnershipStore: 전환마다 DB auto increment 기반 단조 증가 generation을 발급하고 plan별 과거 대상 host 집합을 영속화한다. host는 실행 위치 고정이 아니라 회수해야 할 쓰기 권한의 이력이다.
- 신규 grant 전에 대상 zone의 모든 등록 KVM worker(Down 포함)와 과거 발급 host의 합집합에 revoke를 수행한다. 대상 VM의 현재 배치와 무관하다. 누락/제거된 과거 host나 단절 worker는 fence 증거 없이 성공으로 간주하지 않는다.
- generation 2N의 STOP을 모든 후보가 실제 종료 확인으로 응답한 후, live placement로 선택한 worker에만 generation 2N+1의 START를 허용한다. 이후 전환은 항상 더 큰 generation을 사용한다. 지연 도착한 이전 START/STOP은 qemu에서 거절한다.
- 응답의 ownership protocol version/generation을 검증해 구형 Agent/engine의 일반 ok를 회수 증거로 사용하지 않는다. 실패는 DR_EXPORT_OWNERSHIP_PENDING 및 worker 정보를 포함해 기존 Run 오류/복원 PENDING 경로에 전달한다.
- 실제 failover의 reverse baseline 생성은 모든 writer drain 후 선택된 worker에서만 실행한다. test cleanup은 baseline 생성을 요구하지 않는다.

### qemu 변경

- 기존 plan transition flock 내부에서 영속 ownership generation/state tombstone을 검사하고 원자적으로 갱신한다. STOP을 durable 기록한 뒤 프로세스를 종료한다. 구형 generation 없는 명령은 아직 managed ownership이 없는 기존 plan에서만 호환된다.
- 같은 generation의 동일 operation은 멱등, 낮은 generation 또는 같은 generation의 상충 operation은 거절한다. reconcile의 과거 profile도 동일 gate를 통과해야 한다. STOP 뒤 재시작으로 옛 RUNNING intent가 살아나지 않아야 한다.
- STOP은 runtime 및 영속 manifest를 이용해 정확한 plan/device unit/PID를 찾고 종료를 검증한다. PID 재사용/무관한 process는 종료하지 않으며, 종료를 증명할 수 없으면 실패한다. manifest 누락 상태도 영속 증거를 확인한다.
- 기존 RBD/qcow2 transport와 source outage 계약은 유지한다. 원본 VM/볼륨 삭제나 전체 qemu-nbd kill은 사용하지 않는다.

### 검증/배포

Cloud 변경 DR/schema 모듈은 WSL ext4에서 빌드한다. qemu는 branch release Actions로 빌드하며 generation/reconcile/stop 실패/무관 PID 회귀를 release gate에 추가한다. 기존 tombstone/lifecycle/RBD/qcow2/source outage smoke를 실행한다.

32 RBD 기존 이중 export를 UI test failover drain으로 회수하고 cleanup 후 단일 export 및 새 durable incremental checkpoint를 확인한다. 31 qcow2도 회귀한다. PAUSED 유지, 지연 명령, worker 단절/복귀 및 Agent 재시작의 결과를 별도로 기록한다. 백업 후 qemu를 먼저 배포하고 Cloud를 배포하여 구형 엔진 응답을 성공으로 오인하지 않도록 한다. 검증 전 PASS를 선언하지 않는다.

범위: 발견된 forward 대상 writer 경로를 우선 수정한다. reverse export는 원본 site의 별도 권한이므로 forward host 이력을 적용하지 않으며, 기존 reverse/failback 회귀로 보존한다.