# SharedFS Epic 현재 진행 구분 — 2026-10-08 23:32 KST

최신 추가: NEW20 child 삭제 API 1 회와 parent held-FD 596 회 I/O 및 기존 DATA 보존 실증을 완료했다. 실제 Ganesha restart를 관측했으며 fullFour / AD / ROOT swap / 최종 UI #1275 완료를 뜻하지 않는다. fc3e 로컬 fresh ISO 프로토타입은 6.12.95 커널 provision 단계에서 빌드 중이며 Cloud 등록·배포는 0이다.
이 문서는 시간대별 과거 기록과 현재 상태를 구분하는 진행 snapshot이다. 최종 UI#1275는 마지막 착수 직전 전체 작업 중단·사용자 보고·추가 지시 대기 경계를 유지한다.

## 2026-10-09 00:02 KST 후속

API volume-id-only same-protocol 재바인딩은 실제 성공했다. 새 FS 5b11 · managed root · fresh inspection을 확인했고 원래 source directory/file/ACL을 보존했다. 데이터 복사는 하지 않았다. 부모 실제 UI의 parent/child 새 SPARSE volume · Ready 표시와 child 편집 기본값 7b4/managed root/기존 relative까지 확인했다. 취소 후 추가 UI 제출 · move · 삭제는 0이다. 자세한 현재 기록은 [20261009-volume-only-actual.md](20261009-volume-only-actual.md)이다.

## Namespace 실제 배포 후 API·UI volume-only negative 통과

2026-10-08 23:44:47 KST에 부모가 관리 모듈 175 class의 검토된 namespace 1dad 변경을 배포했다. 관리 PID 1171342 / JAR SHA e99a3ca3bea20e04635edaa7ec007f47058979aa30b39cbf6e370f55c00fc31b, backup /root/epic898-backup-20261008-234320 및 원래 Runtime 2 class 보존을 확인했다. 이 namespace 배포는 전체 614 WIP 또는 새 profile/strict Runtime 배포가 아니다.

새 명시적 intent와 idempotency key로 과거와 같은 old a0b SMB 요청을 다시 생성했다. 과거 job 829c BLOCKED 증빙은 보존했다. 새 share 7ec0c41b-afa9-48ed-ba5c-fcb55ba29661 / epic898-volumeonly-ns-move10은 old a0b / FS a433 / managed /srv 경로에서 Ready이고, 기존 LOCAL_USER epic898-nested-client09의 UID 1002 ACL은 password 파라미터 없이 추가했다. 새 leaf inode 33685639 / device 2064 / 0:0 / 0770 및 named/default user 1002 ACL을 확인했다. 원래 디렉터리 재귀 변경과 포맷은 0이다.

API는 id + volumeid 7b4 + crossprotocol false와 별도 idempotency key만 전송했다. name/path/relativepath/filesystem/importmode는 생략했다. Job 4bc878b4-182b-43d8-8d2a-342e7eb2aedc / operation 932c5ee0-938a-49aa-a90e-8d406a83e244는 target NFS가 이미 사용하는 같은 물리 경로를 준비 전에 거절해 code 530 / BLOCKED였다.

부모 실제 UI도 현재 백킹 볼륨 7b4 선택과 cross switch OFF를 한 번 제출했다. Job 7f49383d-2564-466c-9a80-bb3c58795f80 (DB 909, actor 2, 14:51:21 UTC) / operation c67f3896-530b-4fa7-ba8c-996acd33cb8c도 같은 이유로 BLOCKED였다. UI가 기존 name/relativepath와 FORMAT_IF_EMPTY를 보냈다는 일반 파라미터를 확인했다. API의 volume-id-only 요청과 UI의 필드 유지 요청을 혼동하지 않는다. UI는 native prepare에 도달하지 않아 추가 포맷 0이다.

API 직전 baseline과 UI 직후를 대조해 source 전체 config·old volume/FS/경로·leaf/ACL, target NFS 전체 config·parent/child/file inode·owner/hash, native GEN 42 / checksum 4bcac862... / canonical 7 files, 원래 sentinel·SID·boot c53·SMB PID 2548/2552·POSIX receipt mtime가 모두 같음을 확인했다. 새 target file inode 133 / SHA d810...와 original sentinel inode 16777345 / SHA fe084...도 보존됐다.

따라서 서로 다른 managed volume의 같은 relative 준비와, volume-id-only가 실제 같은-volume crossprotocol 충돌을 준비 전에 막는 negative는 API·UI 실제 확인했다. 같은 프로토콜의 진짜 이동, 디렉터리/파일 보존 이동, child 삭제 및 전체 #910 인수는 아직 별도 GO 전이다. 비밀번호 reset·재포맷·자동 cleanup·원본/partial/AD OOBE 변경 0을 유지한다.

증빙: 910-volume-only-1dad-cross-source-prepare.json, 910-volume-only-1dad-negative-proof.json, 910-volume-only-ui-negative-proof.json 및 각 전후 native baseline이다.

## 최신 경계 — namespace 좁은 pin과 all4 HOLD

Namespace 비교 수정3hunks+신규3tests만 commit1dad2501b5cf3ee4528389ef73a7b61181cf6678로 별도 pin/push됐다. 이 문단은 배포 전 기록이다. 이후 실제 namespace 배포와 API/UI 결과는 위 최신 section에 기록했다. 이 작은 pin을 아래 전체614 WIP의 pin으로 간주하지 않는다.

**전체614 source WIP는 아직 pin0이고 실제 all4 적용은 HOLD다.** Profile callback이 저장된 snapshot의 renderedGeneration 기록을 덮어쓰는 P1과 native commit 성공→DB 저장 사이의 crash gap을 발견했다. 기존614/102 테스트 PASS는 그 당시 실행한 source 검증 기록이며 새 실패 구간의 보호·복구 검증 완료를 의미하지 않는다.

보완 대상은 checkpoint overwrite 방지/정확한 같은-operation frozen state 보존, native committed와 DB pending의 재관측·reconcile 및 각 중간 interruption/response-loss 회귀다. 이를 완료하지 않고 DB/health/LKG를 승격하거나 all4 적용을 시작하지 않는다. Native27 리뷰 단위의 crypto/POSIX/SERVICE 소스257+130 검증 기록도 보호 규칙 검토 중이며 pin/live0이다. 실제 ROOT swap0, SMB AD/AD restore 인수0, productionFour=false, 최종#1275 착수0을 유지한다.

## 현재 source와 actual

| 구분 | 확인한 상태 | 아직 완료로 볼 수 없는 부분 |
| --- | --- | --- |
| 최신 Java source | 정상 Checkstyle614 tests/102 classes/실패·오류·생략0,9864 Java NUL SHAae503092... before/after 동일, diff PASS | 전체614 pin0. checkpoint overwrite/crash gap 보완 및 source freeze. actual all4 HOLD |
| 실제 관리 | 379 선택 오버레이 +#1333 Volume13 class hotfix, legacy b38 Runtime family 보존 | 최신 generic all4 coordinator/profile/strict Runtime family 전체는 아직 actual 배포 증거 아님 |
| Runtime readonly producer | 새14개 회귀 포함 direct86 PASS, 전체614에 포함. 기존 COMPLETE transaction의 fresh signed READBACK + exact CLI hash/entrypoint/ROOT/host/consumer binding | 승인된 NEW SPARSE all4 fixture/profile의 실제 proof·all4 실행은 남음. 기존 interface는 failclosed |
| NEW20 SPARSE7b4 | 정상 생성/실제 qcow2 metadata/표시·삭제보호·rename 경로 보존. 정규 새 XFS5b11 format1회/receiptCOMPLETE 및 NN 외부 childwrite/fsync→parentread PASS | volume-only update negative/positive, 후속 childdelete/경계/새20cleanup는 별도 stage |
| #910 actual 차단 | 당시 actual 백엔드가 volume-qualified root를 제외한 relative 비교로 sourceSMB 생성부터 BLOCKED. native prepare·ACL·update0, source prefix도 미생성 | namespace만1dad 별도pin, narrow reactor 진행/live0. 배포+GO 후 exact 정상 API/UI 재검증; 재시도 금지 |
| NS/SN mixed | Unix UID1002로 actual boundpath 읽기 PASS, Ganesha4.3 NFS named ACL cross-read EACCES 실제 확인 | 새 VFS ACL package/fresh template 및 실제 mixed I/O 필요. 기본 NN 성공으로 해결됐다고 주장하지 않음 |
| VM51 partial10TiB | 021b SPARSE/실패 partial 보존, healthy=false/mkfs-in-progress 증거, 관련 durable operation RECOVERY_REQUIRED | 포맷·삭제·detach·reboot·upgrade0. 정확 새10TiB capacity 추가 입력 및 pool factor4 finally 미완료 |
| AD Client47 | 승인된 Sparse backup/Sysprep 후 새 SID/GUID, KST/UTC 정정 확인. 최신 readonly13:37 UTC OOBEInProgress1/WORKGROUP/고유 신원 유지 | 사용자 OOBE 완료 회신 대기. DNS/hostname/join/새 password 검증 변경0. AD 전체 인수는 남음 |
| Root/all4/AD 전체 | source 단계별 연결·보호 테스트와 package 로컬 self-test 진행 | 실제 ROOT swap0/all4 import0/productionFour=false/AD restore incomplete. 전체 완료·이슈 종료 금지 |

최신 source validation은 `/root/work/epic898-preparation/rendered-coordinator-profile-source-validation.json`의 실제 클래스별 결과를 읽었다. 이전585/97·608/100·611/101을 최신 전체 결과로 표시하지 않는다. Java/API/schema는 parent pin 신호까지 편집하지 않는다. 현재HEAD1dad은namespace의 좁은 수정만 포함하며 전체614 WIP와 구분한다.

## #910 차단의 정확한 재현 조건

Source volumea0bbe566-7aa6-4bbd-9998-226a4f52bf0c에서 새 SMB nameepic898-volumeonly-ns-move10 / relativeepic898-volume-only-audit-10/cross-parent/child / MOUNT_EXISTING / crossprotocol=false / noCleanup를 요청했다. Target volume7b4fae44-e926-41c9-bdd5-c0eca7ccd634에는 같은 relative 문자열의 NFS parent6b6dd32b와 childca41e478이 이미 Ready다.

당시 backend validateFileSharePathAvailable가 상대경로만 비교해 different-volume ancestor 오류로 throw했다. Native/Ganesha의 실제 filesystem boundary 실패가 아니다. operationCCA revision41은 BLOCKED이고 native GEN40/8cf99... IN_SYNC가 그대로라는 점도 구분한다. oldsource prefix와 SMB row가 없고 새 target inode/owner/hash 및 원래 sentinel이 보존됐다.

원문 물리 키는 instance+volume+relative다. 완화할 대상은 symlink/foreign mount/같은 physical path 중복 보호가 아니라 volume root를 제외한 namespace 비교다. Native payload와 current root의 실제 선택은 아직 실행되지 않았으므로 당시 source를 바탕으로 추정한 값을 실제 payload라고 기록하지 않는다.

근거 scratch:910-volume-only-old-cross-smb-prepare-proof.json /910-volume-only-blocked-mount-binding-readonly.json /910-volume-only-persisted-config-roots-readonly.json /910-volume-only-blocked-api-noeffect.json. NFS/SMB 원본 config의 volumeMountPath와 실제 mount는 managed /srv volume UUID와 일치했다.

## 다음 실행 경계

1. Parent immutable source pin·선택 배포·fresh API startup·host health를 확인한 뒤에만 실제 namespace 재시도 GO를 받는다.
2. 같은 원래 요청의 새 명시적 intent로 sourceSMB+existing LOCAL_USER ACL(noPassword)를 준비하고 baseline을 고정한다. 그 뒤 id+volumeid7b4만의 update/crossprotocolfalse가 target NFS overlap을 prepare 전에 거절하는지 확인한다.
3. 별도 same-protocol branch에서 volume-only 재바인딩이 허용되고 새 FS UUID5b11로 재검사·stale inspection 제거·source directory/data 보존이 확인되는지 API와 부모 UI로 연결한다.
4. Childdelete는 부모 별도 GO 후 정확 metadata만 삭제한다. 원래 DATA·partial·NS/SN 실패 자료는 보존하고 새로운 world-permission/guard waiver/수동 DB를 쓰지 않는다.
5. AD와 정확10TiB는 사용자 입력 대기 조건을 유지하면서 독립 기능 source/실증을 계속한다. 최종 UI 표준11탭 전체 QA는 경계 전 착수하지 않는다.

이 snapshot 이후 실제 변경0/Java 편집0이며 어떤 미완료 이슈도 닫지 않는다.
