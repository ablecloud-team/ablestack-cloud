# Epic #898 인수 게이트 감사 — 시간대별 기록

이 문서는 초기 감사부터 이어진 시간대별 기록이다. 아래의 838 HEAD·09:50 상태와 당시 구현 누락은 **역사적 스냅샷**이며 현재 상태로 읽지 않는다. 최신 상태와 실제 배포·source-only 검증을 먼저 요약한다.

현재 통합 snapshot은 [20261008-current-progress.md](20261008-current-progress.md)이다. 23:32 KST 최신 source614 tests/102 classes PASS는 source freeze·배포 검토 단계이며 actual379+#1333과 구분한다. #910 volume-only source 준비의 actual BLOCKED는 당시 백엔드의 volume-unqualified relative 비교가 원인이고 native prepare/update는0이다. Kubernetes 요청3개는 사용자 철회로 범위에서 제외한다.

- Namespace3hunks+3tests만1dad2501b5cf3ee4528389ef73a7b61181cf6678 별도source pin/push, exact narrow reactor 진행/live0이다. 전체614 WIP는 pin0이며 profile snapshot이 renderedGeneration을 덮어쓰는 P1 및 native commit→DBsave crash gap 보완 전 actual all4 HOLD다. Native27 crypto/POSIX/SERVICE src257+130도guard검토/pin0/live0이며 ROOTswap0/AD인수0/최종1275착수0을 유지한다.
- AD는 사용자 승인 후 작업이 재개됐다. Client SID 일반화·고유 SID/GUID 및 시각 정정은 실제 확인했지만 OOBE 사용자 완료 회신·정상 AD 가입·SMB AD·POSIX AD 전체 인수는 남아 있다. AD 보류를 완료로 간주하지 않는다.
- 최종 UI#1275 표준화는 마지막이다. 착수 직전 전체 작업을 중단하고 사용자에게 보고·추가 지시를 기다리는 경계를 유지한다. 현재 기능 UI 검증은 별도이다.
- 실제 VM50은 기본240/alias241·두 SMB listener·DATA/SID 보존과 정식 인증/SS·NN child delete/NUMERIC 표시를 검증했다. signed7b86 v2(CLI01b8...) upgrade 후 명시적 UI policy rev2 재적용과 cold boot c53e1cbb의 reconcile exit0·protected receipt scope/mtime·DATA/SID/NIC 보존을 확인했다. NN 부모 export 외부 read-only 재조회에서도 UID/GID1001 및 기존 inode/hash가 보존됐다. NS/SN named ACL cross-read는 Ganesha4.3 VFS에서 EACCES로 실패했으며 새 ACL 지원 package/build가 필요하다.
- 최신 VM50 signed38a4(CLI99c1...)는 POSIX inspect의 postApplyReceiptSupported=true를 실제 반환하며 GEN38/fe812·bootc53·SMB/NFS PID·receipt mtime·원래 DATA/SID/NIC가 유지됐다. NEW20GiB SPARSE7b4fae44는 정상 할당·metadata 경로 회귀 이후 정규 NFS pipeline으로 새XFS5b11/formatter receipt1회 및 NN 외부 write/fsync/cross-read까지 확인했다. 원래DATA 포맷0이며 volume-only source 준비가 서버의 volume-root를 제외한 overlap 비교로 BLOCKED돼 해당 update negative/positive는 미완료다.
- partial VM51/021b SPARSE10TiB는 filesystemHealthy=false/mkfs-in-progress 증거로 보존한다. 포맷·삭제·detach·reboot0이며 원래39/41/49도 보존한다. pool factor4 guard의 finally 복구와 정확한 새10TiB capacity 승인은 미완료다. 새 디스크는 모두 SPARSE/FAT만 허용한다.
- Runtime 자원 제어의 begin/verify/finish 연결과 10개 회귀를 포함한 direct63 PASS/561 reactor 이후 자원·NFS 의존성 후속 direct72 PASS 및 정상 Checkstyle reactor585 tests/97 classes PASS를 확인했다. 이는 source 증거이다. 실제 관리 서버는379 선택 오버레이+#1333 Volume13 hotfix와 legacy b38 runtime family가 섞인 구성이며 global/per-instance policy OFF, native lease/drain 실제 수행0이다. source 통과·논리적 자원 임대·전체 기능 완료를 구분한다.

## 초기 감사 스냅샷 — 2026-10-08 09:50 KST

전체 union 29개를 원문·현재 GitHub 코멘트·소스·검증 문서와 대조했다. #911은 기존 CLOSED 상태이며 #902와 다른 이슈의 AD 인증 부분만 사용자 요청으로 보류한다. 나머지 이슈는 부분 증거를 전체 완료로 확대하지 않는다.

감사 소스 기준 HEAD는 `838a60aa723de410da688cfc17a8e0bee18131d6`, local base는 부모가 확인한 `c169d9a203f49ce07e038297873bc3c24cd8ffb4`이다. 병렬 ROOT/kernel/UI WIP는 아직 해당 HEAD에 포함되지 않으므로 그 이후 빌드·배포는 별도로 검증해야 한다. 이 문서를 쓰는 감사 작업은 source/API/schema/native/UI를 편집하지 않았다.

2026-10-08 09:50 KST API 35개 중 34개가 정상 응답했다. 1개 `listSharedFS` 이름 오류(432)는 정식 `listSharedFileSystems`로 교정해 추가 조회 성공했다. 다섯 SharedFS는 Ready/instance Running/health ok이며 활성 프로토콜은 NFS/SMB뿐이다. 모든 SMB는 wildcard `0.0.0.0:445` 단일 행이다. 모든 기존 FS의 networkmode는 DHCP이므로 STATIC 게이트의 증거로 쓰지 않는다.

현재 clone05의 ACTIVE_LKG는 `af7da861-b5cc-4ed5-a224-3f1a7e1985bb`, desired/runtime revision 9, operation `25914662-9b03-4b34-a37c-03b64c244836`, generation SHA-256 `88964cf84c63637f52736d8f2f1ba08350098c8950efd9245d11e36420e72f8e`이다. current kernel은 6.1.0-53, DH-HMAC-CHAP capability false다. 전체 프로토콜 데이터 경로가 정상이라는 뜻으로 해석하지 않는다.

원본 API·현재 이슈 원문/코멘트의 비밀 필드를 제거한 증거는 WSL scratch `/root/work/epic898-preparation/acceptance-audit/`에 저장했다. 주요 파일은 `read-api-20261008.json`, `fixture-inventory-20261008.json`, `issue-live-index.json`, `acceptance-ledger.json`이다. 비밀번호·API session/token·signing private key는 파일에 저장하지 않는다.

## 구현 누락과 연결 지점

- #897: `DesiredStateChange.Runtime`와 operation VO/API에는 cancel/drain/quiesce 계약이 없다. Manager operation preflight는 빈 payload로 고정 최소 MemAvailable256MiB/staging100MiB만 확인한다. 예상 작업량·load·active session·resource reservation을 검사하지 않는다. SyncQueue/GlobalLock/flock/HB를 실제로 통과한 증거와 분리한다.
- #909: `StorageServiceConfiguration.plan`은 initial mapping의 NEW만 계획 UUID로 치환한다. Manager `preflightConfigurationAdditionalVolumes`는 추가 mapping을 기존 Ready/unattached UUID로만 해석하며 prepare는 attach만 한다. 추가 NEW allocation·provenance·idempotent cleanup이 없다.
- #892: native Generation.files/digest는 persisted desired JSON을 묶어 checksum을 만들고 current.json을 원자 교체한다. 네 프로토콜 rendered 파일/configfs의 generation별 stage/parse/atomic activation은 별도 구현 대상이다.
- #894: 원문은 NO_ROOT의 0:0/보수적 mode를 **제안**하고 기존 owner/mode 대비 preview·재귀 기본 OFF·별도 확인을 요구한다. 기존 데이터의 자동 chown는 요구하지 않는다. legacy `apply_posix_permissions`는 owner/mode 값만 있으면 chown/chmod하며 recursive에서 path 기반 os.walk를 사용한다. 기존 경로 승인과 안전한 FD·symlink/mount 경계가 필요하다.
- #900: Manager의 SMB endpoint create/reuse/list/delete와 listener 배열은 존재한다. native는 interfaces를 렌더하고 reload-config만 요청하며 모든 요청 IP의 NIC/수신 socket을 검증하지 않고 success를 반환한다. 두 번째 IP의 socket binding을 실제 시험해 보완할 필요가 있다.
- #974: 포맷 deadline/journal과 get preparation 조회는 존재한다. 명시적인 resume API·DB/UI operation 연결과 실제 대용량/partial-format 중단 시험을 따로 완료해야 한다.

## 29개 이슈별 판정

| 이슈 | 현재 인수 범위 | 실제 확인 및 구현 | 남은 게이트 |
| --- | --- | --- | --- |
| #911 | 기존 CLOSED 유지 | 기존 #925/22.x 및 릴리즈 완료 기록. 13번 서명 runtime 반복 upgrade/rollback 재사용. | 신규 최종 template/runtime/RPM 동일 source 릴리즈와 all4proto 회귀는 #924/#920 통합 게이트로 추적. |
| #1269 | 주요 경로 실증·최종 회귀 대기 | 15초 bounded scoped reads/부분 오류 유지/stale 격리; 원본+시험 UI 직접진입·지연·실패·이력 자동갱신 증거. | 최종 모든 탭·모달 추가 후 unmount/재진입/기존세션/라이트·다크/작은화면·키보드 재검증 및 통합 PR. |
| #924 | 부분 구현/실증 | REGISTERED→VERIFIED→AVAILABLE 실제 UI/서명·hash; Signed feature 손실 차단 및 여러 runtime 업그레이드. | manager/agent/template 최소·최대 범위 및 consumer compatibility UI; disabled/deprecated/revoked/delete reference/권한·변조 전체; 최신 full CI/template/RPM/signing. |
| #892 | 부분 구현/실증·구현 누락 | DesiredStateChange snapshot/Ready rollback/단일 writer/native9 IN_SYNC; SMB 변경 후 promoter fault 및 VERIFYING MGT crash 자동 ROLLED_BACK. | rendered 전체 generation stage/parse/atomic activation 미구현; NFS/SMB/iSCSI/NVMe create/update/delete fault + retry·ROOT/guest reboot phase + rollback failure manual recovery. |
| #897 | 부분 구현/실증·구현 누락 | SyncQueue/GlobalLock/native flock/HB·UTC age 및 guest busy 차단; VERIFYING 중 MGT 재시작 자동복구. | 예상 작업량·load·세션·메모리 예약/사전 scale-up 연계; quiesce/drain·cancel API/phase·공정 대기·guest journal resume; 단계별 guest/MGT reboot/OOM/QGA 단절. |
| #974 | deadline/journal 기반·실증 누락 | probe/format deadline 분리, volume identity/signature·formatStarted fail-closed, durable operation journal; 작은 XFS 기존FS 재연결 증거. | get preparation 조회만 존재: 명시 resume/API·DB/UI 연결 필요. 10TiB delay>20초 XFS/ext4 thin/normal, formatter 후 응답손실, reboot/partialFS/UUID·foreign·duplicate/job 안전게이트. |
| #913 | source 지원·전용 STATIC 실증 진행 | MAC 기반 QGA static helper, DHCP unit mask/해당 dhclient 종료, boot ordering 및 gateway validator. 기존 5개 서비스는 DHCP라 STATIC 증거 아님. | NEW static-smb-audit-06 생성 승인 후 ARP/DB 미점유 확인. 생성/재부팅 default route, DHCP 충돌·renewal, no gateway, 다른 NIC명, template일치·서비스복구. |
| #914 | 부분 구현/소규모 실증 | bounded endpoint readiness/probe/cleanup 및 소규모 복수 NFS+SMB reboot 실증. | 1/10/100 exports coldboot, V4/dual+multiIP/port, mount/umount hang·허용실패, SMB apply 한회, 내부deadline<systemd, guest독립완료+monitor/API/UI. |
| #918 | 제한 ACL 실제 경로 실증 | 허용 .9/32 NFS read/write 및 비허용 .165 거절; own IP 자동 추가 없이 Ganesha/listener 유지·reboot. | V3 dual, wildcard/CIDR local source, DBus 성공 시 전체probe 생략, 관계없는export timeout·hang/fallback/configparse/process/listener fail-closed·open-session. |
| #895 | PRESERVE 주요 수명주기 실증 | 실제 UI soft destroy→recover→start; expunge 후 DATA ca9b… 보존·새VM 재연결, 동일FSUUID/hash. | NEW disposable DELETE·다중DATA·프로젝트/외국계정·detach/cleanup 실패+재시도·감사시각. preserve 실패가 delete로 바뀌지 않는 실제 확인. |
| #909 | 광범위 부분 실증·구현 누락 | backup/download/upload+checksum·RESTORE_EXISTING KEEP·single NEW initial clone/local SMB immediateauth·LKG CAS·DB fault/MGT crash·Stopped UNAVAILABLE/15권한·one-use/expiry. | 추가 NEW volume allocation/provenance 미구현. changed restore all4proto+통합 실제I/O, multiNEW, resource mapping/unknown preserve, malformed archive/large limits/version negatives, retention/delete/expiry/chunk/재시작 lifecycle. |
| #920 | 기반 구현+진행 중 WIP | sameVM ROOT retain/stage/swap helper, DB model/temp constraints, phase engine/catalog/compat/topology/lifecycle/planner 30개 및 native adopt/align82개. | 실제 API controller/Runtime wiring/productionDDL/target auth template/buildboot/같은VM ROOT 교체/identity+allDATA+NIC 보존/phase실패rollback/restartresume/retention-finalize/UI 아직 인수 전. |
| #900 | source endpoint 지원·실증 시작 | Manager SMB exact find/create/reuse/list/delete, payload listeners[] 및 interface rendering. 현재 모든 활성SMB는 wildcard 단일 행. | NEW fixture A240→B241→sameB→deleteB+IP공유→reboot 실제검증. Native 성공반환에 모든IP NIC/socket readback 누락; reload만으로 신규socket bind 되는지 검증/보완 필요. |
| #896 | 실제 온라인 확장 UI 실증 | 2C4GB→4C8GB 실제 UI, 작은 offering/downscale 비활성 및 이유 표시. | 고정CPU/RAM·min·HA·dynamic·global·template·hypervisor 제한사유별 표시와 preflight refresh/부족·동시·권한 회귀. |
| #891 | 주요 성공/실패 복구 실증 | 첫 hotplug 반영실패 cold rollback 기록 및 수정후 2C4GB→4C8GB/동일boot+Ganesha PID·898 I/O 오류0. | non-dynamic/global/template/hypervisor 부적합 생성 차단 실제 negative, concurrent scale/config/runtime, 자원부족·guest 반영실패 조합 및 새template회귀. |
| #904 | 기존 XFS 재사용 실증 | 실제 UI EXISTING diskoffering 무선택, ca9b… 기존20GiB/MOUNTED_EXISTING/동일FSUUID/hash; bootstrap DATA 추가 없음. | EXT4 existing, blank/in-use/foreign/project/deleted/changed owner, initattach/mount 실패 cleanup/PRESERVE·재시도, NEW 기존 회귀. |
| #905 | 고유 1볼륨 다중참조 실증 | list/cache projection unique backing; 실제 volume 1개를 여러share 참조시 API capacity·fresh/stale, alias peerPath 보완. | 독립2+DATA/file+block 혼합 사용량 합계·unobserved/stale/snapshot 불일치·조회 실패/TTL 및 목록/상세 actual UI 갱신. |
| #894 | 원문 핵심 preset 미구현 | root/all squash fields 및 공통 POSIX preview/비재귀 보존 실증. | NO_ROOT 0:0/보수적 권장preset, ALL_SQUASH·RO별 보존 안내, 기존stat→예상 owner/mode+명시apply 승인/재귀·symlink 경계. 기존 legacy root 기본자동chown 위험 구분. |
| #906 | NUMERIC 주요 경로 실증 | 두 Linux클라이언트 UID24567:GID24568, 두포트2049/2050, 실제 UI NUMERIC/CONSISTENT 및 reboot FSUUID/hash. | NAME_DOMAIN·3 squash·RO/RW·v4/dual matrix, 모든endpoint 실패rollback. 기존 실패주입은 PATH에 안 걸렸으므로 성공계수 금지. 단계전환/drain도 별도. |
| #903 | 공통 동일경로 정책 실증 | NFS+SMB 동일 policy UUID/rev·UID/GID1001001/mode2775·상호I/O·기존파일hash/inode·reboot. | legacy독립정책 migration·충돌preview·effective/runtime drift·적용/rollback실패·기존POSIX ACL/fullpermission matrix. |
| #915 | 부모 상속 주요 경로 실증 | 실제 UI INHERIT_PARENT_OWNER/setgid; 새파일/디렉토리/중첩+reboot1001001:1001001/2775, 기존파일 보존 및 열린handle. | 다른부모 독립상속·READ_ONLY/missingACL·default복귀·FORCED/common 충돌/실패주입·migration. AD portion만보류. |
| #916 | 단일 정책 주요 경로 실증 | Pinned directory POSIX preview→apply; owner/setgid/access/defaultACL·NFS/SMB공통 참조·기존하위 inode/hash. | 다중policy/outsidepath/symlink/다른mount/정책소유 path 경계 실제, root/no_root/all/numeric/defaultACL, 삭제DATA보존·drift/reboot/failure/migration. |
| #919 | 생성 mode 주요 경로 실증 | 0775 새파일/디렉토리 실제stat·defaultACL 충돌preflight·893 open FD fsync 오류0/max0.0495초·reboot. | 실제 render/testparm/reload fault rollback/PID/기존session·다른share격리, 기본0660/0770 및 inheritPermissions+mask/force조합·재생성template/다크 UI. |
| #910 | NFS 중첩 및 교차 경로 실증 | 같은DATA NFS부모/자식 independentCIDR I/O, NFS+SMB공통 경로 재사용 및 parent delete BLOCKED 이력. | SMB부모/SMB자식·양방향 교차 중첩 actual, child삭제directory/file보존, crossvolume/..../절대/symlink/다른mount 차단, detach 보호·reboot·경로표시. |
| #908 | 로컬 강제 identity 광범위 실증 | 2local auth file/dir/nested1001001; RO read/write deny·noauth/missingACL/POSIXdeny·session원사용자·reboot·mode exit+reenable·oldinode/hash. | foreignUID/GIDcollision·multipleconsumer 관리identity수명주기·native render/reload fault/autorecovery + policy conflict. AD 사용자 portion만보류. |
| #902 | SMB AD 보류 | 사용자 지시로 AD join/DNS/SPN/Kerberos/aliases 인수는 보류하며 완료로 표시하지 않음. | 다른 기능의 local SMB나 NFS/block 테스트에 AD 대기 사유를 전파하지 않음. |
| #907 | IPv4 account AND source 실제 실증 | 허용CIDR localauth I/O·비허용source 동일계정deny·allowedsource noaccountdeny·다른share독립/재부팅·DB/runtime/UI. | NO rules 호환·guest noallow deny·multiSMBendpoint(#900), IPv6지원 capability reject·CIDR parse matrix·renderfail rollback/최종 UI. |
| #901 | 행 기반 구조/포트 실증 | NFS2049/2050 listener rows, SMB445가NFSselector에 섞이는 결함 수정 및 actual UI 확인. | wildcard/dedicated/multiport/dual/IPv6long tooltip·linkedexports·fixedcolumns가로scroll·다크actual; #1275 최종표통합. |
| #1275 | 최종 WAITING | VM 세로tab/MoldDialog/좌측toolbar primary·ReloadOutlined/wide-last 사용자표준 및 별도목업 검증자료 존재. | 선행 nonAD 기능/API/field 확정 뒤11tabs+모든dialog actual; header/footerfixed/bodyonlyscroll, desktop/small/light/dark/ko/en/keyboard/focus/empty/partial/error/regression. |

## 12:12 KST 후속 실증과 source/runtime 분리

앞 표는 초기 838a 소스 기준의 미완료 게이트다. 병렬 구현과 현장 실증이 진행돼 아래 항목은 초기 상태에서 전진했다. 초기 표의 미구현 표현을 현재 HEAD 전체 상태로 해석하지 않는다.

| 이슈 | 새로 확인한 실제 게이트 | 현재 남은 검증 |
| --- | --- | --- |
| #913 | NEW VM50 STATIC240/16/gateway 및 추가 B241, NEW VM51 STATIC242/16/gateway의 생성·재부팅·실제 route 보존. live bff의 agent STATIC primary guard 배포 후 원본5+50 Running/Ready/health 정상 및 VM50 A240/B241 투영 정상. | no gateway·DHCP renewal conflict·다른 NIC·모든 실패 복구 matrix, 최종 전체 source와 runtime 일치. |
| #900 | 이전 93a runtime에서 B Ready인데 소켓이 없는 falseReady 실제 재현. Debian Samba4.17.12 reload로 listener가 생기지 않음 확인. B 전용 master prototype에서 A held FD 868회 오류0, same local auth A/B I/O, cross-endpoint byte-range lock, B만 stop 후 A PID/열린 handle/hash 보존 통과. | kernel agent의 formal managed endpoint source/signed runtime 배포 진행. formal API enable/disable/delete·reapply·reboot·held handle·auth·lock·fault actual 인수는 prototype과 별도로 필요. |
| #974 | NEW fixed10TiB XFS/ext4 각각 실제25초 formatter 중단 후 69.176/71.563초 완료, 외부 NFS/SMB I/O 및 같은 credential을 유지한 reboot auth/data/FSUUID/alignment 보존. parent 실제 UI hidden backing 표 보완 후 NFS/SMB 두 표 UUID/10.0TiB/ext4/currentdevice/EXACT/Ready 확인. | 정식 resume·response loss·formatter/MGT/guest phase interruption·partial/foreign/wrong UUID·UI progress/manual recovery·일반 block storage 전체 게이트는 남음. source5de32797의 read-only host queue 우회와 fresh currentIdentity 보완은 아직 live 실증과 구분. |
| #905 | VM51의 기본20GiB와 실제10TiB 독립 DATA를 동시에 연결해 total10.02TiB API/UI를 확인. 숨김 backing 조회 누락을 scope bounded known UUID/RootAdmin fallback으로 보완해 ext4 실제 UI 확인. | file+block 사용량/관측 실패/TTL·stale/full projection matrix는 남음. |
| #920 | parent가 ROOT production table+5 columns/idempotent retry, 139 backend tests와 controller/class 배포 bff/HTTP200/agents UpEnabled를 완료. Root catalog/history read-only API 응답 확인. | actual ROOT swap은 아직 0이며 same VM/모든 DATA+identity+NIC·phase failure rollback/resume·retention·all4proto/UI actual 게이트가 남음. |

큰 DATA 두 개는 각각 소유한 share 삭제→정확한 UUID detach→deleteVolume해 DB Expunged/removed를 확인했다. 기본 20GiB DATA와 fixture51은 유지한다. exact pool 임시 factor4 override는 resetConfiguration으로 제거했고 DB override 부재/API 상속1.0/global factor1/max40000/custom4096/min1을 확인했다. 큰 논리 용량은 nondestroyed30개 합1,560,910,233,600 bytes로 원복됐다. capacity 집계 표시는 일시 갱신 지연 후 12:12 KST API에서 1,560,911,232,288 bytes로 복귀했다. 원래 DATA39/41/49 및 SMB fixture50는 이 시험에서 변경하지 않았다.

관련 상세 증거는 `20261008-static-smb-disposable.md`, `20261008-large-format-actual.md`, `20261008-multinew-volume-design.md`에 나뉜다. #974 진행 코멘트는 `6051296801`, `6051385328`이며 어떤 미완료 이슈도 닫지 않았다.

이슈 #909 후속으로 부모가 독립 planner/provenance receipt helper 2개와 신규 unit만 구현하도록 위임했다. ROOT compile freeze 동안 scratch prototype만 작성했으며 JUnit 30개 PASS다. 실제 repo Java 복사·Manager/Configuration integration·Cloud multiNEW all4proto 실증은 아직 없으므로 완료 상태는 바꾸지 않았다. 인터페이스와 파일 소유 범위는 `20261008-multinew-prototype.md`에 기록한다.

사용자 추가 인수 기준으로 모든 NEW 디스크 생성은 SPARSE 또는 FAT만 허용한다. 과거 THIN 10TiB XFS/ext4 결과는 역사로 보존하지만 현재 #974 완료 게이트에서 제외했다. 새 offering/actual volume/allocation receipt의 provisioning과 실제 qemu preallocation=metadata 또는full 증거를 대조한다. 기존 DATA는 재포맷하지 않으며 fixture51에 새 SPARSE 10TiB DATA만 추가해 순차 재시험한다. 독립 multiNEW helper에도 planned/actual SPARSE/FAT 검증과 THIN/unknown 차단을 추가해 32개 unit을 통과했다.

사용자 상세 화면 구조 변경에 따라 최종 실제 UI 인수는 정보 탭과 작업/백업·복원/업그레이드 탭을 분리한 11개 세로 탭 기준이다. 볼륨 준비/진행/이력은 새 작업 탭에서 검증하고 상세 정보 탭에 작업 버튼을 혼합하지 않는다.

사용자 AD 환경 준비로 #902 및 다른 이슈의 AD 부분 보류를 해제했다. 현재 상태는 환경 준비/조사 재개이며 실제 join/auth/SPN/Kerberos/aliases 인수 완료가 아니다. ADSvr192.168.16.2는 ablestack.local/ABLESTACK의 primaryDC이며 NTDS/DNS/Kdc/Netlogon Running, AD-integrated DNS zones 확인을 read-only QGA로 마쳤다. Client192.168.16.11은 WORKGROUP/PartOfDomain=false, DNS는VR.1/8.8.8.8이라 DC DNS 설정과 join/reboot 검증이 필요하다. FORMAT critical 중 network/domain mutation은 보류하고 이후 승인된 범위로만 수행한다. 새 service의 ROOT/DATA도 SPARSE/FAT 필수다.

최종 #1275 UI 표준화는 마지막 단계다. 모든 다른 기능/AD/실증/배포 게이트가 완료되면 #1275를 OPEN으로 유지하고, **최종 UI 표준화 착수 직전에 전체 작업을 중단해 사용자에게 보고하고 추가 지시를 기다린다**. 현재 요청받은 기능 UI/정보와 작업 탭 분리/API와 실제 UI 검증은 계속한다. 최종 스타일/버튼 순서/대화상자 표준/11개 탭 전체 QA를 그 경계 전에 착수하지 않는다.

## SPARSE fault 및 AD 실제 후속

이슈 #974 새 SPARSE10TiB 021b는 실제 preallocation=metadata/Cloud SPARSE/actual qcow2 backing-none를 확인하고 정확한 formatter를60초 일시 중단했다. 실제 UI와 read-only API는 writer 중 FORMATTING 관측을 통과했다. 다만 XFS discard 뒤 log ZERO_RANGE의 D-state와1500초 timeout을 재현해 MGT error530/RECOVERY_REQUIRED, native TIMED_OUT_PENDING_RECONCILE로 남았다. fresh child 없음/writerIdle=true/부분 XFS UUID만 확인했으며 정상 완료로 인정하지 않는다. 삭제·mount·새 포맷·재부팅을 하지 않았고 ext4 SPARSE와 명시 resume는 아직 남는다. API/DB/libvirt/guest의021b attachment는 유지되므로 UI20GiB로 감소한 현상은 share rollback 뒤 hidden-unreferenced projection 누락이다. pool factor4 임시 override는 보존 중이며 recovery 계획과 finally 상속1 원복이 미완료다.

이슈 #902 Client 자신의 DNS만192.168.16.2로 바꾸고 SRV/DC discovery를 확인했다. 실제 join은0x52e LOGON_FAILURE로 실패해 WORKGROUP/reboot0을 보존한다. DC SYSTEM readonly RID500은 Administrator/UPN=null/Enabled=true/LockedOut=false/PasswordExpired=false, badLogonCount0, lockoutThreshold0이다. Runtime env→host→guest stdin 문자열 길이와 동일성 bool은 모두 일치했다. 원문 credential/NT/hash는 출력·저장하지 않았으며 추가 인증 반복이나 비밀번호 reset을 하지 않는다. 도메인 가입·Kerberos·SPN/alias·SMB AD auth 인수는 미완료다.

multiNEW 순수 realization binder를 포함한 독립 helper tests36개와 version compatibility tests15개가 direct PASS다. 실제 Manager adapter/root replay version wiring 및 all-protocol Cloud 실증 완료와 구분한다. RuntimeImpl checkpoint는 아직 sourceRootBinding/fresh consumer/installedLKG 증거를 반환하지 않아 production wiring 보완이 필요하다.

AD 자격 증명 추가 원인 판별에서 승인된 DC SYSTEM LogonUserW(user=Administrator/domain=ABLESTACK/NETWORK3)1회가 성공했다(error0/tokenClosed=true). Client exact SAM1회 retry도 GetNetworkCredential domain/user/password 일치 bool=true이나0x52e로 실패했다. fresh DC4776의 NTLM credential validation은status0이고 같은 시각4625 handshake failure/6167 machine ID mismatch가 있다. DC domainSID와 Client local RID500 SID prefix가 같고 MachineGuid도 같다. 이는 Microsoft 공식 [KB5070568](https://support.microsoft.com/en-au/servicing/os/windows/docs/2025/10/kerberos-and-ntlm-authentication-failures-due-to-duplicate-sids)의 중복SID WindowsServer2025 인증 차단과 일치하는 강한 원인 증거이며 제공 암호 오류로 해석하지 않는다. Client 일반화/SID변경/재설치는 아직 하지 않았다. 두Windows 실제 UTC도 Cloud 시간보다 약16시간 앞서 있어 향후 Linux AD Kerberos 시간 동기 게이트에 별도 영향한다. 현재 원문/NT/hash 미출력·미저장, 추가 auth retry와 Client reboot0을 유지한다.

## 15:38 KST source·실증 후속

이슈 #924 실제 RuntimeImpl wiring은 cb224f281a2e938b9284449f9cf371c66f047fa5/385 reactor tests에 포함돼 통과했다. 자체 관련44개(Replay17/freshConsumer12/version15)도 실제 reactor에서 통과했다. source checkpoint는 실제 ROOT UUID/id/VM/template/owner/zone 바인딩, fresh manager/agent/protected guest platform 관측, approvedInstalledLkg, signed installed-code readback을 저장한다. 새 활성화는 preflight와 ACTIVATE 직전에 signed manifest/fresh versions를 재검사하며 previous 예외는 원래 protected source pin/ROOT/LKG/features/명시 UNKNOWN 관측에만 묶인다. capabilities의 consumerobservation JSON producer도 연결했다. formatter child 없음/WRITER_IDLE만으로 incomplete journal을 넘지 않도록 attached DATA의 bounded status+StorageFormatterLifecycleGate를 bootstrap/staging/activate/rollback 전에 확인한다. CAP/READBACK은 읽기 전용 관측으로 남는다. **live MGT는 현재 b38d033c485(296tests)이며 strictversion385는 아직 미배포**다. source49/50 d758 signed bridge 실제 완료 이후 fresh fulltemplate/first ROOT 검증 전에 strict live rollout을 하지 않는다.

이슈 #909 logical NVMe subsystem 후속은 Plan/PlanTest 두 파일만 수정했다. NVME_OF + config object.type=subsystem + absent/JsonNull volumeUuid만 logical container로 skip하고 실제 namespace DATA는 BLOCK_RAW를 유지한다. null namespace/ISCSI/unknown·malformed subtype/subsystem에 예상치 않은 DATA 바인딩/미사용 DATA를 logical container로 소비한 것으로 처리하는 경우는 차단한다. planner/allocation 직접40개(기존36+4) 통과이며 production multiNEW all4protocol Cloud I/O/재시작/lifecycle 인수 완료와 구분한다.

SPARSE 전용 fixed SO metadata2개를 정상 API로 생성했다(실제 disk allocation0/기존 SO 수정0). 2C4GB fc808c8b-00fa-42d2-98b6-a082f12c347f/linked ROOTDO90b3f7d0-bf5b-46dc-bb6c-d46c57fa8774, 4C8GB57c431e2-3330-4a1d-a0ff-11cbb5321899/ROOTDO1ae2dee3-dbf8-4626-9664-601a7dbef950다. API/readonly DB join에서 둘 다 SPARSE/shared/writeback/HA/dynamic/무tags/ROOTDO removedNULL을 확인했다. rootdisksize=0의 명시 요청은 API431로 차단돼 해당 필드를 생략해 템플릿 크기 상속을 사용했다. supportsStorageFormatting은 실제 provisioningtype sparse/fat만 허용하는 UI 함수이며 별도 API flag가 아니다.

이슈 #974 승인된 readonly xfs_repair6.1.0 -n/-m512/-P 진단은 exact021b/serial/10TiB/ROOTancestor 제외/unmounted/writerIdle를 재확인한 뒤1회 실행했다. primary superblock의 mkfs-in-progress bit를 실제 검출하고 secondary scan 중120.000520875초 deadline에서 exact PID/startTicks를 pidfd TERM으로 종료했다(exit-15/terminationPending=false/KILL 불필요). fresh child0, target write counters와64KiB headerSHA가 전후 일치, journal/FSUUID 그대로다. filesystemHealthy=false/FORMAT_COMPLETE승격false이며 mount/repair쓰기/-L/mkfs/reboot/detach/delete0을 유지한다. **부분 헤더는 완료가 아님이 실제로 확인됐고 현재 pending volume은 그대로 보존한다.** factor4 임시 override/finally 원복은 아직 미완료다. 상속1 복원 시 pool logical allocated/total=307.75%라 후속 신규 allocation 제약을 함께 추적한다.

부모는 MGT b38 delta 후 실제 UI total10.02TiB 복귀를 확인했다. exact listVolumes는 defaultdisplay scope의 initial5368와 displayvolume=false scope의021b가 모두 VM51/Ready/DATADISK/admin/ROOT임을 확인했다. default/hidden 조회를 합쳐야 backing 준비 목록2개를 표시할 수 있으며 볼륨 detach/delete가 발생한 것은 아니다.

이슈 #902 부모의 실제 브라우저 noVNC 콘솔도 병행했다. 초기 검은 화면은 wake/키 입력 뒤 로그인 화면이 표시됐고 CapsLock 입력 상태 보정 후 기존 제공 자격 증명으로 VM47 Administrator 데스크톱 로그인이 성공했다. QGA local '.' LogonUserW 1회 역시 성공했다. DC Domain SAM 인증과 Client local 인증은 유효하며 중복SID/6167 가입 실패 원인은 별도로 남는다. Sysprep/일반화/재설치 및 Client reboot는 아직 승인 대기·미실행이다. 최종 UI #1275 정지 경계는 그대로 유지한다.

## 16:07 KST 실제 인증·시간 정정 후속

정식 VM50 local SMB는 승인된 synthetic credential update1회 뒤 current passdb의 메모리 비교bool=true였다. guest A/B ×unqualified/qualified(single delimiter)/domain6probe는 모두 **session setup NT_STATUS_LOGON_FAILURE**였고 tree거절과 구분했다. 허용source host13.1 ROmount3forms도CIFS13으로 실패해 heldFD·endpoint lifecycle·reboot는 시작하지 않았다. kernel readonly FD에서 A1291/B67681이 deleted passdb/secrets inode3858/3850을 열고 currentpath1381/1421과 다른 것을 확인했다. canonical TDB atomic replace 후 daemon의 old inode가 남은 원인 증거다. original sentinel SHA/inode16777345/UID:GID1001:1001/mode0660 및 두 PID/startTicks 보존, 자기 client mount0/추가reset0을 확인했다. #900 실제 authenticated I/O gate는 미완료이며 코멘트6054393360에 기록했다.

AD Windows clock 정정은 부모 GO로 양쪽 VM45/47에 timezone Korea Standard Time 및 fresh strictverified hostUTC guest-set-time을 각1회 수행했다. DC1/replicationPartner0, peerUTC3hosts 차이약25ms를 사전 확인했다. 실제setter는둘다 성공했고0/30/60초 재관측의delta는최대4.81ms/RTT약1.1ms로재발0이다. DC ADDS/DNS/KDC/Netlogon/W32Time Running/hostname/domain/IP.2/DNS loopback과Client WORKGROUP/hostname/IP.11/DNS.2+기존IPv6를보존했다. NTPpeer/GPO/보안/SID/암호/서비스restart/VMreboot/티켓purge는0이다. 첫정적placeholder preflight 오류는mutation전에실패·unchanged를확인해수정했고, setter완료후readonlyPS polling이짧았던문제는읽기관측만늘려종료했다. setter를재실행하지않았다. 기존16시간clock차이는해결됐지만 duplicateSID/Sysprep승인대기와AD join/auth gate는여전히남는다.

이슈 #974 readonly no-modify진단의mkfs-in-progress bit/120초종료/targetwrite0·header불변 및filesystemHealthy=false는코멘트6054402213에도반영했다. partial021b/임시poolfactor4 보존과finally원복미완료를유지한다. #1275 최종UI표준화착수직전전체작업중단·사용자보고·추가지시대기경계도변경하지않는다.

사용자의직접Sysprep승인후Client47만SPARSE100GiB fullbackup/virtualcompare/wholeSHA/provenance를완료한뒤Sysprep1회·실제shutdown·원본boot를진행했다. ROOT/NIC API보존은확인됐지만OOBE QGA disconnected로새SID/GUID는아직미관측이다. 부모콘솔handoff후실제고유신원/join/ADauth인수를계속하며완료flags는바꾸지않는다. 상세는20261008-ad-client-generalization.md에기록했다.

16:47 Client QGA재연결후새SID prefix786796763/2696268617/1721076406과MachineGuidc58fa7e3-fb62-4aed-ba5e-88dadd252c6b의고유화를실제확인했다. OOBE/약관·관리자입력은사용자handoff대기라우회·unattend·암호/DNS/hostname/join 추가변경0이다. 새hostname/DHCP DNS초기화가관측됐고완료후원래hostname/DNS.2를복원할계획이다. nativePOSIX AD/capsuleAD transfer에deferred분기가남은것도source검토에서확인해kernel/rootowner에게전달했다. AD전체완료로승격하지않는다.

## 최신 진행 상태: 관리 서버 재배포와 AD 일반화

앞선 시간대의 Sysprep 승인 대기 및 SMB AD 보류 기록은 당시 상태이다. 이후 사용자 승인을 받아 SMB-Client의 100GiB SPARSE ROOT를 별도 백업하고 논리적 디스크 비교 및 체크섬을 검증한 뒤 Sysprep을 1회 수행했다. 새 SID와 MachineGuid가 ADSvr과 다름을 실제 확인했다. 현재 사용자 OOBE 완료 대기이며, 완료 후 DNS·원래 호스트명을 복원하고 AD 가입을 재검증한다. ADSvr 및 원래 SharedFS DATA는 변경하지 않았다.

작업 제어 DAO Spring 등록 누락을 수정한 0c70 관리 서버의 실제 기동과 새 로그인/API를 검증했다. 모듈 439개 및 기존 런타임과 혼합한 48개 테스트가 통과했다. 호스트 3대의 필요한 cloud-api 클래스만 순차 반영한 뒤 7개 인스턴스의 건강 조회가 모두 성공했다. 상세 증빙은 20261008-operation-control-spring-deployment.md에 기록한다. 건강 조회 성공은 SMB 실제 인증이나 partial XFS 건강을 뜻하지 않는다.

신규 기능 UI는 0c70의 독립 git archive에서 생산 빌드 중이다. 작업 제어 및 SMB 인증 복구 UI의 14개 테스트가 통과했으나 실제 브라우저 복구 작업은 아직 수행하지 않았다. 기존 master의 삭제된 인증 DB 열린 파일과 세션·잠금 0 상태를 fresh 확인했다. 새로운 자원 임대 소스는 논리적 여유 임대이며 실제 RAM 예약·드레인 지원 완료로 표시하지 않는다.

최종 UI 표준화 #1275는 기능 구현·검증 완료 뒤 착수 직전에 작업을 중단하고 사용자에게 보고하는 조건을 유지한다. 통합 PR #1271은 진행 중 Draft이며 이 새 기록으로 이슈를 완료 처리하지 않는다.

## 18:27 KST 후속 capacity / 복구 조건

0c70 실제 startup 후 fresh readonly 7 instance health 모두 success/native ok, 3 hosts UpEnabled를 확인했다. 이는 SMB 인증 완료 또는 partial XFS 건강 완료를 뜻하지 않는다. authoritative DB capacity12,556,027,510,048B/total16,319,648,235,520B, factor4, allocatedthreshold0.85 확인으로 정상 새 여유1225GiB를 확정했다. 3TiB 추가는97.15%라 거절 범위이며, 1TiB 순차 보조 시험은 새 ROOT/템플릿 예약 재확인 조건으로 계획한다. 정확한10TiB는 추가 실제capacity/ratio정책 판단이 필요하고 기존 partial 삭제/설정우회0이다. 상세는20261008-capacity-multinew-plan.md에 기록했다.

부모의 SMB identity repair 실제1은 RECOVERY_REQUIRED cb8bb5c9/jobebf709c3로 종료됐으며 rebind 후 A346908/B346911/currentDBalignedtrue는 관측됐지만 COMPLETE가 아니다. 추가reset/auth/clientI/O0 HOLD를 유지한다. 새 postrepair harness는 actual030efd/catalog701c/CLI9e24와 MGT0c70 선택 구성을 넣은 별도 파일/증빙으로 준비했고 이전 d758/b38 증빙은 보존했다. reset전 intent를 독점 저장해 ambiguous response 후 중복reset을 막고 explicitGO 후만 RAM one-reset/guest6/host3/heldFD를 수행한다.

Client47 fresh QGA09:22:52UTC는 OOBEInProgress1/SetupPhase4/msoobe, WORKGROUP/WIN-63TQIUEHE5Q, 새SID/KST/IP.11 보존을 확인했다. 사용자 OOBE완료 회신 전 DNS/hostname/join/암호 mutation0이다. AD는 ready-conditional이며 전체기능 완료/정지로 간주하지 않는다. partial51/021b 보존 및 poolfactor4 guard23325/finally미완료, 최종UI1275 경계는 그대로다.
