# #1448 STATIC 주 IP 조회 덮어쓰기 수정·최종 인수

2026-10-26(KST). Epic #1438 / PR #1470 / `epic/1438-sharedfs`.
**STATIC SharedFS의 DB 주 IP를 지연된 일반 IP 조회가 덮어쓰는 경로를 수정하고, 실제 UI NIC 복구·재시작·지연 조회 이후 보존 검증을 통과했다.**

## 수정 범위와 기존 동작 보존

제품 commit `02144a03a47105bb14d7355b92ed2a5085dedb45`.

- `UserVmManagerImpl.isStaticSharedFsPrimaryNic()`가 NIC 기본 역할과 `SharedFSDao.findByVm()`의 VM 연결 및 STATIC 모드를 확인한다. VM 이름이나 임의 MAC 변경으로 구분하지 않는다.
- 초기 빈 IP 적재, 배포 완료, 사용자 재시작의 일반 IP 조회 등록에서 해당 NIC를 제외한다.
- 이미 대기 중인 `VmIpAddrFetchThread`도 요청 전과 응답 직후 다시 확인한다. 정책이 요청 중 STATIC으로 바뀌었으면 별칭·null 응답을 DB에 반영하지 않고 대기 항목을 종료한다.
- DHCP SharedFS·일반 VM·STATIC VM의 추가 NIC는 기존 조회·갱신·재시도 동작을 유지한다. 공통 KVM IP 파서는 변경하지 않는다.
- NIC DB가 처음 비어 있는 STATIC 생성은 기존 프로토콜 활성화의 `reconcileProtocolListenNicIdentity()`가 실제 primary 관측을 한 번 반영하는 경로를 유지한다. 실제 새 서비스 생성까지 확인했다.
- 공개 API·DB 구조·SystemVM 실행 코드·MAC·네트워크 구성·프로토콜 엔진을 이번 수정에서 변경하지 않았다.

## 소스 동기화·빌드·배포

로컬 Europa를 upstream `b71bb69f28cc53342c55e656742c27d9c6f8f8a5`로 먼저 동기화(차이 0/0)하고 기존 Epic 브랜치를 rebase했다. 기존 head `05df82cd92ef3a87c23c8f7c0ebbf970c6472bbb`는 로컬 `refs/codex-backups/epic1438-pre1448-writer-fix-20261026`에 보존했다. 공통 locale은 JSON 키 단위로 양쪽 변경을 보존했고, listRefresh의 SharedFS requestScope와 upstream 기본 routeKey, AutogenView의 snapshot/runtime catalogue 처리를 함께 유지했다.

최신 upstream 구간에서 UserVmManagerImpl 원본 변경은 없음을 확인했다. 13번의 기존 검증된 런타임 JAR에 **이 클래스와 내부 클래스 27개만** 교체했으며 다른 entry는 byte 단위로 동일함을 확인했다. upstream 스냅샷 전체 기능을 13번에 새로 배포했다고 주장하지 않는다.

| 항목 | 결과 |
|---|---|
| server 회귀 | 새 IP 조회 worker9 + 기존 SharedFS38 + StorageService7 = 54 PASS |
| KVM 실제 출력 경로 | 13 PASS. 실제 .233/.235 domifaddr 입력이 기존 파서에서 .235를 반환하는 계약을 고정 |
| 모듈 package | server/KVM 및 reactor 의존 모듈 BUILD SUCCESS |
| rebase 공통 UI 계약 | SharedFS read/network/locale 및 Autogen runtime catalogue 30 PASS |
| 관리 모듈 | 13번 mold 정상 재시작, 이전/이후 PID 변경, UI 재로그인 및 실제 API 정상 |
| JAR 이전 SHA | `2318725d7316e225e5df5215482f8f948ee95502d856d3f33f120d83483eac31` |
| JAR 배포 SHA | `bc7044751fe17b4f382cab05c9dd56a788fe3bcbad3ae6132981114304fc9da7` |

[모듈·배포 영수증](evidence/deployment.json) · [변경 entry·SHA](evidence/module.json) · [회귀 결과](evidence/test-results.json)

새 worker 회귀는 실제 private worker를 실행해 늦은 별칭·null 응답 차단, 빈 STATIC NIC 보존, 일반/DHCP 갱신과 null 관측 재시도, 추가 NIC, VM 연결 불일치를 확인한다. 공통 VM 회귀 261개도 원본 배포 클래스 cohort와 수정 cohort를 각각 같은 classpath에서 실행했다. 둘 다 **같은 10개 기존 실패**이며 새 실패는 없다(251 PASS). 이슈 범위를 확장해 기존 VM 생성 fixture·userdata 잠금 테스트를 고치지 않았다. [동일 기준 비교](evidence/common-vm-baseline-comparison.json)

기존 #1447 API wildcard import Checkstyle12건에서 기본 빌드가 막혀 해당 검사만 `-Dcheckstyle.skip=true`로 제외하고 대상 컴파일·회귀·package를 수행했다. 저장소 전체 CI나 Checkstyle 전체 통과로 확대하지 않는다.

## 실제 UI 인수

숨은 API로 자원을 준비하거나 DB를 직접 바꾸지 않았다. NIC 복구·재시작·새 생성은 Mold UI, 클라이언트 접속/데이터 검사는 Mold noVNC 콘솔에서 실행했다. QGA/SSH는 시험 코드 staging과 읽기 전용 진단에 사용했다.

### 1. 기존 실패 자원의 NIC 복구

- SharedFS `8b03d48c-0a8c-47bb-aac8-ce5546b25eed`, VM `867bfbfb-e370-345d-b5f9-ccd20d044219`.
- UI NFS 탭 → NIC 식별 정보 복구 → 사전 점검 `ELIGIBLE`.
- DB 주 IP .235 / 실행 주 IP .233 / 보존 별칭 .235를 확인한 뒤 **복구 적용**.
- API dry-run job `9f9040e4-54f5-4177-be49-23a15d79d67d`와 apply job `008c565a-37b3-4a73-9c6d-034cdf071b51` 성공. `REPAIRED`, `aliasesPreserved=true`, DB 주 IP .233 확인.
- UI 업데이트 후 VM 카드·NIC 목록·네트워크 설정/실측 .233 일치, 기존 DRIFT 경고 해소.

[사전 점검 UI](images/nic-repair-dryrun.png) · [교정 후 UI](images/nic-repaired-network.png) · [UI API 작업](evidence/ui-api.json)

### 2. UI 재시작과 지연 조회 이후 주 IP/별칭 보존

- 네트워크 탭의 재시작 확인으로 같은 VM을 `cleanup=false` 재시작. job `b6a10b12-21ba-4983-a37e-51aa1a703e5f` 성공.
- 실제 boot ID가 이전 `517934e9-9bd5-490d-9754-20260024cd62`에서 `b7d3a9fe-5822-4e2b-a013-ef3783f9baff`로 변경됐다. VM·NIC·DATA UUID는 같다.
- UI 글로벌 설정에서 실제 `externaldhcp.vmip.retrieval.interval=180`초를 확인했으며 설정값을 바꾸지 않았다.
- UI 재시작 시작 후 **917초**(180초 주기 5개 이상 경과)에 네트워크 탭을 업데이트했다. 주 .233/16·별칭 .235/16·GW 10.10.13.1·DNS 8.8.8.8·MAC 02:0d:00:cc:00:57을 유지했다.
- 실제 프로토콜 API는 NFS·SMB 모두 `primaryip=.233`, `runtimeprimaryip=.233`, `identitystatus=CONSISTENT`; SMB 별칭은 .235:445 그대로다.
- 호스트의 실제 domifaddr는 여전히 .233 다음 .235 순이다. 파서/주소 순서를 바꿔 시험을 통과시킨 것이 아니다. 재시작 후 해당 STATIC VM의 일반 IP 조회 writer 실행이 없고, stats 관측이 .235를 반환해도 DB .233을 보존하는 로그를 확인했다.

[조회 주기 UI](images/ip-fetch-interval-180-ui.png) · [지연 조회 후 최종 UI](images/static-after-delayed-queries.png) · [실행 로그](evidence/runtime-writer-logs.json) · [native](evidence/static-native.json)

### 3. 클라이언트 접속·데이터·권한 보존

기존 marker를 다시 쓰지 않고 주 IP .233의 기존 NFS 마운트와 별칭 .235의 **읽기 전용 새 마운트**에서 같은 파일을 읽었다. 캐시를 비운 뒤 두 경로 모두 다음 원본 값을 유지했다.

- SHA-256 `dc404a613fedaeb54034514bc6505f56b933caa5250299ba7d094377a51caa46`
- UID:GID `65534:65534`, mode `0640`
- .235:445 실제 TCP 접속 성공. SMB 인증·공유 기능을 이번 수정에서 새로 구성하거나 재구현하지 않았다.

[실제 클라이언트](images/client-primary-alias-after-restart.png)

### 4. DHCP와 새 STATIC 생성 대조

- 기존 DHCP SharedFS `1fe48b9f-eac4-499b-bef7-7020e85b5ee8`도 실제 UI 재시작 성공(job `344c04a2-562b-40c2-b3ed-56607387dbe1`). 같은 .135/24·GW 10.1.1.1·DNS 10.1.1.1/8.8.8.8·MAC 02:0d:00:d0:00:05 및 DHCP unit active를 확인했다.
- 시작 시 조회 등록 경로도 변경했으므로 새 STATIC 서비스를 UI 생성했다. UI 전체 VM35개 주소와 ARP DAD3회 응답0을 확인한 빈 .236/16을 사용했다.
- 새 SharedFS `3b0499a2-36fc-4a2c-911e-05950a02ccc8`, VM `fdba1e19-3901-3a3e-a4a9-50b2e715f3e7`, ROOT `77560758-ec3d-42b5-8507-a2213827379a`, DATA `d064b2af-8d18-4c8a-91a5-8c35124e4fb6`.
- 생성 job `0b779167-df33-469e-b2d9-9d604cbc56d5` 성공, 초기 NFS/ACL/runtime 완료. 실제 VM 볼륨 탭에서 ROOT 4.88GiB와 DATA 5GiB **모두 sparse** 확인.
- 실제 클라이언트 콘솔에서 새 .236 NFS 마운트·쓰기·읽기 및 marker SHA/권한 확인. 새 THIN 디스크, 임의 MAC 변경, 기존 데이터 재포맷은 없다.

[DHCP UI](images/dhcp-after-module-restart.png) · [새 ROOT/DATA](images/new-static-root-data-sparse.png) · [새 NFS 실제 접속](images/client-new-static-nfs.png)

## #1448 완료 판정과 범위

이 후속 수정으로 직전 [인수 중단](../sharedfs-1448-network-20261026/README.ko.md)의 DB writer 결함을 해결했다. 이전 구현에서 통과한 STATIC 무gateway·DHCP 8이벤트 보호·동일 IP 4프로토콜 재접속/데이터 보존·실제 SystemVM non-eth0 kernel 시험·통제 실패 진단은 guest/host/UI 관련 코드가 같으므로 해당 증거와 함께 판정한다. 이전 Epic #898 결과를 승계하지 않았다.

추가 제품 UI/API/backend 오류는 관측되지 않았다. #1448을 완료로 닫을 수 있다. 최종 전체 UI 정리 #1468은 계속 HOLD이며 기존 표시 차이 목록을 숨기거나 완료 처리하지 않았다. 새 시험 자원은 리뷰를 위해 보존했다. Epic 전체/PR 병합이나 #1449 구현을 시작하지 않았다.
