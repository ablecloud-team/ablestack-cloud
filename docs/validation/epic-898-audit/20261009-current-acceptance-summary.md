# 2026-10-09 현재 인수 원장 — Epic 898 구현 이슈 29개

이 문서는 현재 판단용 독립 원장이다. 이전 acceptance-ledger/followup-gaps의 시간대별 본문은 역사적 증거이며 아래 현재 상태로 대체해 읽는다. Source test PASS, 실제 배포/실증, 전체 요구 완료를 구분한다. helper나 unit PASS만으로 이슈를 닫지 않는다.

2026-10-09 02:32 KST GitHub readonly 상태는 29개 중 기존 `#911`만 CLOSED, 나머지 28개는 OPEN이다. 감사자가 새로 닫은 이슈는 0개다. Kubernetes 요청 3개는 사용자 철회로 범위에서 제외했다.

## 현재 baseline과 불변 경계

- 최신 source 부분 통합은 1849d3496d0의 정상 Checkstyle 792 tests / 123 classes다. SPN의 host/name·cifs/name과 별도 realm 형식 불일치는 50bc58bc07e에서 수정해 정상 762 테스트로 검증했고, 후속 SERVICE 중지·가입 전 암호화 원본 연결을 780으로 검증했다. native f0dd1962bea는 원본 암호문 재사용·복원 117 테스트를 통과했다. typed JOIN/LEAVE producer와 AD 의미 복원은 아직 남아 있고 live AD 0이다. 수동 retained ROOT는 7064edbdfd8의 source 752/118 및 전용 Runtime 경로를 연결했으나 actual ROOT swap은 0이다. 단계별 historical 결과와 현재 실제 배포를 구분한다.
- 최신 실제 management 는 source 707 의 reviewed mixed 197 classes / JAR SHA ade51548 / PID 1189913 이다. 기존 Runtime 2 classes 는 유지하고 새 strict Runtime 3 classes 는 제외했다. explicit template API 등록·기존 상세 UI 재로그인·7 instance Running / 3 host UpEnabled·recovery 10 rows·7 OFF policy 보존을 확인했다. [배포 기록](20261009-template-selector-management-deployment.md)을 최신 근거로 쓴다. 이전 namespace e99a 와 2026-10-08 17:15:51 UTC readonly snapshot 은 각 시점의 역사적 증거다.
- KVM cache SPARSE 보완 0a10 family 2개는 부모가 3 host에 actual 배포/ABI/hash/원본 VM 보존/API 회귀를 확인했다. 새 template/disk 생성은 아직 0이며 [배포 기록](20261009-kvm-sparse-cache-deployment.md)과 구분한다.
- AD 승인 UI 8178fbc10c8은 104 tests / 8 suites·lint·production build 850파일을 통과해 13번 static 모듈에 반영했다. index f851e2a0…·관리 PID 1189913·config·WEB-INF를 보존했다. Chrome에서 기타 합성 입력 완료 후 이름 불일치·중지 미승인 제출 버튼 비활성 및 취소를 확인했다. 실제 JOIN/LEAVE·미지원 API 제출은 0이다. [실제 UI 부정 증거](../epic-898-ui-20261007/20261009-ad-maintenance-actual-negative-ui.md).
- 과거 fc3e 이미지의 공개 SYSTEMVM SAM seed 존재를 읽기 전용으로 확인했다. 이미지 SHA·size·mtime와 운영 DATA/SID를 보존했으며 NBD·loop·mount를 정리했다. 새 빌드 seed 정리·writer/validator absence 검사와 첫 guest SID 고유성 실제 검증을 진행한다. [읽기 증거](20261009-template-public-sam-seed-readonly.md).
- NVMe 생성 인증 d43ab7c12b9는 48 tests / 5 suites·lint·production build 850파일을 통과해 13번에 적용했다. Chrome에서 지원 미확인 기본 선택의 안내·host NQN 입력 후에도 비활성 인증 스위치·취소·전후 7개를 확인했다. 새 템플릿 생성·fresh capability positive·Cloud 인증/I/O는 아직 남았다. [실제 부정 UI 증거](../epic-898-ui-20261007/20261009-nvme-create-auth-actual-negative-ui.md).
- 생성 템플릿 선택 UI 45c82e189dd는 29 tests / 4 suites, lint 및 production build 850파일을 통과해 실제 static 모듈을 적용했다. Chrome의 SYSTEM 목록·명시 선택·기본값 복귀·취소 및 전후 7개 인스턴스를 확인했다. 신규 VM·디스크 생성 성공은 아직 0이다. [실제 UI 증거](../epic-898-ui-20261007/20261009-create-template-selector-actual-ui.md).
- 새 디스크는 SPARSE/FAT 이상만 허용한다. 원래 39/41/49 및 기존 DATA, VM51의 021b partial SPARSE10TiB는 보존한다. factor4 guard23325의 finally 복구와 정확한 추가10TiB capacity는 미완료다.
- AD는 사용자 환경 준비와 승인 이후 계속 진행 중이다. Client SID 일반화와 시각 정정은 실제 확인했으나 OOBE 사용자 완료 회신과 정상 domain join 및 AD SMB/POSIX 인수는 아직 필요하다. AD를 완료나 정지 상태로 묶지 않는다.
- 최종 UI `#1275`는 마지막 착수 직전 전체 작업 중단·사용자 보고·추가 지시 대기 경계다. 최종 11탭 style/theme/button/dialog/keyboard QA는 0이며 기능 UI와 구분한다.

## 최신 실제 완료 및 실패

1. `#910` volume-only API/UI crossprotocol OFF negative, same-protocol 재바인딩 및 source file/ACL 보존을 실제 확인했다. 원본 파일을 복사했다고 주장하지 않는다. NEW20 NFS child 삭제 API 1회, parent 같은 held FD 596회 write/fsync/read·오류0, 실제 Ganesha restart 및 parent Export_Id 보존, removed pseudo fresh mount ENOENT, 기존 file/data/sentinel 보존도 확인했다.
2. fc3e fresh ISO SPARSE prototype은 build/signature/compressed roundtrip/독립 installed source/POM/CLI·Ganesha5.5.3 ACL self-test·selected-service·cleanup을 통과했다. 원본 source fc3e로 유지한다. Cloud 등록/새 VM 생성/productionFour/AD/retained ROOT 완료는 0 또는 false다.
3. local iSCSI 첫 9a83 실행은 actual kernel6.12/QGA/noNIC/signed runtime readback을 통과했지만 configfs stat→open inode guard에서 첫 정상 apply가 실패했다. login/RAW I/O는0, 정상 rollback/cleanup 및 원본 bfa/Data SHA 보존을 확인했다.
4. 수정82cf의 두 번째 local run은 signed normal update/fresh readback 및 커널/RAW ROOT 제외를 통과했으나 정상 apply의 service restart→targetctl clear/restoreempty 이후 TCP3260이 사라져 실패했다. auth helper 단계 이후 진행했지만 fresh kernel CHAP positive를 확인하지 않았다. login/RAW I/O0, 자동 재apply0이며 당시 실제 서비스 lifecycle 보완이 필요했다. 아래 run3 에서 수정 pin 의 결과를 따로 기록한다.
5. signed b6b4485 의 local iSCSI run3 는 actual kernel 6.12 CHAP·mutual positive 및 각각 4 KiB RAW write/sync/read/fresh reconnect, wrong host·wrong target·no credentials 거절을 통과했다. 총 쓰기는 8,192 B 이며 원본 bfa 는 보존했다. 자기 session·target·backstore·vault entry·QEMU process 를 정상 정리하고 reconcile unit 을 원래 checksum 으로 복원했다. Cloud / UI / production all4 완료는 아니다.
6. NVMe run4 는 같은 SPARSE copy 에서 actual kernel HOST_AUTH / TARGET_AUTH, fabrics 두 인증 옵션, signed b6 readback, ROOT 제외·RAW identity·기존 iSCSI 패턴 보존까지 통과했다. 정상 legacy CLI 의 owned cleanup 계약 부재 P1 때문에 target·secret 전달·RAW 쓰기는 0 으로 멈췄다. 이후 별도 source pin 과 actual 실행으로 전진했으며 아래 run7 결과와 구분한다.
7. NVMe run5 의 첫 apply 는 nonzero / 원인 미확정 / 인증·쓰기 0 으로 정리했다. run6 는 실제 one-way 4 KiB I/O와 정상 owned cleanup 을 통과했으나 reconnect login 뒤 namespace 관측 errno 2 로 전체 matrix 를 끝내지 못했다. publication race 는 당시 가설로 유지한다.
8. signed 30c 의 local NVMe run7 는 actual kernel 6.12 one-way·mutual 4 KiB write/fsync/read·fresh context read, valid wrong host·wrong controller·no credentials 거절을 통과했다. 성공 context 4개 / negative 3개 / 실제 쓰기 총 8,192 B / 정상 owned target·host·port·vault·controller cleanup 을 확인했다. 원본 bfa 및 기존 iSCSI window·두 window 밖 zero 를 보존했고 QEMU/unit/NBD/mount 를 정상 정리했다. Cloud external/API/UI/all4/ROOT/AD 완료는 아니다.
9. targetcli dump 우려는 grep의 sys.exit 누락을 바로잡아 철회했다. 설치된 noninteractive 조기 종료와 actual daemonfalse를 확인했고 global prefs를 변경하지 않았다. 실패 run에서 generic saveconfig 두 경로의 추가 파일은 없다.

[volume-only/NEW20 실제 결과](20261009-volume-only-actual.md), [fc3e 빌드 결과](20261009-fc3e-prototype-build-result.md), [첫 local auth 결과](20261009-local-iscsi-auth-first-run-result.md)에 각각의 source/live/제한을 기록했다. 두 번째 local run의 원인·cleanup은 [실제 targetctl clear 실패](20261009-local-iscsi-restart-clear-failure.md)에 기록했다. 수정된 [run3 실제 인증 결과](20261009-local-iscsi-run3-kernel-auth-pass.md)와 [NVMe 실행 범위](20261009-local-nvme-dhchap-plan.md)와 [run7 실제 인증 결과](20261009-local-nvme-run7-kernel-auth-pass.md)는 별도 증거다.

## 29개 issue별 source / actual / remaining

| 이슈 | source 검증 상태 | actual 근거 및 범위 | 남은 전체 인수 게이트 |
| --- | --- | --- | --- |
| #911 런타임 인플레이스 업그레이드 | 기존 기반 구현 및 후속 source 회귀 존재 | 기존 CLOSED. 실제 signed upgrade/서비스·DATA 보존 subset 확인 | 후속 catalog/featureloss/ROOT/AD는 다른 OPEN gate. 기존 closed 상태를 전체 Epic 완료로 확장하지 않음 |
| #1269 상세 무한 로딩·정합성 | 상세/operation widget null guard·조회 source 회귀 | 실제 초기 mount/null render 수정, 작업·준비 조회 및 timeout stale observation 경고 확인 | 모든 consumer/권한/route/실패 상태 functional 회귀 및 최종 전체 시나리오 |
| #924 bundle catalog·수명주기 | version/featureloss/readonly signed proof·normal SAME PIN ONLY source98 PASS | 실제 test catalog/정상 upgrade/readback. strict 새 family 전체 live는 별도 | current manager/agent/template strict consumer, featureloss/fault lifecycle, 정식 stable signing/key provisioning |
| #892 원자적 변경·복구 | all4 coordinator/render/crypto/POSIX/SERVICE 및 crash-gap/recovery source 보완 | legacy subset·정식 reconcile 및 local iSCSI/NVMe 실제 인증·negative·RAW I/O PASS. all4 transaction proof 와 별도, productionFour=false | fresh approved fixture all4 stage/activate/verify/rollback, inter-phase crash/response-loss, canonical7·identity·DATA 보존 |
| #897 장시간·자원·격리 | queue/formatter lifecycle/resource policy/lease Runtime·ROOT·scale source 연결 | 실제 lookup/startup/SharedFS·generic stop negative, policy enable infrastructure-disabled negative. control OFF | real opt-in lease/reservation/renew/drain/cancel/pressure/failure/restart, 모든 workload의 release·recovery hold |
| #974 대용량 준비 | bounded deadline/status/resume/strict identity/source receipt 보완 | SPARSE10T 실제 timeout/partial 보존·UI negative 확인, NEW20 SPARSE 1회 format PASS. 이전 THIN10T는 새 조건 인수에서 제외 | 정확 SPARSE10T 정상 XFS/ext4 완료·resume/response-loss/partial/status/physical guard, capacity 및 factor finally |
| #913 STATIC 지속성 | STATIC tuple/primary MAC·IP projection 및 repair source 보완 | 정상 NIC CAS+desired apply 후240/alias241 cold boot·DHCP0·route/기존DATA 보존 PASS | fresh SPARSE ROOT fixture, 다른 NIC명/빈 gateway/다중 NIC·계획 변경 및 all4 cold lifecycle |
| #914 다수 export boot deadline | bounded probe/FD9/per-protocol checkpoint source 보완 | 여러 NFS/SMB cold subset 및 POSIX receipt cold PASS, 실패 사례도 보존 | 큰 export 수·dual/v4·endpoint scale, 전체 boot deadline/readiness/progress/interruption 및 실제 반복 없는 replay |
| #918 제한 ACL visibility fault | CIDR skip·bounded cleanup·FD9/native readiness source | 제한된 실제 NFS root/all squash 및 RO subset. DBus fastpath actual 없음 | 허용/거절 client CIDR 전체 matrix, endpoint/export fault 격리, verified DBus export/readback/PID proof |
| #895 삭제·데이터 분리 | exact deletion tuple/persistent format marker/generic VM lifecycle guard source | VM51 unresolved partial의 SharedFS stop 및 generic VM stop actual 거절·DATA 보존 | 실제 NEW fixture PRESERVE/DETACH/DELETE 정책·power loss/stopped/error·retry provenance·owner/pool/UUID mismatch |
| #909 구성 export/import/LKG | multi NEW planner/allocation/binder/adapter·crypto/profile source | readonly 및 기존 subset 기록. 실제 fresh multi NEW/all4 import 없음 | new SPARSE source/target, deterministic receipts/tenant/provenance/FILE1 RAW0/rollback·cleanup·restart/fault 및 external all4 I/O |
| #920 ROOT template 교체 | source 752 의 retained 최신 상태 단계·전용 Runtime NEW_ACTIVATION·capture/authorize/compensation·commit-gap 보완, actual 0 | 실제 ROOT swap0. prototype은 로컬 build 검증만 | source 707 explicit template/precreate API 및 SYSTEM 선택 UI 45c82는 배포·브라우저 선택 검증 완료. private USER prototype 등록·새 artifact/fixture 는 0 이다. producer canonical metadata 조건 확인 후 다른 USER ROOT target 승인, actual swap/retained rollback/AD·DATA·IP·kernel·all4 |
| #900 SMB 다중 endpoint | 누적·멱등 listener와 CIDR·네트워크 replay source 보완 | 실제 VM50 두 IP 인증·교차 I/O·byte-range lock 및 B 삭제·재활성 중 A 940회 쓰기 오류 0, 동일 신원 재부팅 인증 확인 | 새 단일 source SPARSE ROOT의 다중 endpoint 재부팅·activate/rollback·부분 fault·API/UI 전체 인수 |
| #896 SO 제약 UX | filtering/설명/validation source | 실제 기능 UI subset 및 새 sparse SO readback | template/ROOT 조건과 사용자 권한별 선택·거절·메시지 실제 확인 |
| #891 online scale SO 강제 | dynamic/HA/shared 조건 source guard | 정상 서비스 offering 및 새 sparse 2C4G/4C8G API/linked DO DB 확인 | 실제 새 sparse VM 생성/scale·negative SO·재시작/online semantic·UI/API 전체 |
| #904 EXISTING volume 생성 | existing volume mode/디스크 오퍼링 불필요 의존 및 preserve source | 원래 DATA reuse/MOUNT_EXISTING subset, NEW20 후속 child reformat0 확인 | fresh SPARSE ROOT+existing DATA normal create/identity/디스크 오퍼링 불필요/foreign/size/tenant/failure resume |
| #905 고유 용량 합계 | QGA bind-dedupe max +독립 partition SUM/parser source | actual host class 배포·API/UI 사용량174.8MiB 및 총용량/hidden DATA 회귀 | fresh all4 RAW/FILE 다수, detach/failed/partial/ownership·refresh·quota/unique aggregate 전체 |
| #894 squash POSIX preset | preview/token/CAS/leaf-only 권한 source | UI rootSquash65534→명시preview0:0/noRootSquash, client UID65534/0 RW·RO EROFS·child 보존 PASS | all presets/anonymous/manual/RO/no-recursive/approval races 및 신규 package 환경의 mixed policy |
| #906 NFS NUMERIC | service mapping/source gate·monitor 설정 | NAME_DOMAIN nobody와 NUMERIC1001 같은 file 표시 차이·client flag 보존/RW/cold subset PASS | 모든 endpoint/v3v4/security/client 조건·fresh template와 대규모 cold/failure |
| #903 공통 POSIX policy | common policy/explicit preview/protected receipt source | 실제 leaf-only apply·cold receipt/inode·NN 및 SS subset | Ganesha5.5.3 fresh Cloud mixed NS/SN named/default ACL, policy change/rollback/권한 충돌 |
| #915 SMB parent owner | inherit owner/setgid source helper·tests | nested SMB 일반 ACL/파일 보존을 확인했지만 이 옵션 전체 actual proof와 구분 | 전용 UID/GID/setgid 기존·신규파일/child/재접속/권한상속·API/UI positive/negative |
| #916 디렉터리 ACL 관리 | dirfd/leaf scope/preview/receipt/AD-principal source | actual named LOCAL_USER leaf ACL·explicit owner/noRecursive/old child preservation | 모든 access/default ACL·boundary/recursive·AD SID mapping·replay/rollback 및 UI/API |
| #919 SMB mask/force mode | create/directory mask·force/inherit source | SS/mixed/volume-only 파일 mode subset은 전용 policy 전체 검증 아님 | 명시 mask/force/inherit 조합·기존파일 preserve·newfile actualmode·rename/update/cold API/UI |
| #910 nested share | namespace/effective relative/volume-only/guard source 수정 | NN/SS delete/file 보존, volume-only API/UI negative+sameproto positive, NEW20 child-delete 실제 PASS | mixed NS/SN named ACL Cloud fix, symlink/bind/rename races·independent ACL/cold lifecycle 전체 |
| #908 forced UID/GID | identity map/force policy source helper | local uid1002 ACL 시험은 FORCED_UID_GID와 별도 | 관리 identity>=10000 실제 mapping·force user/group/create owner/mode·cleanup·AD/ROOT replay |
| #902 SMB AD/DNS 별칭 | source 792의 SERVICE 원본 암호화·외부 효과 보호 및 fresh 읽기 API, native 117 암호문 재사용 PASS. SPN 형식 불일치는 수정 검증 완료; typed lifecycle/bootstrap/semantic restore 미완 | DC/Client readonly 조사, 시각/SID 일반화·console login PASS. OOBE pending, AD feature actual0 | human OOBE→정상join, isolated fresh sparse service, DNS/SPN/live aliases/Kerberos/ACL+identity backup/ROOT all gates |
| #907 SMB IP/CIDR | allow-list 및 runtime scope source | 허용 host13.1 local auth·A/B I/O subset, 원래 scope 보존 | 단일/다중 CIDR·IPv4/IPv6/허용/거절 실제 client matrix·same session/update/reboot/UI |
| #901 NFS listener 행 분리 | 행 기반 endpoint 및 모드/UX source | 부모 actual NFS table/NUMERIC/parent-child row subset | 다중 IP/ports/dual/v4/ACL 설정과 각 행 callback/readiness/권한/fault refresh 전체 |
| #1275 최종 UI 표준화 | 요청된 functional 11탭 분리는 source/actual 확인 | details 정보 전용·작업/백업복원/upgrade functional 배포. 최종 QA0 | 다른 인수 완료 후 전체 중단·사용자 보고·추가 지시 대기, 그 전 final style/theme/keyboard QA 금지 |

## 정식 signing 권한 한계

정식 workflow path는 `secrets.storage_runtime_signing_private_key`다. 부모 조사에서 repo storage/runtime secret 목록은0, environment는 github-pages이고 workflow environment binding은0이었다. org Actions secret API는403 권한 제한으로 존재 여부를 확정하지 못했다. 따라서 org secret이 없다고 단정하지 않는다.

실제 stable key provisioning/정식 release signing은 아직0이며 local sealed RAM test keys는 그 증거를 대체하지 않는다. [정식 signing 경로 조사](20261009-stable-signing-path-investigation.md)와 `#924` 진전 댓글의 권한 한계를 유지한다.

## 최신 증거와 다음 순서

현재 state read는 `20261009-29-issues-current-state-readonly.json`이다. 현장 baseline은 부모 `current-readonly-api-20261009.json`, KVM `kvm-cache-0a10eccd4c09/postdeploy-proof.json`, local source/kernel 실행은 각 별도 proof다. 이 원장에서는 source-only/test-key/실제 legacy subset/정식 release를 혼합하지 않는다.

local iSCSI/NVMe 인증 matrix 와 정상 cleanup 을 마쳤다. 다음은 AD/native/retained 필요 핀의 단일 source 통합과 새 full image/runtime/RPM, private USER explicit template/precreate producer-consumer gate, fresh SPARSE Cloud validation fixture의 all4/ROOT/multi NEW/AD 순서다. 사용자 OOBE/capacity 답변 의존 단계는 그대로 남겨두며 independent source/readonly 검증은 계속한다. 어떤 미완료 이슈도 닫지 않고 최종 UI 경계를 앞당기지 않는다.
