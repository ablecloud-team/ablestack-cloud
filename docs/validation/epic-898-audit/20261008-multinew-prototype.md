# #909 multiNEW 독립 helper prototype — 2026-10-08

부모가 구현을 위임한 범위는 새 `StorageConfigurationVolumePlan`/`StorageConfigurationVolumeAllocation`와 해당 새 unit뿐이다. ROOT Java compile freeze 동안 repository Java는 편집하지 않았고 scratch에 네 파일을 만들었다. Scratch javac와 JUnit 30개는 통과했지만 실제 Manager/Configuration/Volume API 연결이나 Cloud multiNEW 인수 완료로 취급하지 않는다.

## 계획 helper 입력과 출력

`StorageConfigurationVolumePlan.build(archive, mapping, blueprint, targetScope, catalog)`는 side effect 없는 순수 함수다. archive의 `desired/volumes.json` DATA inventory와 file-shares/posix-directory-policies/block-targets의 실제 consumer를 읽는다. initial NEW/EXISTING blueprint와 primitive initial mapping의 일치도 검증한다. primitive `mapping.volumes[sourceUuid]=NEW|existingUuid` 및 `mapping.newVolumes[sourceUuid]` 상세 spec을 유지하고, 정렬된 allocations와 DomainRestore가 사용하는 primitive volumeMappings를 출력한다.

| 입력 | 계약 |
| --- | --- |
| targetScope | artifactUuid/artifactSha256/allocationNamespace/targetInstanceUuid/accountId/domainId/projectId(nullable)/zoneUuid/baselineRevision. 서버가 target tenant를 resolve하고 첫 plan의 namespace/target UUID를 저장해 재검토·재시작에서도 재사용한다. |
| blueprint | legacy initial size/diskofferingid/storageid/filesystem. 추가 NEW의 미지정 offering/pool은 이 값에서 derive하고 size는 source ceilGiB 이상이다. |
| newVolumes spec | diskofferingid/storageid/sizeGiB/usage/filesystem/dataPolicy만 허용한다. usage/filesystem은 archive consumer와 대조하며 dataPolicy는 PRESERVE만 허용한다. |
| catalog.offerings[UUID] | uuid/active/accessible/customized/storageType(shared)/minSizeGiB/maxSizeGiB/maxVolumeSizeGiB/tags 및 FIXED sizeBytes/선택적 encrypted. KMS mapping 없는 encrypted NEW는 차단한다. 서버가 fresh DAO와 표준 access/config로 만들며 client catalog를 신뢰하지 않는다. |
| catalog.pools[UUID] | uuid/zoneUuid/up/accessible/supported/tags. 실제 offering tags와 target zone을 대조한다. |
| catalog.existingVolumes[UUID] | uuid/typeDATADISK/stateReady/reservedfalse/sizeBytes/accountId/domainId/projectId/zoneUuid/poolUuid/attachedVmUuid(nullable). EXISTING은 target tenant/zone의 미사용 DATA만 허용한다. |

plannedUuid는 persisted allocationNamespace와 source UUID의 name UUID로 고정한다. 같은 source를 여러 NFS/SMB가 참조해도 한 allocation이며 다른 source는 같은 target UUID에 합치지 못한다. FILE와 BLOCK_RAW를 섞은 DATA, 충돌 filesystem, ROOT, unknown spec/consumer, 크기 overflow, foreign owner/domain/project/zone, pool/tag/access 불일치를 사전에 거절한다. FIXED offering에는 cmdSizeGiB가 없으며 source/request보다 큰 fixed10TiB는 custommax4096 검사를 적용하지 않는다. CUSTOM은 cmdSizeGiB를 표준 API에 전달한다.

전체 scope와 normalized allocations를 canonical JSON으로 SHA-256해 `planSha256`을 동결한다. execution은 requireFrozen으로 확인한다. 부모의 plan capability·expiry·caller·baseline revision 검증을 대체하는 helper가 아니며 해당 기존 gate 이후에 호출한다.

## allocation helper adapter

`StorageConfigurationVolumeAllocation.prepare(frozenPlan, Runtime)`은 모든 allocation의 fresh access를 첫 mutation 전에 검증하고 각 단계에서도 재확인한다. receipt와 volume lookup은 UUID로만 하며 프로세스 RAM과 display name을 authoritative 상태로 사용하지 않는다.

| Runtime method | production adapter가 보장해야 하는 동작 |
| --- | --- |
| validateAllocation | RootAdmin/caller/target tenant, owner/domain/project/zone, quota 전체 예상량, offering/pool availability 및 동결 fingerprint의 fresh preflight. |
| loadReceipt/saveReceipt | artifact metadata 또는 persistent receipt의 fresh read와 version+scope CAS. null expected는 insert-if-absent. CAS 실패는 ReceiptConflictException으로 구분해 concurrent winner를 recovery 상태로 덮지 않는다. |
| findVolume | exact planned UUID DAO 조회를 normalized uuid/type/state/sizeBytes/accountId/domainId/projectId/zoneUuid/poolUuid/attachedInstanceUuid/provenance로 반환한다. NEW provenance는 전체 allocation+planSha256과 동일해야 한다. |
| allocateAndRecord | **한 DB transaction**에서 INTENT CAS를 확인하고 표준 allocVolume(owner,zone,offering,null,null,name,cmdSizeOrNull,false,null,null,plannedUuid,null), volume_details provenance, ALLOCATED receipt를 함께 commit한다. UUID collision을 이름/존재만으로 adopt하지 않는다. |
| createPhysical/attach | DB transaction 밖에서 exact NEW physical create(pool)와 attach를 수행하고 다시 DAO identity를 검증한다. 이미 Ready/같은 target에 attached면 중복 호출하지 않는다. |
| inspect | 쓰기 없이 exact volumeUuid/mappingStatusEXACT/sizeBytes, FILE filesystem/filesystemUuid/blank, native preparationStarted/preparationComplete/preparationVolumeUuid/formatterActive를 반환한다. 장치 이름만으로 resume하지 않는다. |
| prepareFile | FILE에만 FORMAT_IF_EMPTY 또는 MOUNT_EXISTING. NEW provenance와 durable format intent 후에만 최초 포맷, BLOCK_RAW는 이 method에 들어가지 않는다. 반환과 후속 fresh inspect 모두 FSUUID/type/identity를 확인한다. |
| cleanupSafety/deleteUnpublished | 명시 요청한 아직 준비되지 않은 NEW만 exact scope/UUID/provenance 및 참조/published/mount/session/formatter/writer/다른VM 사용 부재를 fresh 확인하고 삭제한다. 실패 시 CLEANUP_PENDING에서 같은 UUID만 재시도한다. |

표준 `VolumeApiServiceImpl.allocVolume`은 DB-only이며 owner/access/limits/offering/zone/custom range를 검증하고 `customId`를 UUIDManager로 전달한다. `commitVolume`은 Transaction.execute를 사용하며 Cloud DB transaction은 nested start를 join한다. volumes UUID unique constraint도 존재한다. 이 소스 근거는 실제 adapter의 atomic commit/rollback DB 통합 시험을 대신하지 않는다.

receipt phase는 INTENT→ALLOCATED→READY→ATTACHED→FILE_PREPARING→PREPARED이며 formatStarted·filesystemUuid·provenance·version을 저장한다. 할당 응답 유실 시 committed receipt와 exact volume를 재사용한다. FILE preparation 완료 응답 유실이면 matching durable native completion과 fresh FSUUID를 확인하고 MOUNT_EXISTING으로 재개한다. receipt/native journal에 포맷 시작 기록이 있는데 blank/partial로 보이면 자동 포맷하지 않는다. 기존 또는 재개 FILE의 FSUUID는 mount 전에 receipt에 고정한다. RAW는 identity-only이고 mkfs/mount를 호출하지 않는다.

실패는 기본 PRESERVE/RECOVERY_REQUIRED이며 자동 detach/delete가 없다. explicit cleanup도 EXISTING/ROOT/formatStarted/PREPARED/published/다른 참조를 거절한다. cleanup 응답 유실은 CLEANUP_PENDING와 같은 exact UUID를 대조해 reconcile한다. 로그에는 raw throwable를 receipt에 저장하지 않고 고정 diagnosticCode만 사용한다.

## 30개 unit 증거와 남은 통합

- 2 FILE NEW+RAW NEW+EXISTING 혼합: NEW allocation3/FILE format2/RAW·EXISTING format0. 반복 prepare에서도 allocation과 mkfs는 늘지 않는다.
- canonical/deterministic UUID와 namespace 구분, source ceilGiB/explicit override/fixed10TiB, bad integers/overflow·scope·tag·consumer·unknown/secret field·size/type rejection.
- alloc commit 전/직후 실패, create/attach 전/후 응답 손실, second allocation 실패에서도 앞 DATA provenance 보존 및 자동 cleanup0.
- format 완료 응답 손실은 같은 FSUUID mount로 재개; durable intent 뒤 blank/partial/foreign filesystem·live formatter·wrong device/size·FSUUID drift는 reformat0.
- UUID hijack/provenance/owner/project/pool/attachment 변경·stale CAS·fresh authorization 실패는 fail closed.
- PRESERVE 기본, EXISTING/준비된 FILE/사용 중 DATA cleanup 차단, explicit 미발행 NEW delete 실패/응답 손실은 같은 UUID로 안전 reconcile.

scratch 경로는 `/root/work/epic898-preparation/acceptance-audit/multinew-prototype/`이며 Gson2.10.1/JUnit4.13.2 및 현재 runtime dependency jar로 컴파일했다. production 통합은 freeze 해제 후 부모/ROOT 담당이 기존 Manager/Configuration을 연결한다. Initial SharedFS DATA에도 동일 planned UUID/provenance가 필요하며 기존 deploy→metadata 기록 전 중단의 target creation gap은 별도 해결해야 한다. 여전히 #909 실제 multiNEW all4proto API/client I/O/reboot/UI/fault/lifecycle 인수는 남아 있다.
