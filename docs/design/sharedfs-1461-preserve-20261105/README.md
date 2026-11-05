# #1461 SharedFS 삭제 시 DATA 보존·분리 정책 — 코드 수준 설계안

**2026-11-05 / 설계만 / 구현·제품 빌드·배포0 / OPEN·인수0/4.**

상위 Epic #1438, 기존 작업 브랜치 epic/1438-sharedfs. 설계 기준 HEAD **689ba57c24dac23acf74928156c7658281aef709**, 로컬/upstream Europa **413d1459682b6f4dea6c90773510b8342dba1ec2**, divergence0/0. Windows checkout은 수정하지 않았다.

현재 브라우저 도구가 Work에서 비활성화됐고 사용자가 **앱 재실행이 필요함**을 확인했다. 따라서 이번 기록은 코드·배포본 검토와 실제 SystemVM 사전 실증 및 빌드한 Vue3 목업 소스까지다. **오늘 실제 UI의 현재 삭제 대화상자·VM 표준 화면 검토와 라이트/다크 목업 이미지 캡처는 미완료다.** 앱 재실행 후 이 두 항목을 보완하기 전까지 설계 완료로 판정하지 않는다. 과거 UI 사진을 오늘의 검토 근거로 대체하지 않는다.

## 1. 범위와 목표

SharedFS 제거 시 **기본·추가 DATA 전체를 기본 보존**한다. 사용자가 영향 목록에서 명시적으로 선택하고 최종 확인한 DATA만 삭제한다. ROOT는 서비스 VM의 OS 디스크로 구분하며 VM 영구 제거 때 삭제된다.

NFS·SMB 파일 DATA, iSCSI·NVMe-oF 블록 DATA, 참조 없는 연결 DATA도 빠짐없이 포함한다. 이 기능은 **Cloud 볼륨을 보존하고 분리**하는 기능이다. 공유 설정 백업·복원/AD 재가입/프로토콜 재설계/공통 볼륨 삭제 UX 전체 변경을 추가하지 않는다. 보존된 파일·블록 데이터의 재연결/읽기를 구현 인수에서 검증한다.

## 2. 실제 소스·배포본에서 확인한 경로

### 2.1 현재 삭제 동작

| 소스 | 현재 확인한 동작 | 이슈 영향 |
|---|---|---|
| ui/src/config/section/storage.js:995–1024 | destroy는 expunge/forced만, expunge는 일반 자동 대화상자. groupMap에도 DATA 정책 없음 | 기본 보존·볼륨별 대상·최종 데이터 손실 확인 없음 |
| DestroySharedFSCmd / ExpungeSharedFSCmd | id, expunge, forced 및 SuccessResponse | 승인 계획/요청 멱등성/진행 결과 계약 필요 |
| SharedFSServiceImpl.destroySharedFS:1350 | Ready+forced에서 정상 stop 호출, Stopped/Error만 Destroyed 전환. expunge일 때 deleteSharedFS | 기존 파기→복구/영구제거 의미 유지 필요 |
| SharedFSServiceImpl.deleteSharedFS:1393 | Expunging 전환→provider.deleteSharedFS→호환 모델 삭제→성공·SharedFS remove. provider boolean 결과도 확인하지 않음 | 실패 때 성공·관계 제거를 막아야 함 |
| StorageVmSharedFSLifeCycle.deleteSharedFS:467 | imported DATA는 별도 guard, expungeVm 후 SharedFS.volumeId 기본 DATA를 destroyVolume | 기본 DATA 자동 삭제의 직접 경로 |
| UserVmManagerImpl.destroyVm(long,boolean):6910 | 공통 VM 파기. 외부 DestroyVMCmd 경로가 수행하는 DATA 사전 분리와 다름 | SharedFS에서 공통 VM UI의 DATA 선택이 자동 적용된다고 가정하면 안 됨 |
| VolumeOrchestrator.cleanupVolumes:1786 | ROOT는 destroy/expunge, 나머지 연결 DATA는 분리 | “모든 DATA가 현재 무조건 삭제된다”는 판단은 틀림. 추가 DATA는 분리되지만 기본 DATA를 SharedFS가 후속 삭제 |
| SharedFSGarbageCollector / cleanupSharedFS:1640 | Destroyed/Expunging/Error를 같은 deleteSharedFS로 정리 | UI만 변경하면 자동 정리가 보존 결정을 무시함 |
| deleteStorageServiceCompatibility:1494 | NFS 및 directory policy 중심 soft-remove, RuntimeException은 경고 후 계속 | 멀티 프로토콜 관계 보존·최종 완료 판정 보완 필요 |
| volume_detach_prepare:3199 | 전달 mount 아래 findmnt 대상 unmount 후 fstab marker 제거 | 외부 bind 별칭과 이미 unmount된 경로의 안전성 검사가 별도 필요 |

공통 볼륨 detach API는 일반 사용자의 SharedFS VM detach를 이미 거부하고, SharedFS 내부는 detachVolumeViaDestroyVM을 사용한다. 기존 초기 import 취소도 **detach 재조회 확인→DATA 없는 VM만 cleanupImportedSharedFSVm** 순서를 사용한다. 이 ROOT-only 정리 경계를 재사용한다.

### 2.2 배포본 무결성

13번 관리 서비스 active. JAR **49fa4bcefe132a4356944a2a9699154f195ee8d934857b4e78432e68aeb6c95c**, UI index **330784f765854c1e7ddc8be0ab5e8b65c3044e388f72f2f5015ffb2d4014b6b2**로 #1460 완료 배포본과 일치한다.

SharedFSServiceImpl, StorageVmSharedFSLifeCycle, VolumeOrchestrator, Destroy/ExpungeSharedFSCmd **5개 class byte가 로컬 target과 일치**한다. UserVmManagerImpl 전체 class는 무관 변경 때문에 다르므로 전체 동일을 주장하지 않는다. 배포 class를 읽어 javap로 **expunge, destroyVm 두 overload, detachVolumesFromVm, destroyVolumeInContext 5개 관련 메서드**를 상수풀 번호 제거 후 비교했고 모두 동일하다. 공통 VM class를 이번 기능 때문에 교체하지 않는다.

실제 SystemVM i-2-214-VM / VM **23ccd807-af30-360b-a8ef-c2c29b9bb833**, runtime **1460.client.4**, 활성 /opt/ablestack/storage-runtime/releases/1460.client.4를 확인했다. 배포 CLI SHA **c09a749ef83294cdb0dd544d7c61a5502873ce4a5cf612c927b6b6313330a3ef**는 소스와 일치한다.

- [소스 class 지문](evidence/source-class-audit.json)
- [배포 관련 class 지문](evidence/deployed-class-audit.json)
- [차이가 있는 공통 class의 관련 메서드 비교](evidence/user-vm-method-audit.json)

DB 직접 읽기는 기본 root 인증이 거부돼 수행하지 않았다. DB 실제 상태/신규 migration 적용을 확인했다고 주장하지 않는다. 스키마 설계는 현재 저장소 VO·DAO와 schema migration 경로를 기준으로 하며 실제 DAO 저장/재조회는 구현 후 필수 시험이다.

## 3. 실제 SystemVM 설계 사전 실증

[실행 스크립트](probes/native-proof.py) · [원문19검사](evidence/native-proof-result.json)

운영 SystemVM 안에서 **private mount namespace + 새 SPARSE 파일 기반 loop**만 사용했다. XFS512MiB/EXT4512MiB/raw64MiB이며 생성 시 allocated0이었다. Cloud 볼륨·VM 생성/삭제나 운영 프로토콜 설정을 숨은 API로 준비하지 않았다. 실제 business API 인수를 대신하는 시험이 아니다.

- 실제 배포 CLI의 volume_detach_prepare Python을 추출했다. harness에서 **fstab 경로만 시험 파일로**, daemon-reload만 no-op으로 바꿨다. findmnt/umount는 실제 커널·기존 helper 코드 그대로다. 제품 파일은 수정하지 않았다.
- XFS·EXT4 각각 UUID/FSROOT+device/inode로 외부 NFS 부모·SMB 자식 별칭이 같은 물리 경로인지 확인했다.
- 현재 helper로 기본 mount만 제거하면 **namespace 외부 위치의 bind 별칭 두 개가 남는 것**을 확인했다. 따라서 이 helper의 success만으로 전체 DATA 분리가 안전하다고 판정하면 안 된다.
- 실제 busy alias의 일반 umount가 실패했다. 이 실패 단계 뒤 detach/VM-expunge/volume-delete 호출은0으로 유지했다. 강제/lazy unmount를 쓰지 않았다.
- 검증된 외부 별칭을 먼저 해제하고 기존 helper로 기본 mount를 해제하면 남은 mount0, 시험 fstab의 무관 행은 동일했다.
- 이미 분리된 관측에서는 기존 helper를 다시 호출하지 않는 재개 경계를 확인했다.
- loop detach/reattach와 remount 후 **파일 SHA·FSUUID·inode·UID1234/GID2345·02770·명시/기본 ACL**이 XFS/EXT4 각각 동일했다. raw도 filesystem 생성 없이 전체 해시가 동일했다.
- loop 전부 해제, 이번 시험의 sparse 파일3개만 경로 확인 후 정리했다. 운영 fstab·CLI·Ganesha config/PID·seed 등 보호 파일6개 byte 동일, 운영 서비스 재시작0.

**설계 반영:** 서비스 VM 자체를 제거하는 기능이므로 새 live unpublish/mount 엔진을 만들지 않는다. 기존 정상 VM shutdown으로 서비스와 mount를 종료하고, 실제 PowerOff를 확인한 뒤 DATA를 분리한다. 이 경로에서는 위의 live volume_detach_prepare를 부르지 않는다. 이미 중지된 VM을 QGA/unmount를 위해 다시 부팅하지 않는다. 향후 온라인 detach 개선을 이 이슈에 추가하지 않는다.

이 실증은 kernel 분리/보존·설계 경계에 대한 것이다. 네 프로토콜 실제 서비스의 Cloud VM 삭제·재연결, 선택 DATA 삭제, UI·API 전체 인수는 **구현 후 미수행 과제**다.

## 4. 확정할 최소 변경 설계

### 4.1 정책·영향 목록·승인 계약

기존 destroySharedFileSystem/expungeSharedFileSystem을 유지한다. API가 정책을 생략해도 **PRESERVE**다. 예전 호출자·GC의 누락이 과거 자동 DATA 삭제로 돌아가면 안 된다.

- volumepolicy: PRESERVE(기본) / DELETE_SELECTED.
- deletevolumeids: DELETE_SELECTED에서만 허용하는 UUID 목록. 빈 목록은 거부하고, ROOT·비대상·무권한·삭제 보호·외부 참조/공유 DATA는 선택 불가.
- requestid: 계정+SharedFS+정규화 요청의 멱등 키. 같은 키/같은 요청은 같은 계획·상태, 같은 키/다른 정책은 충돌.
- planfingerprint: read-only getSharedFileSystemDeletionImpact가 반환한 구조 지문. 선택·경고 최종 확인 전후 목록 변화 시 재조회·재확인.
- confirmresource: 정확한 SharedFS UUID/이름 검증, confirmdataloss는 선택 삭제에서만 필수. 서버도 검사하며 UI 체크박스만 신뢰하지 않는다.
- expunge/forced: 기존 의미 유지. forced를 신원·보호·정지 확인 우회로 사용하지 않으며 정상 shutdown 실패 시 자동 강제 정지로 넘어가지 않는다.
- 기존 SuccessResponse는 유지하고 additive deletionoperationid만 제공한다. 진행/상세는 getSharedFileSystemDeletionOperation으로 같은 계획을 조회한다. 완료 전 success:true로 표시하지 않는다.
- 새 resumeSharedFileSystemDeletion은 operationid+requestid로 **동일 계획만** 재개한다. 기존 구성을 적용하는 resume API로 섞지 않는다.

영향 목록은 다음 합집합을 **볼륨 UUID로 중복 제거**한다.

1. 해당 VM에 실제 DB 연결된 ROOT/DATA 전체(숨김·미참조 DATA 포함).
2. SharedFS 기본 volume_id.
3. instance의 NFS/SMB file-share volume_id와 iSCSI/NVMe-oF block-target volume_id.
4. 승인 계획에 이미 포함돼 분리 완료된 DATA — 재개 중 누락 금지.

각 행은 UUID/이름/type/크기/상태/계정·Zone/연결 VM/출처(기존 연결·이 서비스 생성)/프로토콜 참조/다른 instance 참조/삭제 보호/계획(PRESERVE_DETACH 또는 DELETE_SELECTED)/관측 시각을 구분한다. raw에 FSUUID·파일 경로를 강요하지 않는다. 다른 VM으로 옮겨진 참조는 삭제·분리 대상이 아니라 **CONFLICT**다. 현재 인스턴스 안 여러 공유의 참조는 하나의 DATA행에 표시한다.

Read-only 점검이 하지 못한 관측은 UNKNOWN으로 표시한다. 그 결과를 전체 빈 목록·새 삭제 승인으로 바꾸지 않는다. 현재 부팅 장치명은 관측 값이며 승인 식별자는 Cloud 볼륨 UUID/소유/크기/스토리지 신원이다. UUID가 같다는 이유만으로 외부 VM의 볼륨에 작업하지 않는다.

### 4.2 영구 계획과 상호 배제

삭제는 설정 원복 transaction과 달리 비가역 단계가 있어, 기존 configuration_operation의 before/candidate를 삭제 journal로 재해석하지 않는다. 생성 이력 creation_context도 덮어쓰지 않는다.

**예정 DB 변경(설계만, 실제 migration 미적용):**

- 새 cloud.sharedfs_deletion_operation 테이블1개:
  id BIGINT PK, uuid CHAR(36) UNIQUE, sharedfs_id BIGINT, sharedfs_uuid CHAR(36),
  instance_uuid CHAR(36), vm_uuid CHAR(36), account_id/domain_id BIGINT,
  request_id VARCHAR(64), request_hash/plan_fingerprint CHAR(64),
  state/phase VARCHAR(32), plan_json/result_json MEDIUMTEXT,
  original_failure_json TEXT, job_id BIGINT NULL, created/updated/completed DATETIME.
  UNIQUE(account_id,sharedfs_id,request_id), INDEX(sharedfs_id,state).
  SharedFS/VM/StorageService 삭제와 cascade되지 않도록 식별자·소유권 snapshot을 보존한다. 영구 제거 후에도 계정/domain 권한을 검사해 조회한다.
- cloud.storage_service_instance.active_lifecycle_operation_uuid CHAR(36) NULL 한 컬럼. 생성/조회/다른 instance에는 기본NULL로 영향0.
- 현재 Europa는 DatabaseUpgradeChecker:573–574에서 runEuropaPhase로 기존 초기 생성/구성 migration을 실행한다. 같은 경계에 신규 EuropaSharedFSDeletionUpgrade.migrate와 독립 KEY를 추가한다. engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql 및 해당 신규 설치/upgrade SQL에 동일 additive DDL을 정렬한다. 운영 DB 적용은 구현 승인 이후에만 수행한다. 503/부분 migration 방지를 위해 정확한 테이블·컬럼·index 사전 체크와 DDL별 재개를 배포 계획에 포함한다. 기존 shared_filesystem.creation_context는 변경하지 않는다.

새 SharedFSDeletionCoordinator는 **기존 instances.lockRow와 같은 예약 트랜잭션** 안에서 activeConfigurationOperationId, runtime writer/포맷/AD 미확정 작업, lifecycle UUID를 검사하고 계획·예약을 저장한다. 설정 coordinator 및 해당 instance의 attach/detach/resize/런타임 시작/SharedFS start·restart·recover는 같은 lifecycle guard를 확인한다. 조회는 허용한다. 일반 VM/다른 SharedFS의 경로를 전역 차단하지 않는다.

전역 공통 볼륨 API 재작성은 하지 않는다. 일반 API의 SharedFS attach/detach 차단을 유지하며, 승인 후 외부 resize/보호 변경 등 구조 변화는 각 단계에서 재검증해 중단한다. volumeApiService 자체의 기존 권한·deleteProtection/참조·상태 보호를 우회하지 않는다.

### 4.3 파기/복구와 영구 제거 순서

**복구 가능한 파기(expunge=false):**

- 계획을 저장한 뒤 기존 정상 stop과 Destroyed 전환을 사용한다.
- DATA/ROOT는 연결 상태를 유지하며 파일·블록·서비스 관계를 지우지 않는다. “보존 후 분리”는 향후 영구 제거 단계라는 점을 UI에 명시한다.
- 기존 recoverSharedFS는 detach 이전의 SOFT_DESTROYED 계획을 취소하고 Stopped로 복구한다. 부분 분리 이후에는 일반 복구로 모호하게 재시작하지 않고 같은 제거 계획 재개만 제공한다.
- GC는 저장한 정책만 사용한다. 정책 없는 기존 Destroyed/Error 자원은 기본PRESERVE 계획을 먼저 기록하며 선택 삭제 목록을 추측하지 않는다. 관측·부분 실패가 있는 계획은 GC가 무한 재시도하지 않고 RECOVERY_REQUIRED로 남긴다.

**즉시/지연 영구 제거:**

~~~text
PLAN_VALIDATED
  → 정상 VM shutdown 및 기존 stop 완료
  → VM_STOP_CONFIRMED (DB Stopped + host의 CheckVirtualMachineAnswer.PowerOff)
  → DETACHING_DATA (기본/추가 DATA 전부, UUID당1회)
  → DATA_DETACHED_VERIFIED (재조회로 instance_id=null, 비대상으로 이동하지 않음)
  → VM_ROOT_REMOVING (ROOT 외 연결 볼륨0)
  → VM_ROOT_REMOVED_VERIFIED
  → DELETING_SELECTED_DATA (오직 승인 목록, 미선택 DATA delete 호출0)
  → CLEANUP_METADATA
  → COMPLETED
~~~

- host 확인은 기존 CheckVirtualMachineCommand/Answer를 사용한다. PowerUnknown·답변 없음·다른 host/VM 세대는 STOP_UNCONFIRMED다. 관리 DB Stopped 한 값이나 QGA 단절만으로 정지·unmount 완료를 추정하지 않는다.
- 정상 shutdown은 기존 OS의 서비스 종료/flush/unmount를 사용한다. 실제 PowerOff 뒤에는 게스트 mount/프로토콜 실행 주체가 없으므로 offline detach를 진행한다. 별도 live unmount 명령·강제/lazy unmount·mount 경로 삭제·데이터 파일 삭제/format/chown은 없다.
- volumeApiService.detachVolumeViaDestroyVM의 return만 믿지 않고 VolumeDao를 다시 읽는다. 예상 VM 연결 해제와 Ready/비파괴 상태가 확인돼야 다음 DATA로 진행한다. null/예외/응답 유실이면 다음 삭제 단계0.
- ROOT-only provider 경계는 cleanupImportedSharedFSVm의 기존 DATA없음 guard를 공통 private helper로 재사용한다. 새 cleanupDetachedSharedFSVm(fs,expectedVmUuid)에서 VM 신원+ROOT 외0을 다시 검증한 뒤 기존 expungeVm을 사용한다. 기존 초기 import 취소의 guard/동작은 유지한다.
- lifecycle.deleteSharedFS의 무조건 기본 DATA destroy 경로는 승인된 제거 executor에서 호출하지 않는다. 기존 초기 생성 실패 정리는 별도 명시적 owned-volume context로 유지하며 사용자 서비스 삭제와 혼합하지 않는다.
- 삭제 선택 DATA는 모두 detach와 VM/ROOT 제거가 검증된 뒤 일반 destroyVolume 보호 경로로 처리한다. 공유·외부 참조·삭제 보호가 새로 생기면 즉시 중단한다. 보호를 꺼주지 않는다.
- 메타데이터 cleanup은 완료한 instance의 NFS/SMB/BlockTarget/ACL·protocol·directory/identity 참조를 묶어 처리한다. 다른 instance/volume 행과 과거 작업 진단은 제거하지 않는다. 현재 NFS-only·예외 삼키기 경로를 이 executor에서 재사용하지 않는다.
- VM/볼륨 실제 삭제와 DB cleanup은 단일 SQL rollback으로 되돌릴 수 없다. 실제 관측과 단계별 journal로 확정하며, 관계를 먼저 지워 이후 재관측 대상을 잃지 않는다. 완전히 완료한 뒤 SharedFS/instance soft-remove, 이력은 독립 보존한다.

### 4.4 실패·재개·결과

| 실패 | 처리 |
|---|---|
| 활성 formatter/구성/업그레이드/AD 작업 | 실행 전 BLOCKED, 변경0 |
| stop 실패/host 응답 유실 | STOP_UNCONFIRMED, detach/VM/DATA 삭제0 |
| DATA 분리 중 일부 성공·다음 실패 | 각 볼륨 결과 보존, VM/ROOT·선택 DATA 삭제0. 같은 계획으로 미확정 행부터 재관측 |
| VM expunge 응답 유실 | ROOT/VM 실제 상태 재조회. 새 VM을 만들거나 소실을 성공으로 추측하지 않음 |
| 선택 DATA 삭제 일부 성공 | 원인·이미 삭제된 UUID 보존. 남은 승인 목록만 재개, 미선택 DATA는 계속 보존 |
| 구성/소유/참조 지문 변화 | PLAN_STALE/RESOURCE_CONFLICT. 자동으로 대상 목록 확대 금지 |
| metadata cleanup 실패 | 실자원 결과 보존, METADATA_PENDING. DATA 삭제를 반복하지 않고 metadata 단계만 재개 |
| 중복 submit/JVM restart | 같은 request/hash/plan을 조회, 동일 볼륨 detach/delete 중복 호출 방지 |

최초 실패는 write-once. 현재 단계·대상 볼륨·보존/분리/삭제/미확정 상태를 UI/API에 그대로 전달한다. 부분 실패를 롤백 성공으로 꾸미거나 분리된 DATA를 자동 재연결해 서비스 재기동하지 않는다. 시작한 요청의 화면 닫기는 취소가 아니다.

## 5. 예정 파일과 영향도

| 경계 | 예정 변경 |
|---|---|
| SharedFSServiceImpl / SharedFSService | destroy/expunge/GC/recover를 삭제 계획 coordinator로 연결. 기본PRESERVE. 기존 생성/import/재시작 동작 유지 |
| SharedFSDeletionPlan / SharedFSDeletionCoordinator (신규) | union inventory·승인 지문·단계 journal·detach/ROOT/선택 DATA 순서·재개 |
| SharedFSDeletionOperationVO/DAO + migration | 전용 이력1table와 lifecycle 예약1column |
| StorageVmSharedFSLifeCycle / SharedFSLifeCycle | UUID 확인·ROOT-only 제거 경계. 공통 UserVmManagerImpl/VolumeOrchestrator 변경0 |
| StorageServiceConfigurationCoordinator/Manager/Runtime 시작 | 같은 instance lifecycle 예약 충돌만 검사. 일반 요청/다른 instance 로직 유지 |
| Destroy/ExpungeSharedFSCmd + 영향/진행/재개 Cmd·Response | additive 정책/요청/승인 계획. 기존id/forced/expunge/SuccessResponse 유지 |
| ui config storage.js / SharedFSDeletion.vue / locale | 일반 자동 삭제 폼을 전용 계획 폼으로 교체, 영향/실패·동일 작업 추적 |
| SharedFSTab 작업 내역 | 제거 작업 진단을 기존 표준 작업내역 스타일로 표시. 정보 상세 탭에 작업 섹션 추가0 |
| SystemVM / host agent | **제품 변경 예정0**. 현재 정상 shutdown/기존 host power 관측/볼륨 API 재사용. 이번19검사는 설계 사전 주입에 한정 |

provider 지원·API권한은 새 제거 capability를 실제 endpoint별로 노출한다. 지원하지 않는 provider는 DATA 삭제를 과거 동작으로 fallback하지 않고 명시적으로 거부한다.

## 6. UI 목업과 VM 표준

[실행 가능한 Vue3/AntD/Mold 목업](mockup/README.md), [App.vue](mockup/App.vue).

**A 현재 UI:** 소스에서 현재 destroy/expunge의 자동 대화상자 계약을 확인했다. 오늘 실제 브라우저 캡처는 차단돼 미완료. 과거 사진이나 B를 A라고 표시하지 않는다.

**S VM 표준:** 실제 코드의 AutogenView, Breadcrumb, ResourceLayout, DestroyVM, MoldDialog를 기준으로 대조했다. 오늘 실제 컴퓨트 > 가상머신 화면 검토는 앱 재실행 뒤 필요하다.

**B 개선 목업:** 실제 ui/node_modules의 Vue3·Ant Design Vue와 repo의 Breadcrumb/ResourceLayout/MoldDialog/style/index.less를 import한다. API 호출·실제 삭제 코드는 없다. 예시 데이터는 실제 자원 상태와 명확히 구분한다. 로컬 목업 webpack build만 PASS했고 제품 UI를 빌드/배포한 것은 아니다. **이미지 첨부·시각 검수는 아직 미완료.**

- Breadcrumb/목록/InfoCard+상세 프레임/세로 탭/탭 콘텐츠는 VM 표준을 유지한다. 상세는 정보 전용, 작업은 작업 메뉴·대화상자·작업 내역으로 분리한다.
- 기본은 모든DATA보존. ROOT는 별도 고정 행. 삭제 선택은 row별 target UUID에 연결하고 공유·삭제 보호 볼륨의 선택은 비활성화한다.
- 기존 expunge의 즉시/지연 의미를 보존하고, 지연 모드에서는 지금 분리하지 않는다는 안내를 명시한다.
- 주요 버튼은 좌측 작업군의 파란 primary, 업데이트는 Reload 아이콘+업데이트다. 불필요한 탭 중복 제목과 전체 UI 정리를 추가하지 않는다.
- 제거 대화상자 MoldDialog size=xl. 제목 헤더와 취소/확인 푸터는 고정, 영향 목록·폼만 본문 스크롤. 파란 최종 확인 버튼과 별도의 데이터 손실 경고·정확한 이름 확인을 사용한다.
- 기존 AntD/UI CSS token만 사용하고 버튼8px/섹션16px 간격을 유지한다. 상태는 글자로 구분하고 색만으로 판정하지 않는다.
- 앱 재실행 후 라이트/다크의 본문·경고·비활성 선택·긴 UUID·좁은 창과 fixed header/footer 위치를 실제 렌더로 검수한다. 미완료 검수를 통과했다고 쓰지 않는다.
- 목업용 별도 PR은 생성하지 않는다. 설계 문서/목업만 기존 Epic 브랜치에서 공유하고 이미지 확보 후 이슈에 연결한다. 최종 UI 정리 #1468은 HOLD다.

## 7. 구현 승인 후 검증 계획 — 아직 수행하지 않음

1. **DAO/실제 직렬화:** plan JSON MEDIUMTEXT 저장→재조회·상태전이·same request 충돌·선택볼륨변조·권한·삭제 후조회·migration/idempotency. 실제 직렬화 응답을 컴포넌트 fixture로 사용.
2. **오케스트레이터/실제 오류 경로:** 모든DATA union/숨김/미참조/중복참조·NULL기본정책·소유/공유/삭제보호·stop미확정·detach null/응답유실·providerfalse·두번째볼륨실패·VM실패·선택삭제부분실패·metadata실패를 시험. 후속 파괴 호출0, 보존목록delete호출0 확인.
3. **동시/재시작:** configure/format/runtime writer와 양방향 경쟁, 중복 API1회실행, GC 동일정책, legacy계획없는기본보존, 관리JVM단절 후 같은계획재개.
4. **기존 기능:** 초기 신규/기존 XFS·EXT4 import취소 보호·생성 실패ownedcleanup·파기후recover·NFS/SMB/블록 기존 작업의 불변 회귀.
5. **로컬 모듈:** 변경 API/schema/server/SharedFS provider/UI만 빌드, 필요 클래스와 migration 적용. SystemVM/host-agent 빌드는 현재 설계상 불필요. Actions 대신 로컬을 기본 사용.
6. **실제 UI 자원:** 새 VM·ROOT/기본XFS DATA·추가EXT4/RAW DATA, 네 프로토콜과 실제 새클라이언트2대를 UI로 구성. 전부SPARSE이상·MAC자동.
7. **보존:** UI 기본보존→파기/복구→영구제거. 실제 볼륨 Ready/미연결·UUID/용량/스토리지 유지. 새 실제 클라이언트에 UI 재연결 후 NFS·SMB 파일hash/uid/gid/mode/ACL와 iSCSI·NVMe rawmarker/파일시스템을 실제 콘솔로 읽는다. 기존 공유 설정의 자동복구를 이 인수와 혼합하지 않는다.
8. **선택삭제:** 별도 새 전용 서비스, 보존DATA와 선택DATA를 혼합해 UI영향목록·경고·최종확인. 승인UUID만삭제·비대상보존. 부분실패 후 VM/ROOT 보호·관리JVM재시작·동일계획재개·원인 이력 확인.
9. **실제 UI/다크:** 현재 A/VM S/개선 B 대조, 라이트·다크/긴목록·본문만스크롤·중복확인·서버원인 전달. 제품 코드 오류는 원인 분석 후 중단, 시험 실수는 자체교정.
10. 모두 통과한 후에만 #1461 완료 기록→Epic PR 코드반영→이슈종료. 이번은 설계안 기록이며 이슈를 닫지 않는다.

## 8. 재개할 정확한 위치

1. 앱 재실행 후 브라우저 제어 활성 여부 확인.
2. 실제 13번 SharedFS 삭제 대화상자(최종 제출하지 않음)와 VM 표준 화면을 열어 A/S 기록.
3. 빌드된 목업을 local server에서 열어 B 보존/선택삭제/부분실패·목록/상세/작업내역을 라이트/다크로 캡처·본문스크롤 검수.
4. 설계와 목업을 이슈에 보완한 뒤 **설계 완료** 보고. 그 전/후 모두 제품 구현은 별도 지시 전 시작하지 않는다.
