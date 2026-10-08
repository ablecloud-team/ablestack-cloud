# #918 / #914 / #910 / #895 후속 gap audit

실제 원문은 GitHub 최신 issue view의 body/comments를 issue-N-gap-audit-fresh.json으로 읽었다. 네 이슈 모두 OPEN이며 기존 comment는 제한 ACL/small reboot/PRESERVE/중첩 주요 경로 subset만 완료로 기록한다. source HEAD와 현재 peer WIP의 파일SHA/시각은 918-914-910-895-gap-source-snapshot.json에 고정했다. 원격 StorageVM 변경·OOBE 조작·최종 #1275 스타일/테마/키보드 QA는0이다.

## 우선순위와 작은 구현 단위

| 우선순위 | 이슈/단위 | 확인한 근거 | 필요한 최소 변경/검증 |
| --- | --- | --- | --- |
| P1 | #895 stopped partial journal 보호 | SharedFSServiceImpl requireNativeLifecycleIdle는 Running 외 VM을 건너뛰고 withSharedFSWriterLock에 generic unresolved-operation guard가 없다. guest/poweroff 뒤 incomplete formatter가 observer에서 사라져도 DATA 안전 승인은 아니다. | persistent originating format/protection marker + generic RUNNING/RECOVERY_REQUIRED DAO gate를 stop/expunge/delete 경로에 적용. Stopped/Destroyed/Error도 marker가 pending이면 deny; unknown은 failclosed. ROOT/native writer unknown을 unlock/삭제로 자동 해소하지 않음. |
| P1 | #895 삭제 승인 identity 재검증 | storedDeletionVolumeIds는 sharedfsId/UUID만 확인하고 숫자 ID를 반환한다. provider는 type/account/attachment만 검사. snapshot의 UUID/domain/zone/pool/size 변화를 current row와 대조하지 않는다. | 신규 StorageSharedFsDeletionIdentity helper/protected identity hash와 fresh tuple 검증. attachment만 originalVM↔null 재시도 허용; foreignVM/new/missing/replaced DATA reject. pool/type 필드 snapshot에 추가. |
| P1 | #910 SMB update의 nested/volume 경계 | doUpdateStorageSmbShare는 path/relative 있을 때만 검증하며 volume-only 변경은 overlap검증 skip. stored relative를 복원하지 않아 nested path-only update가 legacy path로 오판된다. NFS update는 이미 effectiveRelativePath와 volume변경 조건을 가진다. | SMB를 NFS와 같은 name/path/relative/volume 조건+effective saved-relative로 맞춤. volume/path 변경에 stale backingPath/lastInspection 무효화. |
| P1 진행 중 | #918/#914 D-state probe/cleanup | 초기 감사의 legacy probe subprocess.run(timeout)은 D-state kill→communicate 대기를 bounded하지 못하고 FD9/전체 cleanup deadline이 없었다. | kernel owner가 boundedPopen/pidfd/FD9/shareddeadline와 pendingchild failclosed를 WIP로 보완했다고 보고(4new+native116PASS). 초기 3Mock.run harnessERROR는갱신됐다. 아직 source/live/full NFS fault검증과 구분. |
| P2 | #918 verified DBus fastpath | 현재 systemvm 전체 검색에서 UpdateExport/ShowExports/DBUS_DYNAMIC_UPDATE_VERIFIED 구현이 없다. 제한 ACL skip와 같은 기능이 아니다. | UpdateExport→exact Export_Id/config hash/readback/PID+owned socket 검증된 endpoint만 mount probe를 생략. RPC 불명/실패는 old revision 유지/fallback으로 분리, 다른 endpoint/export를 전체 중지하지 않음. |
| P2 | #914 protocol별 boot checkpoint | 실제 legacy while-loop scratch 추출+system ops no-op 실험에서 NFS fail-first/succeed-next이면 NFS2/SMB2 apply/exit0. SMB 완료 상태를 유지하지 않는다. | bootId+desiredhash+protocol checkpoint, 완료 protocol no-op·실패만 retry. workload total deadline을 내부에 두고 unit상한보다 먼저 structuredexit. reboot와 interrupted phase 재관측. |
| P2 | #914 runtime/export readiness·진행 상태 | legacy는 start 직후 probe. rendered-boot WIP는 four protocols 1회 replay+verify, runtime.verify NFS는 config hash/TCP/process count 중심이고 actual export-id exact set/rpcbind 등록/대표 probe gate가 없다. monitor에 boot phase/probe pending/skip를 병합하는 전용 경로도 검색되지 않았다. | WAIT_RUNTIME_READY에서 PID/owned listener/rpc registration/exact exportinventory gate→대표 endpoint/proto probe→bounded 상세probe. runtime-ready와 full probe complete는 별도 상태, monitor/API functional 표시. |
| P2 actual 대기 | #910 child delete/ACL/cross boundary | Backend child deletion은 metadata/ACL만 삭제하고 parent 보호·cross-volume path validate, native dirfd/O_NOFOLLOW+st_dev/findmnt fence 기반은 있다. helper/unit 존재가 all combinations 실증은 아니다. | same-volume NFS/NFS·SMB/SMB·NFS/SMB·SMB/NFS, same physicalpath explicitcross policy, samefs bind-mount boundary/symlink race, child delete 실제directory/inode/hash 보존, independent ACL, detachdeny, reconcile/reboot. |

## #918 테스트/실증 계획

source tests는 restricted external /32 skip와 timeout cleanup 기반만 다룬다. 다음은 wrapper/component 수준에서 추가한다.

- wildcard, local-contained CIDR, comma-multi CIDR, IPv4 no-local source의 정확한 결과를 구분. malformed/local-address discovery failure를 ACL skip로 위장하지 않음.
- DBus success에는 matching Export_Id/native config/PID+owned socket 확인 후 전체 mount 호출0. 같은 service의 unrelated export permission/timeout은 변경 대상 ACL job과 분리.
- config parser/process/listener/export inventory 실패는 skip으로 덮지 않음. endpoint별 fault 영향을 제한하고 #892 이전 LKG 복구가 source/journal 증거를 반환.
- 실제 허용/비허용 client NFSv4와dual NFSv3 RW 및 같은 open session/다른export PID 보존은 별도 disposable matrix에서 수행. 원격 mutation은 부모 추가 schedule/fixture 승인을 기다림.

## #914 테스트/실증 계획

- 로컬 actual boot loop의 NFS firstfail/SMBsuccess 실험을 protocolcheckpoint 회귀로 변경: NFS2/SMB1이어야함.
- fake clock/workload1/10/100 ×v4/dual ×single/multiport에서 probe/retry/cleanup 전체 deadline과정확한 structuredfinal을 검증. D orphan child는 LKG/success로 승격되지 않음.
- success protocol hash변경/새bootId에는 checkpoint를무효화하고 identity unchanged에는no duplicateapply. rendered import/boot에서도같은contract 적용.
- daemon-ready와probe-pending 중 monitor/API가상태를유지, QGA/MGT접속단절로guestboot 작업을중단하지않음. 실제100export coldboot/서비스I/O/reboot는 미수행으로 유지.

## #895 source failure fixtures

- Running partial/Stopped power-loss partial/Destroyed VM but pending marker: stop/expunge/DELETE/PRESERVE 모두 before-detach 차단, provider delete/destroy/DAOremove0.
- completed trusted journal만 allow, unknown/unsupported observation은보존. recovery-required desired writer와Root/runtime active writer의공통범위검증.
- planned idsV1/V2 중V1detach성공/V2실패: VMexpunge/volumeDestroy0, approvedUUID/owner/domain/zone/pool/size intact, retry는V1nullattachment을허용하고 V2부터완료.
- after-review UUID/owner/zone/pool/size/type replacement/new attached DATA/missing DATA/foreignVM은freshplan 없이 deny. approval policy와currentpolicy불일치도deny.
- DELETE V1success/V2deletefail after VMexpunge: includingRemoved V1exacttuple/terminalstate를읽고retry하며PRESERVE로되돌림이나foreigncleanup은없음.
- audit insert 실패/COMPLETE audit 실패/DBremove 실패 각각provider 실행순서와retry state를검증. metadata 행의시각·actor·policy·exact volumeUUID 목록을API로검증.
- non-owner/root/project 접근은 QGA/lock/provider sideeffect 전에checkAccess. accountID프로젝트분리와zone/domain의preserve를실제disposable권한matrix로확인.

## 테스트 범위와 조건부 상태

NFS WIP는 kernel owner가 source보완중이고 live원본의daemon/IP/DATA 변경0이다. #895 신규identity helper는scratch13unit PASS이며repo복사/SharedFS wiring은현재Maven pinfreeze해제뒤Root owner가진행한다. #910 Manager는직접편집하지않았다. 현재새선별 source/실제 release 모두AD source와독립해 ready-conditional로관리하며 'deferred라완료'로표시하지않는다. 최종 #1275 표준화는모든기능완료후착수직전전체작업중단·사용자보고·추가지시대기조건이다.

## Kernel WIP 후속 정정

초기 legacy-loop 실험의 SMB2회 결과를 kernel owner가 source per-protocol protectedcheckpoint로 보완했다. bootId+각savedfileSHA+APPLYING/VERIFIED/FAILED를기록하고 NFS firstfail/nextsuccess에서이미VERIFIED SMB는재apply하지않는다. 같은actualwhile-loop를stub에서실행해NFS2/SMB1/exit0 및desiredfile변경시receipt무효화unit을통과했다고보고했다. liveboot/실100export/총bootdeadline/renderedcoldreadiness 완료증거가아니므로해당후속gate는남는다.

이슈 #895 scratch identityhelper는freeze/requireCurrent API와owner/domain/zone scope를포함하며13unit PASS다. prototype2파일은현재Mavenpinfreeze기간repo복사하지않았다. sharedservice wiring/persistentfmt marker 및unresolvedwriterDAOgate는Root owner가별도연결한다. helper만으로Stopped partial보호완료를주장하지않는다.
