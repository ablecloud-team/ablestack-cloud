# 새 클러스터 all4 기능 인수의 단일 실행 흐름

이 문서는 final source/artifact GO 뒤 parent 가 검토해 단계별로 실행할 기능 시험 계획이다. 이 계획으로 추가 QEMU · Cloud 생성/변경은 0 이다. 이후 CreateSharedFS의 일반 SYSTEM 선택 기능만 별도 source/test 범위로 보완했으며 actual UI는 아직 미검증이다. 스타일/레이아웃 표준화는 시작하지 않았다. source-only, 로컬 실제, 기존 Cloud subset, 새 클러스터 인수를 구분한다. 최종 11탭 style/theme/button/dialog/keyboard QA #1275 는 이 흐름에 포함하지 않는다.

빌드/서명/패키지는 [단일 source 빌드 계획](20261009-final-single-source-build-plan.md), 전체 판정은 [29개 현재 원장](20261009-current-acceptance-summary.md)을 따른다. 예전 THIN cache gap은 host 0a10 보완으로, template selection은 explicit API 707 배포로 전진했지만 새 artifact의 실제 producer/consumer/cache 증거는 필요하다. 옛 fc3e 계획을 새 artifact나 실행 승인으로 읽지 않는다.

## 0. 실행 계약과 보호 대상

testRun UUID, source S, template/runtime/package/image SHA와 parent GO 시각을 먼저 고정한다. stage별 intent/idempotency key를 써서 Async job UUID → operation UUID/revision → resource UUID → guest/host proof → 실제 UI 캡처를 연결한다. pending/recovery는 성공으로 올리지 않고 동일 key 조회 후 정식 resume/reconcile을 따른다.

API 증거는 비밀 없는 request allowlist·job/code/phase·대상 UUID와 전후 projection이다. UI 증거는 parent의 실제 생성·편집·조회·실패 안내/화면과 같은 job/operation 연결이다. UI는 parent 전담이다. credentials/keytab/NT/CHAP/DHCHAP 값·파생 hash는 파일/argv/로그/URL에 남기지 않는다.

| 보존 대상 이름 | 제외할 instance UUID |
| --- | --- |
| nfs-test | 1d3f751a-a254-43b7-9336-b34ff9c64625 |
| epic898-ui-test-01 | 0c7b3e03-0b74-400e-88c3-6b97e6dec5a7 |
| epic898-reused-03 | 00b331db-f1ba-4a40-aa19-931fdd393a0e |
| epic898-config-clone-04 | 6e75936a-5a11-4e82-b9f0-849b754bf9d6 |
| epic898-config-clone-05 | 67673fb1-f83c-4da7-a395-14d3f5321fe7 |
| epic898-static-smb-audit-06 | b54a3c04-fe88-4bde-9ae3-1c67e0998030 |
| epic898-format 10t-audit-07 | 0b5a950b-7362-4cc6-8c48-81f938bbe26e |

숫자 VM ID 39/41/43/48/49/50/51 및 모든 현재 기존 FS/VM/ROOT/DATA UUID 를 실제 실행 직전에 bind해 exclusion manifest에 더한다. 이름만으로 선택하지 않는다. 기존 NEW20 7b4fae44-e926-41c9-bdd5-c0eca7ccd634, 원래 a0bbe566-7aa6-4bbd-9998-226a4f52bf0c, partial 021b의 실제 UUID/attachment/journal·기존 leaf/file을 보존한다. Client 47/DC 45는 AD 의존 stage 전 설정 변경 0이다.

## 1. artifact · 배포 · capacity checkpoint

AD/native/retained 필요한 핀을 통합한 S와 새 full image/runtime/RPM을 먼저 고정한다. production all4/AD flag를 임의 true로 만들지 않는다. source 760 AD 부분검증·752 retained 구현·로컬 auth PASS는 전체 시작 조건을 대신하지 않는다.

actual ADE 707/old Runtime 2는 새 profile proof를 default fail-closed 한다. 새 Runtime 3/interface/Manager/API/DTO/provider/schema와 KVM command/wrapper/QemuImg ABI를 같은 출력으로 맞춘 뒤 parent 배포 GO가 필요하다. 원래 7 VM/3 host/10 recovery rows/7 OFF policy와 strict manager/agent/template version을 대조한다. legacy filename이나 5-part trim으로 platform을 추정하지 않는다.

| 자원 | 실행 계획의 고정 후보 |
| --- | --- |
| zone / pool | f4fd8c56-ed31-4dfd-bf3c-10e3000fd10f / 90b0c4e3-7078-4372-8da0-9625bc0e806a |
| 4C/8G SPARSE SO / ROOT DO | 57c431e2-3330-4a1d-a0ff-11cbb5321899 / 1ae2dee3-dbf8-4626-9664-601a7dbef950 |
| 2C/4G SPARSE SO / ROOT DO | fc808c8b-00fa-42d2-98b6-a082f12c347f / 90b3f7d0-bf5b-46dc-bb6c-d46c57fa8774 |
| shared customized SPARSE DATA DO | c48ab8d8-faba-4d43-85a6-e98a9ecc0cd5 / no tags |
| L2 network | 4d9047dc-c8b7-4c80-83bf-2c075adc2995 |
| source F1 IP / alias 후보 | 10.10.13.243/16 gateway10.10.0.1 / 10.10.13.244 |
| target F2 IP / alias 후보 | 10.10.13.245/16 / 10.10.13.246 |
| 외부 Linux C1/C2 후보 | 10.10.13.247 / 10.10.13.248, 새 SPARSE ROOT만 |
| AD F3 network | 5b054061-0c15-4389-bfca-873871f4b470 / VLAN201 / isolated DHCP |
| 기존 DC / Windows client | 192.168.16.2 / 192.168.16.11, ablestack.local |

주소 free/할당을 아직 확정하지 않는다. 실제 생성 직전 NIC/secondary DB·L2 ARP·tenant/zone/network 권한·MAC 고유성을 고정한다. F3에 L2 STATIC을 넣지 않는다. IPv6는 승인된 dual-stack fixture/route가 있을 때만 실제 CIDR matrix를 수행하며 미구축이면 OPEN으로 남긴다.

기존 authoritative headroom은 약 1,205.316 GiB다. F1/F2 각각 ROOT 5 + FILE 2×20 + RAW 2×20 = 85 GiB, 합계170 GiB로 initial을 다시 더하지 않는다. AD 25 + Linux C1/C2 ROOT 최대 20씩 + retained 추가 ROOT 10 + unique cache 30 + contingency 16을 더하면 약 291 GiB다. 남은 914.316 GiB는 추가 1 TiB와 동시 유지할 수 없다. 일반 UI 생성 F_UI25 GiB를 더하면 총316 GiB/남은889.316 GiB다. F_UI 정리 또는 다른 단계의 순차 배치 후 계산한다. template/root가 더 크면 다시 계산한다.

실행 checkpoint에서 capacity type 3 used/reserved/total·template/snapshot reserve·factor/threshold·actual unique cache/volume·physical free를 다시 계산한다. 세 host의 같은 GFS를 합산하지 않는다. factor 4 guard 23325/finally와 정확10 TiB human 선택은 미완료다. global/threshold/custom max/중복 pool/partial 희생은 계획하지 않는다.

## 2. 일반 UI 생성과 protected API foundation의 구분

private USER T_source를 정상 등록해 owner/launch 권한·zone Ready/KVM/x86_64·checksum/protected manifest/POM/S/canonical key를 확인한다. SYSTEM 전역 selector를 바꾸지 않는다. 실제 template/runtime/catalog/artifact UUID 를 반환값으로 고정한다.

소스 검토 당시 CreateSharedFS custom form/request builder에는 templateid가 없었다. 일반 사용자 SYSTEM 선택은 기존 form/select로 optional templateid를 보완한 별도 기능 source다. default 미지정은 기존 동작을 유지하고, API가 반환한 SYSTEM/Ready/scalable/KVM/zone/arch/owner-context 조건을 확인한다. 실제 제출·성공 Cua 검증 전 source-only로 표시한다.

일반 사용자 생성 F_UI는 정상 UI의 eligible SYSTEM 선택 또는 default와 SPARSE SO/DO로 검증한다. 별도 Root5/DATA20 예산25 GiB를 추가하고 exact SYSTEM UUID/실제 request/job/결과를 고정한다. SYSTEM prototype을 전역 등록해 후보를 억지로 만들지 않는다. eligible SYSTEM이 없으면 explicit UI success는 OPEN으로 남는다.

protected private USER T_source를 쓰는 all4 F1은 templateid와 승인된 validationartifactuuid/SHA를 내부 foundation API 준비에서만 전송한다. 일반 form에 artifact refs/RootAdmin 실험 정보를 노출하지 않는다. F1 생성은 API-only foundation이며 actual UI의 상세·프로토콜·작업·결과 조회로 이어간다. 이것을 일반 UI 생성 성공으로 주장하지 않는다. API 반환값으로 FS/instance/VM/ROOT/initialDATA/owner/domain/project/zone/pool/NIC UUID/MAC을 bind한다. cache/ROOT/DATA의 actual provisioning·backing-none·virtual size·metadata allocation을 확인한다. qemu argv가 없으면 미관측으로 기록하고 source test·enum·구조를 같은 증거로 묶지 않는다.

상세는 정보, 작업 탭은 operation/history/preparation을 확인한다. bounded GET·자동조회·volume 선택·stale observation·권한/오류 상태를 기능으로 검증한다. 전체 스타일 QA는 하지 않는다.

ledger 슬롯은 F1/F2/F3 FS/instance/VM/ROOT/각 DATA/endpoint/share/ACL/policy, C1/C2 VM/NIC, T_source/T_target, bundle/backup/import/artifact/planSHA/token, job/operation/revision, native generation/pointer/LKG/canonical7, serial/device/FS UUID다. placeholder를 실제 UUID로 취급하지 않는다.

## 3. all4 source와 외부 client 기본 인수

F1에 initial XFS 20·추가 EXT4 FILE 20·iSCSI RAW 20·NVMe RAW 20을 만든다. FILE은 exact NEW UUID 당 format 1, RAW는0이다. strict serial/size/ROOT 제외·blank signature·staged receipt를 effect 전에 확인한다. partial이면 자동 mkfs/삭제/detach 없이 보존한다.

정상 UI/API로 NFS export/ACL, SMB share/LOCAL_USER ACL, iSCSI LUN/CHAP·mutual, NVMe subsystem/namespace/host/DHCHAP·mutual을 연결한다. volume 없는 logical subsystem과 mandatory RAW namespace를 구분한다. A/B owned socket/port/PID/start tick/fresh health를 고정한다. #900 기존 A/B 선택 삭제·same-password cold PASS는 재시험하지 않고 modern all4 import/ROOT 뒤의 새로운 lifecycle만 검증한다.

C1/C2에서 NFSv4/dual v3, SMB authenticated held handle, iSCSI/NVMe bounded raw window read/write/fsync/fresh reconnect를 검증한다. 로컬 kernel PASS를 반복하지 않고 Cloud/QGA/외부client/API/UI 연결을 새 gate로 삼는다. RAW는 자기 exact offset/size만 쓴다.

## 4. POSIX · SMB mode · nested · CIDR

새 leaf에만 UI preview/token/명시승인→API CAS→native scope/inode/owner/mode/access/default ACL→client 결과를 연결한다. data root 재귀 chown/chmod는0이다. root/all/no squash·anon/manual owner·RO preserve·preview stale/inode race·rollback을 확인한다.

Ganesha5.5.3 actual selected service/ACL proof 위에서 mixed NS/SN named/default·crossProtocol false overlap/true common policy·independent ACL·symlink/bind/rename/cold/fault를 검증한다. 기존 NN/SS/volume-only/NEW20 delete 조건은 재시험하지 않는다.

전용 SMB share에서 기본0660/0770·명시0775/0775·mask/force/defaultACL/setgid 조합을 확인한다. old files/PID/session/heldFD는 보존하고 reload 실패는 이전 config/revision로 돌아가야 한다. INHERIT_PARENT_OWNER와 FORCED_UID_GID 충돌을 사전 차단한다. 관리 force ID>=10000과 기존 LOCAL_USER UID를 구분한다. AD 결과는11번에 배치한다.

NFS C1 /32만 허용하고 service/C2 거절 뒤도 daemon/다른 export를 유지한다. wildcard/서비스IP포함 CIDR의 local probe와 skip reason, 실제 parse/process/listener 실패를 대조한다. DBus UpdateExport→ShowExports/readback/PID는 별도 proof다. SMB 단일/다중 CIDR·allow/deny·same-session/update·조건부 IPv6를 확인한다.

## 5. cold boot · scale · 조회

configuration을 freeze하고 client quiesce 뒤 UI normal reboot1회로 STATIC/alias/MAC/CIDR/gateway/DNS/DHCP0·NUMERIC·FS/serial/hash/owner·canonical7/gen/policy receipt no-op을 검증한다. one-shot inactive+exit0은 성공일 수 있으며 active만으로 판정하지 않는다.

export1/10/100·v4/dual·다중 endpoint/port, first-probe delay/retry·특정 export fail·hang bounded cleanup·SMB once·internal/systemd deadline·listener ready/probe progress·QGA 단절을 monitor/API/작업 탭과 연결한다. approved own-fixture fault hook이 없으면 unit PASS로 실제 gate를 닫지 않는다.

SO dynamic/shared/HA/arch/template 필터·API negative 후 실제 online scale/operation/lease를 검증한다. FILE/RAW unique total·bind dedupe·독립 partition SUM·failed/partial/detach/refresh를 확인한다. 추가 reboot는 바뀐 조건이나 별도 fault gate가 있을 때만 한다.

## 6. runtime/catalog와 rollback

normal register/verify/AVAILABLE·UI catalog/preflight/apply/readback/history를 연결한다. installed/LKG/Root-retained/import reference의 revoke/delete 제약, tampered archive/signature/key·unknown/incompatible consumer·feature/package loss·wrong pin/provenance를 effect 전에 거절한다.

F1 held I/O의 compatible update, 의도한 health-failure rollback/recovery, manual rollback을 필요한 조건으로 구분한다. UI100%보다 signed current bytes/source-previous receipt/terminal/data-session 보존을 본다. 기존 bridge subset은 반복하지 않는다. stable signing은 org403/key provisioning과 test key를 구분한다.

## 7. backup download/import · multi NEW

UI backup→download→upload→validate→plan preview→apply와 정상 create/download/upload/validate/plan/applyStorageServiceConfigRestore를 연결한다. archive는 구성/encrypted identity·credential이며 DATA 내용 백업이 아니다. traversal/symlink/size/schema/hash/owner/feature/secret scope negative는 allocation 전에 거절한다.

protected private USER로 CREATE_NEW하는 F2의 template/artifact foundation은 API-only다. 일반 UI backup/download/upload/validate/preview/apply는 실제 지원하는 SYSTEM/EXISTING 경로로 별도 검증하고, private foundation apply의 UI operation/history 조회를 사용자 UI 제출 성공으로 대체하지 않는다. UI에 지원하지 않는 multi NEW 입력은 API-only gate와 남은 기능 UI gap을 표시한다.

CREATE_NEW F2에 source4 DATA를 NEW로 매핑한다. planSHA→execution parentSHA/actual target-initial bind·추가 deterministic customID/provenance receipt/CAS를 고정한다. initial 중복 allocation0, FILE1/RAW 0, default PRESERVE다. source file copy를 주장하지 않고 새 target data의 external all4를 확인한다.

same artifact/key replay·response loss·INTENT/ALLOCATED/attach/prepare failure·MGT restart recovery에서 count/UUID/owner/zone/pool/provisioning/targetVM/revision을 보존한다. formatted partial/imported Ready를 cleanup 삭제하지 않는다. fresh EXISTS는 자기 Ready DATA만 사용해 disk offering 불필요·foreign/tenant/size/failure preserve를 검증한다. UI에 없는 field는 API-only request와 actual UI 결과 조회를 구분한다.

## 8. lease · cancel · recovery · live queue

master OFF negative는 재반복하지 않는다. fresh infrastructure/metrics/native logical lease/maintenance supported 조건에서만 parent가 normal API로 master와 F1 policy를 enable한다. 원래 7 policy OFF를 유지하고 global/per-instance/expiry/revision/provenance·finally 값을 고정한다.

plan/import/runtime/ROOT/scale acquire/renew/heartbeat/cancel/release, queued와 effect-boundary cancel, release-failure recovery hold, OFF 뒤 started row 의무를 검증한다. drainSupported=false면 unsupported와 실제 미완료를 표시한다. stop/kill을 drainComplete로 부르지 않고 구현+open-handle 실제 quiesce proof가 있어야 닫는다.

장시간 job 전에 bounded watcher를 arm해 actual jobUUID/instance/account/live StorageServiceInstance sync type/id를 관측한다. completed queue row 없음은 획득 증거가 아니다. 기존 VM51 stop negative/expired watch를 재시험하지 않는다. GET/status/control/UI는 writer 중 bounded 응답하고 MGT/agent restart는 I/O·formatter·ROOT critical과 분리한다.

## 9. 자기 target 분리·보존·삭제

F2 검증 후 client quiesce와 getSharedFileSystemDeletionPlan의 actual 목록/planHash/UUID/type/owner/domain/project/zone/pool/size를 고정한다. UI enum은 PRESERVE_VOLUMES/DELETE_VOLUMES 두 가지이며 detachStorageServiceBackingVolume은 별도 분리다.

기본 PRESERVE의 unmount→detach→VM 제거/audit/재사용과 실패 시 expunge/DB-delete 중단·정식 retry를 확인한다. DELETE는 별도 disposable의 exact NEW만 이중 확인한다. unmount/detach fault·UUID/replacement/tenant/pool/size 변경·stopped/powerloss/persistent formatter marker를 검증한다. 원래 7/partial/NEW20 destructive request는0이다.

## 10. ROOT/auto rollback/retained latest

기존 ROOT upgrade form은 listStorageServiceSystemVmTemplates의 일반 SYSTEM target selector/preflight/execute를 재사용한다. private USER target의 승인 artifact UUID/SHA는 일반 폼에 없고 내부 API-only foundation이다. 해당 API job의 실제 UI history/status 조회와 일반 SYSTEM target의 실제 UI 선택·실행은 다른 증거다. eligible SYSTEM target이 없으면 사용자 ROOT UI success는 OPEN이며 hidden Vue mutation/browser API injection으로 우회하지 않는다.

F1 current all4/identity/DATA/Root pin과 정식 maintenance Root4를 freeze한다. private USER T_target 승인/preflight/UI upgrade→PRESTOP→quiesce→AFTERSTOP encrypted source→swap→bootstrap/signed code/identity/mount/attest→all4 stage/activate/verify/commit/finalize를 job/history/guest와 연결한다. 명시 maintenance의 interruption이며 열린 handle 연속성을 주장하지 않는다.

같은 bytes의 다른 templateUUID는 Root/transaction 교체 subset이다. OS/kernel/package gate는 실제 다른 approved artifact/capability delta가 있어야 한다. supplied target 없이 이름/detail만 바꿔 upgrade를 주장하지 않는다.

precommit fault는 latest source Root/data/canonical7/gen/runtime로 정상 swap-back/resume, committed crash/response-loss는 forward recovery만 허용한다. 운영 중 최신 변경 뒤 retained manual restore는 source20/target10 approval/전용 NEW_ACTIVATION/latest identity·POSIX/no historical inverse/actual Root4-host-READBACK를 검증한다. UNKNOWN/newtarget/different pin/foreign proof는 거절한다. retention/finalize/reference/drop·lease-before-maintenance release도 확인한다.

## 11. AD/Windows 조건부 기능과 복원

OOBE 사용자 완료 회신과 actual 완료 확인 뒤만 기존 승인된 Client 47 DNS→DC.2/hostname/join/reboot를 한다. 회신이 없으면 conditional OPEN으로 남기고 다른 기능을 계속한다. 질문 반복/OOBE·약관·암호 우회는0이다.

F3 isolated DHCP/route/DNS/MAC·SPARSE Root/Data·fresh handler/normal signed pin/profile을 확인한다. 새 AD test user/group/alias scope와 cleanup을 고정하고 UI/API join/leave/principal receipt, DC/DNS/SPN/NetBIOS/machineSID/keytab/idmap, Windows Kerberos/CIFS alias/AD user-group ACL positive/denied를 검증한다. source-only proof는 actual join/identity 복원이 아니다.

`#915`: 서로 다른 parent1001001:1001001/setgid2775의 AD 생성·old owner 보존·RO/invalid 거절·기본복귀. `#908`: 관리ID>=10000의 AD/local force identity·권한제거 뒤 auth-success/tree-denied·실principal audit·NFS policy 일치. `#916`: SID→UID/GID/defaultACL/protectedID/충돌을 검증한다.

JOINED F3 encrypted backup/import와 ROOT/retained/reboot 뒤 machine identity/idmap/credential/SPN/alias/Kerberos/permission을 검사한다. different candidate의 production feature loss와 exact same-pin fixture 예외를 구분한다. rotate/leave-failure/rejoin 후속은 required pin+parent stage GO가 있을 때만 한다.

## 12. 정확 SPARSE 10 TiB와 마지막 STOP

자기 disposable 정리 뒤 fresh capacity를 계산한다. 1 TiB는 정확10 TiB를 대체하지 않는다. 추가 pool/factor human 선택과 parent exact plan GO 뒤 XFS→own cleanup→EXT4를 순차 실행한다. partial 021b/global/threshold를 희생하지 않는다.

NEW10 TiB actual metadata/identity/ROOT 제외/formatter budget/deadline·bounded GET/작업 탭·완료/external NFS-SMB/cold UUID-data·interruption/response loss/partial/명시resume를 검증한다. 이전 AS 1 GiB/physical 160 GiB/free 3.30 TiB guard를 fresh budget으로 확인하고 정확 NEW formatter만 제어한다. factor finally가 미완이면 닫지 않는다.

모든 functional gate 대조 후 최종 #1275 착수 직전에 전체 작업을 중단하고 사용자 보고·추가 지시를 기다린다. 이 흐름 중 최종 style/theme/buttons/dialog/keyboard 전체 QA는0이다.

## 29개 이슈의 남은 기능 연결

| 이슈 | stage | 남은 gate와 기존 증거 구분 |
| --- | --- | --- |
| #911 | 6 | 기존 CLOSED 유지, 새 맥락은 #924/#920 |
| #1269 | 2~12 | bounded consumer/권한/failed/recovery functional, 기존 null/stale PASS 재반복 없음 |
| #924 | 1/6/10/11 | strict3consumer/catalog lifecycle/signed fault·featureloss/stablekey |
| #892 | 3/7/8/10/11 | 새 all4 atomic/crash/crypto/recovery/canonical7/LKG, localauth와 별도 |
| #897 | 8/10 | real opt-in lease/pressure/drain/cancel/restart/release-hold/activequeue |
| #974 | 12 | 정확 SPARSE10T/resume/capacity/finally, THIN/NEW20/1T로 대체 없음 |
| #913 | 2/5/10 | freshRoot/NIC명·다중NIC/static 변형/all4cold, 기존240/241 재반복 없음 |
| #914 | 5 | 1/10/100/dual/다중group/delay·hang/deadline/QGA단절/SMBonce |
| #918 | 4/5 | /32·multi·wildcard allow/deny/localSkip/daemon/DBus/fault |
| #895 | 9 | 두 policy/별도detach/provenance/stopped-partial-powerloss/retry-audit |
| #909 | 7 | freshsource-target multiNEW/FILE1RAW 0/encryptedcredential/idempotency-fault |
| #920 | 10/11 | 실제 Root/retained/latest/OSdelta/ADidentity/data-IP-runtime-cold |
| #896 | 1/2/5 | offering/templateRoot/UIfilter/권한/실제거절 |
| #891 | 5/8 | newSparse onlineScale/SOnegative/resourcelease-maintenance |
| #904 | 7/9 | freshEXISTS/diskoffering불필요/foreign-tenant/failurepreserve |
| #905 | 3/5/7 | FILE-RAW unique/partitionSUM/failed-detach-refresh, 기존dedupe와 구분 |
| #894 | 4 | 전체preset/anon-manual-RO/previewrace/명시승인/newmixed |
| #906 | 4/5 | 모든endpoint/v3v4/client/freshpackage/scale-coldNUMERIC |
| #903 | 4/7/10 | freshGanesha mixed named/default/commonpolicy/change-rollback-root |
| #915 | 4/11 | ADparentUID-GID/setgid/RO-invalid/default복귀/forceconflict |
| #916 | 4/11 | access-default/recursiveboundary/ADSID/protectedID/cold-rollback |
| #919 | 4/5 | mask-force-inherit/newmode/oldpreserve/PID-session-handle/reloadfault |
| #910 | 4/5/7 | mixedACL/symlink-bind-rename/independent-cold, 기존NNSS-volumeonly-delete 재반복 없음 |
| #908 | 4/11 | managed>=10000 forceID/AD-local/deny-audit/NFSpolicy-root |
| #902 | 11 | OOBE→join/DNS-SPN-alias/Kerberos/identity-import-root 전체 |
| #907 | 4/5/11 | IPv4/조건부IPv6 CIDR/deny/same-session/update-cold |
| #900 | 3/5/7/10 | 새 modern all4/ROOT의 multi SMB endpoint lifecycle, 기존 VM50 A/B auth·선택삭제·재부팅 PASS 동등 재시험 없음 |
| #901 | 3/4/5 | 다중NFS IP-port/v4-dual/ACL 행/callback-readiness-fault |
| #1275 | 마지막 STOP | 기능시험만, 최종11탭 QA 전에 사용자 대기 |

다른 이슈의 style/theme 잔여도 #1275 경계로 표시해 기능 완료와 혼동하지 않는다. functional만 끝났다고 조기 close하지 않고 source gap은 pin/live/actual proof까지 OPEN으로 남긴다.
