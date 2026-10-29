# #1518 RBD 복제 임시 스냅샷 수명주기

## 재현과 원인

32번의 새 전용 VM `qa1518-32-legacy-src`(262)에 legacy ROOT/DATA를 준비했다. UI에서 Full, 1대, 시작 끔으로 복제한 Job은 성공했으나 임시 VM snapshot 12가 Expunging에 남았다. 후속 UI 복제 Job `58c68752-474b-4fc9-ab4a-60921b6d0571`은 530 / `There are other active Instance Snapshot tasks on the Instance`로 실패했다.

복제 직후 실제 RBD ROOT 이미지의 parent와 `rbd children`에서 원본 임시 snapshot 의존성을 확인했다. 기존 legacy 경로는 Agent가 RBD clone만 만든 뒤 성공을 반환하고, 관리 서버가 백그라운드 flatten보다 먼저 VM snapshot을 삭제한다. 실패 처리에서 모든 GROUP 디스크 snapshot을 MANUAL로 바꾸고 VM snapshot 매핑을 제거해, 이후 삭제 전략 선택도 바뀐다. 뒤늦게 물리 의존성이 사라져도 VM snapshot은 복구되지 않는다.

## 변경

- Agent는 Full VM clone에 한해 동기 flatten을 마친 뒤 복사 성공을 반환한다. 시작 끔에도 적용하며 Linked 및 일반 snapshot 복원 동작은 유지한다.
- 디스크 삭제 실패 시 VM snapshot을 Error로 전이하고, 모든 디스크의 원래 매핑을 유지한다. 일부 디스크가 이미 삭제돼도 같은 storage 전략으로 재대조한다. 정지 VM의 domain 조회는 필요하지 않다.
- 자동 생성 VM snapshot만 소유권 marker와 원본 Job id를 기록한다. 30초 주기 worker는 원본 Job이 끝난 뒤 VM 작업 큐를 통해 삭제를 재시도한다. 진행 중 Job, 생성/복원 중 snapshot 및 살아 있는 Linked 볼륨은 보존한다.
- 실패 횟수와 다음 실행 시각은 DB에 먼저 기록한다. 60초 간격, 최대 5회이며 재시작해도 횟수가 초기화되지 않는다. 소진하면 `retry-exhausted`와 snapshot 오류를 남겨 정상 삭제 UI/API 재시도가 가능하다. 성공한 clone을 삭제하지 않는다.
- 과거 Expunging 잔류는 계정의 VM Job이 모두 끝나고, ROOT를 포함한 모든 RBD 디스크가 `full-flattened`이며, snapshot 이름의 VM snapshot id/볼륨 UUID와 실제 저장소 경로가 정확히 일치하는 경우에만 매핑을 복원한다. 사용자 snapshot이나 Linked 부모를 추정해 채택하지 않는다.
- DB 매핑은 사전 선별 조건이다. 실제 RBD 자식이 남으면 Agent의 unprotect/delete가 실패하므로 상태만 강제로 삭제하지 않는다. worker는 관리 서버 사이의 scan lock 및 VM별 cleanup lock을 사용한다.

## 검증 계획과 한계

변경 Maven 모듈은 `server`, `engine/storage/snapshot`, `plugins/hypervisors/kvm`이며 WSL ext4에서만 빌드한다. 전체 Cloud/Release 빌드는 실행하지 않는다. 집중 검사는 Full/Linked 분기, flatten 실패 전파, 부분 삭제 재시도, Linked 마지막 자식, 원본 Job 보호, 재시작 횟수 보존, backoff/소진, legacy 채택 조건을 검증한다.

실환경은 32번의 기존 잔류 자동 복구와 Full→Full→Linked UI 연속 작업, 정지 원본, Linked 복수 자식의 일부/전체 삭제, 물리 의존성으로 인한 일시 실패/재시도 및 관리 서버 재시작을 우선한다. 22번은 전용 복제본의 explicit ROOT와 legacy/explicit DATA, 31번은 GFS2 ROOT/DATA Full 경로를 회귀한다. Job/DB/실제 이미지 및 내용 증거를 수집한 뒤 전용 자원을 정리한다.

22번 `rocky98-perftest`와 연결 ROOT/DATA에는 전원, 디스크, QGA, 마이그레이션, 내용 변경을 하지 않는다. 보호 VM은 DB 메타데이터 및 QEMU PID/시작 시각만 대조한다. UI 정적 파일을 변경하지 않으며 관리 서버 fat JAR의 변경 클래스와 Agent KVM 클래스만 기존 파일을 보존하여 패치한다.


## 설치 클래스 호환성 보완

첫 배포 후 정지 원본 preflight에서 `CheckVmBackupTrackingCommand.isStopped()`의 `NoSuchMethodError`가 드러났다. 설치된 KVM wrapper는 새 계약을 사용하지만 Agent `cloud-core`의 명령 클래스가 구버전이었다. `core` 모듈의 명령 클래스도 동일 소스로 빌드·배포하고, 정지 플래그/오프라인 디스크 목록의 Gson 왕복 및 구형 요청의 기본 거절 계약을 검사한다. 따라서 배포 대상 클래스는 `UserVmManagerImpl`, `StorageVMSnapshotStrategy`, `KVMStorageProcessor`와 호환 의존성 `CheckVmBackupTrackingCommand`다. 관련 내부 클래스도 함께 패치한다.

22번 배포 중 보호 VM PID 변경이 감지돼 자동 롤백했다. 사용자가 별도 작업에서 정지·시작했다고 확인했다. 본 작업은 보호 VM의 전원·디스크 명령을 실행하지 않았으며, 동시 작업의 PID 변화와 배포 자체의 보존 검사는 구분해 기록한다.


## 한 번도 부팅하지 않은 정지 RBD 복제본

22번에서 최초 Full(시작 끔)로 만든 전용 VM에 explicit/legacy DATA를 연결한 뒤 다시 Full 복제를 제출하자, VM host/lastHost가 모두 없어 `BACKUP_TRACKING_UNKNOWN: No host available for tracking inspection`으로 거절됐다. `BackupSnapshotGuard`가 정지 VM의 준비된 공유 RBD 디스크에 한해 저장소 endpoint를 선택해 같은 읽기 전용 검사를 수행하도록 보완한다. 실행 중 VM, 준비되지 않은 디스크, RBD가 아닌 저장소 및 사용 가능한 endpoint가 없는 경우는 계속 거절한다. 백업/스케줄/dirty-bitmap 검사도 유지한다.

Agent 명령/처리부 계약 2개 및 기존 guard/Agent 검사 23개를 포함한 검사에, 호스트 이력 없는 RBD 성공·비대상 저장소/Creating 볼륨 거절·endpoint 부재·Running host 부재 거절을 추가했다. 관리 서버의 `BackupSnapshotGuard`와 내부 Lease 클래스를 함께 배포하며 Agent 재기동은 필요하지 않다.
