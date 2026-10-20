# #909 추가 NEW volume 구현 설계 — source 감사/제안

Java repository는 편집하지 않았다. ROOT 검증/배포와 분리한 설계이며 구현·빌드·실증 완료를 뜻하지 않는다.

## 실제 재사용 지점

| 목적 | 현재 메서드/계약 | 재사용 시 제약 |
| --- | --- | --- |
| 표준 volume DB allocation | VolumeApiService.allocVolume(long ownerId, Long zoneId, Long diskOfferingId, Long vmId, Long snapshotId, String name, Long cmdSize, Boolean displayVolume, Long minIops, Long maxIops, String customId, Long kmsKeyId) | caller/owner access, offering/custom size/maxvolume/resource limit/zone/local storage를 기존 구현이 검증한다. Root Admin custom UUID를 사용한다. vmId/snapshotId null로 detached staging하며 caller는 기존 configuration RootAdmin gate. |
| 물리 생성 | VolumeApiService.createVolume(volumeId, vmId, snapshotId, storageId, display) | planned pool UUID와 actual pool을 대조한다. desired phase/receipt를 먼저 저장하고 외부 작업을 수행한다. |
| attach | waitForFileShareVolumeAttachable + attachVolumeToVM | 현재 wait는Allocated/Ready/Uploaded를 허용한다. clone helper는 명시적 staged 상태별처리 후 동일VM/owner/zone/type/pool/size 확인. |
| FILE 준비 | prepareFileShareBackingVolume + inspectAttachedFileShareVolume + createFileShareVolumePayload | NEW이고 provenance 확인된 blank volume에만FORMAT_IF_EMPTY. 1volume를NFS/SMB여러share가 참조해도 format은1회. |
| directory 준비 | prepareConfigurationDirectory | 현재MOUNT_EXISTING 전용이므로 FILE volume 준비 완료후에만 호출. path/UID/ACL은 기존validator/FD경계 사용. |
| BLOCK 준비 | prepareBlockBackingVolume | attach/identity만. iSCSI/NVMe-only volume은 mkfs/mount/디렉터리준비를 절대호출하지 않음. |
| 계획/집행 | StorageServiceConfiguration.plan/applyLocked + StorageConfigRestorePlan.validateCloneInitialVolume/build + StorageConfigDomainRestore.apply | 지금primitive source→targetUUID mapping을 계속유지하고 별도 normalized allocationPlan을 추가한다. |
| 실패정리 | cleanupFailedFileShareCreate/markFileShareCreateFailed | 현재PRESERVE 의미다. 그대로data보존 기반을 유지한다. |
| 위험 정리 | cleanupCreatedBackingVolume | provenance없이detach/destroy할 수 있다. multiNEW에서직접호출하지 않는다. |

현재 createFileShareVolume allocation 메서드는 없다. 위표의prepare/payload와 표준Volume API를 합친 adapter가 추가 구현 대상이다. allocVolume(CreateVolumeCmd)는 getCustomId를 direct overload로 전달하며, direct overload도access/limits를검증한다. 따라서 deterministic plannedUUID를 customId로 고정하는 표준 allocation 경로를 재사용할 수 있다.

## 호환되는 입력 모델

현재 mapping.volumes의 값을primitive UUID/string NEW로 유지한다. 추가NEW의 세부값은mapping.newVolumes[sourceUuid]에 별도로둔다.

```json
{
  "volumes": {
    "11111111-1111-4111-8111-111111111111": "NEW",
    "22222222-2222-4222-8222-222222222222": "NEW",
    "33333333-3333-4333-8333-333333333333": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  },
  "newVolumes": {
    "22222222-2222-4222-8222-222222222222": {
      "diskofferingid": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "storageid": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      "sizeGiB": 32,
      "usage": "BLOCK_RAW"
    }
  },
  "initialVolumeSourceUuid": "11111111-1111-4111-8111-111111111111"
}
```

- legacy initialNEW는기존createNew blueprint를사용한다. additionalNEW도별도spec없으면diskoffering/storage를blueprint에서명시적으로derive하고size는source bytes의ceilGiB 이상으로제안한다. UI/dry-run에그값을보여승인받는다.
- FILE/BLOCK_RAW usage는archive consumers에서계산하고 입력으로변조할 수 없게 대조한다. NFS/SMB/Posix refs는FILE, iSCSI/NVMe namespace refs는BLOCK_RAW. 동일volume에두종류가섞이면domain에서지원되는명확한정책이없을 때preflight blocker.
- filesystem은disk container format(QCOW2/RAW)과분리한다. configurationVolumeMetadata에는FSUUID만있고FS type은없으므로file-shares.filesystem과필요한새field를대조한다. XFS/ext4 여러consumer가충돌하면차단한다.
- FIXED offering에는cmdSize를전달하지않고offering.size≥source size를검증한다. CUSTOM은sizeGiB를전달하고표준custom.min/max/maxvolume을검증한다.
- EXISTING UUID는현재Ready/unattached/owner/zone/pool/reservation 검사그대로다. CREATED/NEW spec으로분류를바꾸어기존DATA를format하거나delete하지 않는다.
- newVolumes에unknown sourceUUID/Root type/0size/overflow/unknown key/foreign resource/unsupported encryption 정책이 있으면새서비스생성전에차단한다.

## plan freeze 및 crash-safe provenance

순수 helper 후보 StorageConfigNewVolumePlan은 archive resources/volumeInventory+mapping+validatedblueprint로다음을만든다.

- volumeMappings: 기존DomainRestore가소비하는source→targetUUID primitive map.
- volumeAllocations[source]: modeNEW/EXISTING, plannedUuid, source size/type, requested/effective size, offeringUUID+fingerprint, poolUUID+zone/tag/scope, inferredusage/fs, targetowner/zone, dataPolicy.
- allocationNamespace: 최초dry-run에생성하고metadata에유지한다. 같은artifact/normalizedspec/createdtarget 재검토는기존namespace를재사용하고spec변경을정확한cleanup/재검토없이바꾸지않는다.
- plannedUuid: UUID.nameUUIDFromBytes(allocationNamespace+sourceUuid), source별하나. 같은source를두share가참조해도동일UUID이고다른source는서로다른UUID.
- planSha256에allocationPlan/spec/resources/sourceartifactsha/baseline revision을포함하고기존5분capability token 및 exactconfirmation으로승인한다.
- capability는새resource allocation 전에소비한다. 그다음 ALLOCATION_INTENT를영속화한뒤에만sideeffect를실행한다.

실행 helper 후보 StorageConfigCloneVolumePreparation.Runtime은직접API adapter를호출하되 VolumeDAO lookup을매번새로하고 receipt를수정한다.

```text
INTENT → ALLOCATING → ALLOCATED → CREATING → READY → ATTACHING → ATTACHED
       → FILE_PREPARING / RAW_IDENTITY_VERIFIED → PREPARED → BOUND → VERIFIED
실패: PRESERVED / CLEANUP_PENDING / RECOVERY_REQUIRED
```

영속 receipt는artifact metadata 또는전용clone preparation record에sourceUUID/plannedUUID/actualVolumeUUID/targetinstance/owner/zone/pool/offering/planhash/phase/device/FSUUID/formatStarted/cleanupPolicy/diagnostic을저장한다. secret와DATAbytes는저장하지 않는다.

allocation만DB작업이라는VolumeApiService 계약을이용해 outer Transaction.execute 안에서 allocVolume와volume_details provenance 및receipt를같은DB commit으로묶을수있음을검증한다. physical create/attach/format을DB transaction안에넣지않는다. Volume detail keys 예: storage.config.artifact.uuid / storage.config.source.volume.uuid / storage.config.plan.sha256 / storage.config.target.instance.uuid.

MGT crash가alloc응답직전발생해도 plannedUUID lookup+동일provenance로동일volume을재사용한다. UUID가있지만owner/type/size/pool/tag/provenance가다르면자동adopt하지않고RECOVERY_REQUIRED다. allocation name이나UUID존재만으로owned=true를추정하지 않는다. GenericDAO의강화VO/update 계약과DB uniqueUUID/transaction 동작을실제검증한다.

초기service create에도별도crash gap이있다. 현재createConfigurationNewService의deploy반환후에만createdTargetInstanceUuid를metadata에쓰므로, 완료후기록전중단이중복서비스로이어질수있다. 추가volume helper만통과해도CREATE_NEW전체restart-safe라고주장하지않으며초기target intent/reconcile를별도로묶는다.

## FORMAT 및 apply 경계

모든allocation/attach/preparation receipt가PREPARED가된후executionPlan의volumeMappings를actual UUID로확정한다. 그전에는StorageConfigDomainRestore.apply를호출하지않는다. Directory 준비는FILE volume에만MOUNT_EXISTING으로수행하고domain batch는현재순서(protocol→POSIX→share→target→ACL)를유지한다.

initial source가BLOCK_RAW만사용하는경우현재prepareConfigurationInitialVolume은unconditionalformat이라수정대상이다. initial FILE이면기존FORMAT_IF_EMPTY, BLOCK_RAW면attach/identity검증만하도록usage계약을적용한다.

resume는existingFS/UUID/mount를재조회한다. formatStarted=true인blank/불일치/partial device는RECOVERY_REQUIRED이며다시mkfs하지않는다. 준비완료FILE은같은volume/UUID를MOUNT_EXISTING으로재사용한다. BLOCK_RAW는어느phase에서도mkfs하지 않는다.

## 실패 및 cleanup 규칙

기본은PRESERVE다. apply실패시기존desired/native snapshot을복구하고이미만든NEW DATA는receipt와현재attachment상태를보존한다. failedartifact/backup expiry는DATA수명주기로확장하지 않는다.

선택적자동cleanup이필요해도receipt provenance/exactUUID/owner/zone/targetVM/pool/planhash 일치, 다른resource/LKG 참조없음, formatter/mount/session/writer 없음, 아직publish/사용가능성이없음이확정된새resource만대상이다. EXISTING/source/initialforeign/Root disk는절대destroy하지 않는다. 실패는CLEANUP_PENDING과정확ID를기록하고정리실패때다른volume로대상을바꾸지 않는다. 이미formatStarted 또는사용가능성이있는volume은explicit operator detach/delete로분리한다.

## 필요한 회귀 및 실제 인수

1. 2개FILE NEW+1개BLOCK NEW+EXISTING혼합, 같은FILE을NFS/SMB참조, UUID중복없음/format1회/BLOCKformat0회.
2. 크기ceil/고정offering/customrange/foreignowner-zone/pool-tag/Root/mixedusage/fs-conflict와badmapping을resource생성전차단.
3. 첫/두번째alloc/create/attach/format/verify마다실패와중단. 같은stagedUUID·receipt재사용, duplicate allocation0, existing DATA/hash/FSUUID unchanged.
4. plannedUUID hijack/provenance mismatch·target/pool/size변경·동일request 재시도·plan expiry/stale token 차단.
5. formatter완료후응답손실 및MGT/guestrestart에서FILEUUID/mount 재관측resume, partial/blank전환에서자동reformat0.
6. 정리실패PRESERVE/CLEANUP_PENDING 재시도, snapshot복구가volume receipt를지우지않음, artifactexpiry/delete가DATA를지우지않음.
7. 실제NFS/SMB/iSCSI/NVMe단독+통합multiNEW API/guest/client I/O·auth·reboot·UIreviewedplan/async/progress/실패복구.
8. 참고기존테스트: StorageConfigRestorePlanTest, StorageConfigVolumeBindingTest, StorageConfigInitialVolumePreparationTest, StorageConfigTransientDirectoryTest, StorageConfigurationBatchTest, StorageConfigSemanticValidationTest.

독립helper ownership을배정하면순수plan/persistedreceipt engine+의미있는fault matrix를먼저구현하고Manager/Configuration integration은부모의compile freeze해제후별도로연결할수있다.
