# #1518 RBD 복제 임시 스냅샷 복구 검증

검증일: 2026-10-29~30 KST. 대상: Epic #1510 / 필수 P1 CL-02.

## 결과

32번 legacy RBD Full의 임시 스냅샷 잔류를 재현하고 원인을 수정했다. 배포 후 같은 원본에서 Full → Full(2대, 자동 시작) → Linked(2대)가 수동 DB 수정이나 서비스 재시작 없이 성공했다. Linked 부모 보존, 부분 삭제, 한정 재시도, 실패 표시, 정상 UI 삭제 재시도 및 관리 서버 재시작 후 자동 복구도 검증했다.

22번의 부팅 이력 없는 정지 복제본에 explicit ROOT/explicit DATA/legacy DATA를 혼합한 Full 복제와 31번 GFS2 Full/부팅 회귀가 성공했다. 변경 모듈의 집중 검사 73개가 통과했다. QA 자원 정리 후 활성 VM·볼륨·VM 스냅샷·디스크 스냅샷·저장소 참조 잔여가 0건이며, 관련 물리 이미지/파일/실패 주입 참조 29개도 모두 없어졌다.

## 원인과 구현

기존 legacy RBD 경로는 RBD clone을 생성한 직후 성공을 반환했다. 관리 서버가 비동기 flatten보다 먼저 임시 스냅샷을 삭제하면서 EBUSY가 발생했다. 실패 처리에서 GROUP 디스크 스냅샷을 MANUAL로 변경하고 VM 스냅샷의 디스크 매핑까지 제거해, 의존성이 없어져도 Expunging이 남고 다음 복제가 차단됐다.

- Full VM clone만 Agent에서 동기 flatten을 완료한 뒤 성공을 반환한다. Linked 및 일반 스냅샷 복원 계약은 유지한다.
- 삭제 실패 시 VM 스냅샷을 Error로 전이하고 디스크 매핑을 보존한다. 이미 삭제된 디스크도 재대조하여 부분 성공 다음의 재시도를 허용한다.
- 자동 생성 소유권과 원본 Job을 기록하고 30초 주기로 정상 VM 작업 큐에 정리를 요청한다. 진행 중 원본 Job과 살아 있는 Linked 부모를 보호한다. 시도 횟수와 다음 실행 시각을 요청 전에 저장하며, 최소 60초 간격/최대 5회 이후에는 Error 및 `retry-exhausted`를 남긴다. 정상 삭제 UI/API로 재시도할 수 있다.
- 과거 매핑이 사라진 Expunging은 모든 RBD 디스크의 Full 독립화, 고유 이름 및 정확한 저장소 경로가 일치할 때만 채택한다. 사용자 스냅샷 및 Linked 부모는 추정해서 채택하지 않는다. 물리 의존성이 있으면 Agent 삭제가 계속 거절하므로 DB 상태만 강제로 삭제하지 않는다.
- 부팅 이력이 없어 host/lastHost가 비어 있는 정지 RBD 복제본은 모든 디스크가 Ready인 공유 RBD일 때만 저장소 endpoint에서 읽기 전용 백업 추적 검사를 수행한다. Running/비대상 저장소/Creating 디스크/endpoint 부재는 거절하고 기존 백업·스케줄·bitmap 검사를 유지한다.

설계 상세: [design.ko.md](design.ko.md). 정지 검사 명령의 설치 버전 차이도 확인하여 `CheckVmBackupTrackingCommand`와 관리 서버 `BackupSnapshotGuard` 및 내부 클래스를 함께 배포했다.

## 실제 UI와 저장소 검증

Job ID는 공개 API의 UUID다. 생성·복제·사용자 재시도·부분/전체 자식 삭제는 Mold UI로 실행했다. 준비/상태 대조/실패 주입/최종 정리에는 API·DB 읽기·호스트 도구를 함께 사용했다.

| 검증 | UI Job | 결과와 실제 근거 |
| --- | --- | --- |
| 32 변경 전 legacy Full 후 재복제 | `58c68752-474b-4fc9-ab4a-60921b6d0571` | 530 active Instance Snapshot 재현. 임시 VM 스냅샷 12 Expunging, 실제 RBD parent/children와 매핑 유실 확인 |
| 32 기존 잔류 자동 복구 | 기존 스냅샷 12 | 배포 후 worker가 매핑을 복구하고 정상 삭제. 정상 Full 복제본은 보존. 수동 DB 상태 보정 없이 removed 기록과 물리 정리 확인 |
| 32 Full 1대, 시작 끔 | `28447c5d-48bc-4a7f-b660-93f471f60454` | ROOT 100 GiB/DATA 1 GiB 독립화, 두 이미지 전체 논리 내용 `qemu-img compare`: identical. 임시 13 자동 정리 |
| 32 같은 원본 Full 2대, 시작 켬 | `d062feef-1586-4385-97ff-e3fa79c09265` | 두 VM Running, 네 디스크 parent 없음, 임시 14 자동 정리. 전용 게스트의 QGA ping/OS/디스크 경로 확인 |
| 32 같은 원본 Linked 2대, 시작 끔 | `91c77035-4263-4c56-9118-1ed044f54033` | 네 디스크가 자동 스냅샷 16의 올바른 원본 ROOT/DATA를 참조. ROOT 표본 해시와 DATA 패턴 일치 |
| 32 Linked 첫 자식 ROOT+DATA 삭제 | `ab1e3c22-b69b-4a53-b91d-8f9f86dcdbe6` | 각 디스크의 실제 자식 2→1. 살아 있는 자식의 부모 16과 사용자 스냅샷 15 Ready 보존 |
| 32 마지막 자식 삭제 후 DATA 정리 실패 | `248a96f3-3613-4cb1-b572-fa28b2907479` | 전용 외부 RBD 자식으로 DATA EBUSY 주입. ROOT는 물리 삭제/Destroyed, DATA는 보존, VM 스냅샷 Error와 디스크 매핑 2개 유지 |
| 32 실패 상태 정상 UI 삭제 재시도 | `fdff75e4-fca1-4b29-9cd9-a36c4ab2aa32` | 530 실패와 Job 표시. 정지 원본에서 Domain not found로 우회하지 않고 실제 저장소 의존성 실패 유지 |
| 32 5회 소진과 재시작 | 스냅샷 16 | `attempts=5`, `retry-exhausted`, Error 및 두 매핑이 관리 서버 재시작 후에도 유지. 무한 pending 및 횟수 초기화 없음 |
| 32 참조 해제 후 정상 UI 삭제 | `7bbb18c7-73e3-4ff8-910f-746b11632743` | 전용 blocker 제거 후 정상 삭제 성공, 임시 부모 16 물리 정리 |
| 32 일시 실패 후 재시작 자동 복구 | 생성 `efb127c9-4d4b-4c0b-bc0a-0a115517dd6a`, 자식 삭제 `0df4df35-fa92-42d3-8bee-aa5e44a51f10` | 임시 17 Error/attempts=1에서 Mold 중지, 전용 blocker 제거, 재기동. boot 시 attempts=1/두 매핑 보존 후 worker가 자동 삭제 완료. 수동 스냅샷 삭제 및 DB 보정 없음 |
| 22 explicit ROOT 최초 Full | `3d5727d5-f1c8-4630-bf50-c3f08ac6c97b` | 허용된 기존 base에서 전용 정지 복제본 생성. 보호 VM은 사용하지 않음 |
| 22 host/lastHost 없는 혼합 Full | `65e9f76f-0ef0-46b1-9477-277454a664e2` | ROOT/두 DATA 독립화, 임시 자원 자동 정리. explicit off/raw 계약과 legacy raw 출처 구분 유지. ROOT 0/1 GiB/8 GiB에서 4 MiB 표본 해시 일치, 두 DATA의 0x5a/0xa5 패턴 일치 |
| 31 GFS2 ROOT/DATA Full, 시작 켬 | `150c7d73-5afb-4f08-abc1-7093c400e738` | VM Running, QGA ping/OS 확인, 두 QCOW2 독립 이미지 및 DATA 패턴 일치, QMP block job 없음. 원본 경로 보존 |

사용자 스냅샷 `qa1518-32-user-preserve`(15)의 UI 생성은 성공했으며 DB의 CreateVMSnapshot Job `7c1fc166-d33b-4c69-b53c-32a610c40f26`과 대조했다. 생성 POST 자체는 브라우저 이벤트 버퍼에서 만료되어 원문이 없다. 모든 실패·재시작 시험 후에도 VM 스냅샷 Ready 및 ROOT/DATA의 실제 사용자 스냅샷을 확인했고, 최종 UI에서도 이 사용자 항목만 ‘사용 가능’으로 남았다. 검증이 끝난 후 전용 fixture 정리 과정에서 정상 삭제했다.

## 빌드·배포와 종료 상태

WSL ext4 전용 checkout에서 `core`, `server`, `engine/storage/snapshot`, `plugins/hypervisors/kvm`만 빌드했다. 최종 집중 검사는 13개 suite/73개, 실패·오류·skip 모두 0이다. 세부 목록은 [evidence-summary.json](evidence-summary.json)에 있다. 전체 Cloud/Release 빌드 및 GitHub Actions 전체 CI는 요청되지 않아 실행하지 않았다.

관리 서버 3대/Agent 9대의 기존 JAR에 변경 클래스와 필요한 내부 클래스만 패치했다. 원본 및 롤백 백업을 보존하고 관련 없는 JAR 엔트리의 보존을 확인했다. 최종 관리 서버 JAR은 cold guard 보완본, Agent는 호환 명령을 포함한 v2 검증본이다. 30개 활성 JAR의 SHA256이 배포 메타데이터와 모두 일치하며, 12개 서비스가 active다. 세 관리 서버의 `/client/`는 HTTP 200이고 `WEB-INF`가 존재한다. UI 정적 파일은 변경하지 않았다.

각 클러스터의 전용 VM과 볼륨/스냅샷/오퍼링을 정상 API로 정리했다. QA VM 이력은 22번 2개, 31번 2개, 32번 8개다. 활성 DB 자원/참조는 모두 0이며, 물리 부재 검사는 22번 7개, 31번 4개, 32번 18개(실패 주입용 RBD 자식 2개 포함) 모두 통과했다. 과거 soft-delete 이력은 보존한다. 제거된 VM 스냅샷 행의 상태 문자열이 Expunging이어도 removed 시각/물리 부재와 구분하여 판단했다.

22번 기존 `rocky98-base`의 Stopped 상태와 ROOT 경로/정책, 활성 스냅샷 0건을 대조했다. `rocky98-perftest`와 ROOT/DATA에는 이 작업에서 전원·디스크·QGA·마이그레이션·내용 명령을 보내지 않았다. 작업 중 22:43:55 정지/22:44:10 시작이 관측되어 배포 guard가 롤백했고, 사용자가 다른 작업의 재시작임을 확인했다. 해당 재시작 이후 최종 PID 1516803/startTicks 201077163과 Running/host/두 볼륨 경로·정책이 유지됐다. 전체 작업 동안 PID가 한 번도 바뀌지 않았다는 의미는 아니다.

## 검증 중 보정과 범위

- 첫 배포 후 설치된 core 명령 클래스의 `isStopped()` 불일치와 구형 관리 서버 guard의 오프라인 명령 계약을 확인하여 동일 소스의 호환 클래스를 배포했다. 이후 22번 host 이력 없는 복제본의 검사 실패를 재현하고 fallback을 구현했다. 마지막 배포 이후 해당 UI 경로가 성공했다.
- 초기 22번 fixture 준비 Job이 관리 서버 배포와 겹쳐 중단됐다. 제거된 전용 Creating 볼륨 17의 실제 이미지/관련 복사 프로세스 부재를 확인한 뒤 이 볼륨에만 남은 일시적 `SNAPSHOT_ID=4` detail 1건을 정리했다. VM/스냅샷 상태를 강제 변경하지 않았다. 이 수동 fixture 보정은 자동 복구 PASS에 포함하지 않으며, 생성 도중 중단의 모든 단계 복구를 검증했다는 의미도 아니다.
- 관리 서버 재시작 시험은 삭제 재시도 상태의 내구성을 대상으로 했다. 재시도 여유가 남은 경우의 자동 완료와 소진된 경우의 UI 수동 재시도를 각각 검증했다. 동시 다중 관리 서버 장애 전체 조합은 실행하지 않았으며 scan/VM lock 및 중복 정리는 집중 검사로 보완했다.
- Epic #1510의 나머지 필수 #1519, #1520 및 선택 #1521은 이 PR 구현 범위에 포함하지 않는다. 이 결과로 Epic 전체가 완료되는 것은 아니다. PR 생성/검증 후 사용자 요청에 따라 #1518을 종료하며 PR 병합은 별도 상태다.

원시 Job/DB/RBD/내용/배포/브라우저 증거는 로컬 `raw/`에 보관하며, 인증 정보가 섞일 수 있는 원문은 Git에 올리지 않는다. 공개 가능한 요약만 커밋한다.
