## #971 코드 수준 설계

기준 Cloud f616359046 / qemu7a0880d (#970 실측 검증 완료), 별도 WSL ext4 worktree와 `codex/fix-971-source-independent-recovery` 생성. 기존 브랜치 병합·수정 없음.

### 안전성 발견과 경계
현재 site-agent NBD 복제는 대상 이미지를 제자리 수정한다. Cloud의 last completed record가 있더라도 다음 cycle의 부분 쓰기가 반영되었을 수 있다. 원본 PAUSE 실패를 무시하고 현재 backing으로 새 snapshot을 만들면 마지막 성공 체크포인트라고 증명할 수 없다. 따라서 source-independent 시험은 **요청한 ref/sequence에 대응하는 기존 immutable 대상 봉인본**만 사용한다. 봉인본이 없는 기존 데이터는 명확한 오류로 차단하며 성공 체크포인트로 포장하지 않는다. 매 cycle immutable publication은 별도 발견 이슈로 추적한다.

### Cloud
1. StartDrTestFailoverCmd: `sourceindependent` Boolean을 명시적 요청에 기록. 기본 false로 정상 PAUSE barrier 유지. true일 때 NO_NIC/격리망 시험만 허용.
2. FtctlDrUnifiedActionAdapter: sourceIndependent TEST_PREPARE는 source hardware live 조회/검증과 source PAUSE RPC를 실행하지 않는다. 기존 Plan 저장 boot metadata와 target capability를 사용한다. target export ownership STOP/ACK는 반드시 성공해야 한다. request에 `checkpointExistingSealRequired=true`, durable ref/sequence, writer DRAINED 증거를 전달한다. qemu 신규 capability 없으면 차단한다.
3. target-only 실행의 worker/profile 구성에서 source worker 탐색을 피한다. cleanup은 이미 #964의 durable recovery queue를 사용하므로 artifact cleanup 성공과 source resume 대기를 계속 분리한다.
4. cleanup recovery는 최신 PAUSE/RELEASE/FAILOVER/target authority가 우선한다. 원본 연결 실패 중 반복 target export 교체를 피하도록 비동기 재개 워커에서 원본 상태를 먼저 확인한다. 원본에 연결되지 않으면 대상 export 생성 전에 재시도 대기로 남긴다. 연결이 살아 있는 구간의 재개 실패까지 포함하는 영속적인 준비 단계 재사용은 별도 보강이 필요하다.
5. UI: 테스트 모달에 원본 연결 없는 봉인 체크포인트 시험 옵션과 제한 안내. scheduler/RPO 이상 때문에 test 메뉴 자체를 숨기지 않고 유효 target checkpoint를 기준으로 이 모드를 제공한다. 정상 모드의 준비 조건은 API에서 유지한다.

### qemu
1. capability `dr-source-independent-test-v1`을 추가한다.
2. FILE: qcow2_checkpoint.py에 existing-only 경로 추가. plan/ref/sequence/device/path contract hash, checkpoint file/qemu check 및 OS probe를 검증하고 독립 overlay 생성. mutable backing과 비교하거나 새로 seal하지 않는다. 부분/누락/불일치 봉인본은 구조화 오류.
3. RBD: 정상 barrier에서 plan/ref/device로 식별되는 checkpoint snapshot을 생성하고 시험 clone과 별도 보존한다. source-independent 모드에서는 해당 snapshot 존재 확인 후 clone만 생성하며 live image에서 새 snapshot을 만들지 않는다. cleanup은 시험 clone만 지우고 봉인 checkpoint를 유지한다. RBD snapshot 유지/발행 정책은 후속 자동 publication 설계와 연계한다.
4. 정상 테스트·cleanup·failover/failback·release tombstone 회귀 게이트를 유지한다. Disaster Failover에 source RPC를 새로 추가하지 않는다.

### 검증
- 단위: source RPC 호출0, 정상 PAUSE 유지, target STOP 실패 차단, seal 없음/손상/다른 ref 차단, source cleanup 실패는 재개 대기로 분리, 최신 사용자 의도 우선.
- WSL changed Maven modules/UI 빌드, qemu Actions 패키지/필수 smoke.
- RBD 및 qcow2: 정상 UI 시험으로 유효 봉인본 준비, 원래 PAUSED 의도 유지 후 source 관리망 실제 차단. source-independent UI Test/QGA/cleanup 실행, 원본 연결 복구 후 PAUSED 유지 및 RUNNING recovery를 분리 확인.
- source 단절 disaster UI 전환은 명시적 격리 근거와 target-only 경로로 검증. 원본 VM이 통신 단절만으로 중지됐다고 주장하지 않는다. 테스트 VM 네트워크는 격리/NO_NIC 사용.
- 실행한 네트워크 차단/프로세스 중단과 실제 전원 장애, VMware/혼합 미실행 경로는 구분해 보고한다.