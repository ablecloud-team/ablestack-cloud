# #1448 구현·13번 배포·인수 중단 보고

> 후속 STATIC writer 수정과 최종 인수는 [완료 보고서](../sharedfs-1448-static-writer-20261026/README.ko.md)를 참조한다. 이 문서는 중단 당시의 근거를 보존한다.

2026-10-26. Epic #1438 / 기존 Draft PR #1470 / `epic/1438-sharedfs`.
**제품 코드는 구현·빌드·13번 배포됐으나, 추가 IP를 가진 VM의 재시작 후 DB 기본 IP가 바뀌는 기존 경로를 발견해 최종 인수를 중단했다. #1448은 OPEN이다.**

## 확인된 중단 원인

새 STATIC 서비스 `8b03d48c-0a8c-47bb-aac8-ce5546b25eed`, VM `867bfbfb-e370-345d-b5f9-ccd20d044219`의 주 IP는 `10.10.13.233/16`, SMB 추가 IP는 `10.10.13.235/16`이다. UI 재시작 후 게스트의 주 주소·별칭·기본 경로·DNS와 NFS 데이터는 모두 유지됐다. 그러나 별도의 L2 주소 조회 작업이 DB NIC 기본 IPv4를 .235로 덮어썼다.

경로는 다음과 같다.

1. `LibvirtGetVmIpAddressCommandWrapper.ipFromDomIf()`가 `virsh domifaddr --source agent` 결과를 읽는다. 실제 출력은 같은 MAC에 .233 첫 행, .235 연속 행이다.
2. 같은 클래스의 `getIpAddresses()` 111–140행은 모든 IPv4 행에서 같은 변수에 대입한다. 134행에서 **마지막 IPv4인 .235**가 남는다. IPv4의 primary/secondary 구분이나 SharedFS STATIC 정책 조회가 없다.
3. `UserVmManagerImpl.VmIpAddrFetchThread.runInContext()` 1023–1038행은 조회 결과가 유효한 IPv4이면 기존 주 주소/STATIC 소유권/등록 별칭 확인 없이 NIC DB를 갱신한다.
4. 실제 관리 로그 03:53:58.799에는 NIC 기본 IP .233으로 조회를 시작했고, 03:53:58.820에는 .235 조회 성공이 남았다.
5. 실제 `listStorageServiceProtocols` 응답은 `primaryip=.235`, `runtimeprimaryip=.233`, `identitystatus=DRIFT`이다. NFS·SMB UI에도 같은 경고가 표시된다.

[실제 로그·native 주소·소스 지점](evidence/primary-drift-cause.json) · [실제 API](evidence/primary-drift-api.json) · [UI](images/stopped-db-primary-drift-ui.png)

이 경로는 이번에 수정한 DHCP hook이나 STATIC helper가 아니다. 배포된 IP 조회 wrapper의 SHA는 현재 브랜치 빌드와 같은 `feb092bbc1f0641599df942af753a5b640e6415c38d28b4caf60bfbd798e4869`다. StatsCollector의 주 IP 보존 로직도 배포된 class와 일치한다. **StatsCollector 결함이나 미배포로 단정하지 않는다. 별도의 VmIpAddrFetchThread writer가 원인이다.**

현재 실제 주 주소는 .233이고 .235는 secondary다. MAC 변경·중복 IP·파일시스템 포맷·데이터 손실은 관측되지 않았다. 클라이언트 NFS 읽기·원본 SHA·권한과 .235:445 접속은 통과했다. 하지만 DB의 주/추가 IP 소유권이 틀리고, 후속 엔드포인트 작업이 DB NIC를 사용하므로 단순 화면 표시 차이로 처리하지 않는다. 추가 변경 요청 실패를 일부러 유발하지 않았으며, 수동 SQL·NIC 복구·재시험으로 증상을 지우지 않았다.

## 최소 영향도의 후속 개선 방향 — 아직 미구현

- 먼저 실제 UI의 네트워크 탭에 주 .233 / 추가 .235가 구분되고, VM 카드·NIC 목록·프로토콜 API의 DB 주 IP도 .233으로 일치하는 흐름을 인수 기준으로 둔다.
- `SharedFSDao.findByVm()`으로 **유효한 STATIC SharedFS와 해당 NIC만 식별**하고, 일반 L2 IP 탐색 작업이 이미 명시적으로 관리되는 주 주소를 덮어쓰지 못하게 한다. 시작 시 스케줄 등록과 이미 등록된 작업의 DB 반영 직전 모두 확인한다. VM 이름 문자열로 구분하지 않는다.
- 같은 VM ID·NIC ID·MAC과 저장된 STATIC IP를 확인하고, null/낡은 관측·등록된 secondary를 주 주소로 승격시키지 않는다. 일반 VM 및 DHCP SharedFS의 기존 IP 탐색·갱신은 보존한다. 공유 파일 시스템이 관리하지 않는 일반 L2 동작을 이번 이슈에서 재설계하지 않는다.
- 이번 .233/.235 실제 출력과 기존 wrapper를 사용하는 회귀로 마지막 행 선택 문제를 고정하고, 서비스 정책이 있는 경우 덮어쓰기가 차단되는 server 작업 시험을 추가한다. STATIC 단일/복수 주소·빈 관측·늦은 응답·DHCP 대조를 포함한다.
- 수정 배포 후에만 기존 UI의 NIC 식별 정보 복구 dry-run/확인을 사용해 시험 VM의 DB 기본 IP를 .233으로 복구한다. 게스트 네트워크·별칭·데이터를 변경하는 우회 복구는 사용하지 않는다.
- UI 재시작 → 원 IP/추가 IP 및 데이터 확인 → **별도 IP 조회 작업이 완료된 뒤 다시 UI/API 확인**까지 수행한다. 현재 시험은 게스트 복원 직후의 성공을 확인했지만, 지연된 외부 writer가 그 뒤에 DB를 바꾸는 구간을 최종 재확인에서 포착했다.

공통 IP 조회 계약을 확장해야 한다면 별도 영향 검토가 필요하다. 우선 SharedFS STATIC writer 보호로 범위를 제한한다. 사용자 지시에 따라 이 개선은 아직 구현·주입·재시험하지 않았다.

## 구현한 범위

제품 commit: `6864e1995d8b344062eda317da7a05c692dedb7d`, `7a9630a08a22475ad6be9bc4f36e98543032939b`, `0e519dbeccd24bdd7b9c641d4457f16ab0499348`, `00c0afb893d`.

- MAC으로 실제 NIC를 찾는 STATIC 정책 helper, DHCP IPv4 이벤트 guard, 주 주소·소유 경로·DNS의 제한적 적용/재관측을 추가했다. 주소 전체 flush·NIC rename·MAC 변경을 하지 않는다. 같은 정책 재적용 시 추가 주소와 다른 NIC 경로를 보존한다.
- 게이트웨이 없는 정상 구성 및 /31·/32를 구분하고 잘못된 gateway/prefix/DNS를 UI·server·guest에서 변경 전에 거부한다.
- 기존 VM에 신규 템플릿과 같은 제어 파일 6개를 전달하도록 KVM 모듈 resource를 재사용한다. bootstrap 신원/완료 guard를 유지하고 monitor 시작의 동기 대기 순환을 제거했다.
- 주 네트워크 → 추가 주소 → 프로토콜 순서를 유지한다. 네트워크 실패는 정확한 phase를 남기고 프로토콜 적용을 호출하지 않는다.
- 기존 health/inventory에 optional network.policy를 추가한다. VM 표준의 세로 네트워크 탭, 좌측 파란 재시작 버튼, 아이콘+업데이트, 설정/실측 구분, 오래된 관측 UNKNOWN, 같은 VM 재시작 확인 및 중복 제출 보호를 추가했다.
- SharedFS에 한해 기존 NIC 컴포넌트의 열/작업·MoldDialog/MoldDialogActions를 표준화한다. 다른 호출자는 기존 기본 모드를 유지한다. 영어/한국어·라이트/다크를 확인했다.
- 새 DB 구조·공개 mutation API는 추가하지 않았다. #1447 카탈로그의 게시/철회/삭제 기능은 이번 이슈에서 재구현하지 않았다.

## 빌드·사전 시험·배포

[산출물·배포 영수증](evidence/build-deployment.json) · [실제 SystemVM 사전 주입/격리 kernel 시험](evidence/native-pretest.json)

- 현재 브랜치 로컬 기반 Europa `a7543feb62a728a5a806283b1e0e6134a7305260`와 upstream 차이 0/0 확인 후 작업했다.
- server 회귀 45개(SharedFS 38 + StorageService 7), KVM wrapper 14개, Maven module package 통과.
- 실제 SystemVM에서 25개 guest 회귀 및 별도 kernel network namespace 시험 통과. ens8/ens9, 실제 iproute2 적용/no-op, 다른 NIC 경로, /31·/32·무gateway, 네 수신 포트와 resolver search/options 보존을 확인했다. live namespace는 변경하지 않았다. 이후 monitor 시작 계약 추가로 WSL 최종 26개 통과.
- UI 관련 16개 통과 후 실제 배포 API로 STATIC+gateway·무gateway·DHCP fixture를 교체했다. 마지막 network 11개 및 기존 create 6개, locale 5개 통과. 대상 lint 및 production UI build 통과. 저장소 전체 CI 통과를 주장하지 않는다.
- 13번 관리 JAR 최종 SHA `2318725d7316e225e5df5215482f8f948ee95502d856d3f33f120d83483eac31`. host 13.1/2/3 KVM 변경 class+resource만 교체하고 그 외 entry를 보존했다.
- UI index SHA `0e16fb7288b26c32443bd4446ec51b83f4e103a7bcba354fd522ac26a38e25dc` 및 후속 ko/en locale 파일을 배포했다. 운영자 config.json과 SystemVM 배포 파일을 보존했다.
- 새 SYSTEM 템플릿 `c408e951-89a0-42f3-8075-a5e4dd3f9bfa`, image SHA `cdf33817e74d35eac560a46dcc80b0fa95cc2a14074ec89249a6099fd902dbdf`. 검증된 기존 이미지에서 명시한 경로만 갱신하고 계정 파일·그 외 파일 무결성/qemu check를 확인했다.
- 정상 서명 runtime `epic1438-1448-network-7a9630a08a2`를 기존 UI에서 등록→검증→게시하고 기존 시험 VM에 preflight/적용 COMPLETE를 확인했다. 기존 보호된 서명 수단을 재사용했으며 비밀을 저장소에 넣지 않았다.

## 실제 UI·클라이언트 검증 결과

자원 생성·설정·재시작은 실제 Mold UI, 클라이언트와 DHCP/실패 시험 실행은 실제 noVNC 콘솔에서 했다. QGA/SSH는 코드 파일만 staging하거나 진단을 읽는 데 사용했다. 숨은 API 호출로 UI 준비를 대체하지 않았다.

| 대상 | 결과와 한계 |
|---|---|
| 기존 인플레이스 VM `5d261ca7-7c62-34a0-bf9d-4a18c91464d0` | 정상 서명 런타임 UI 적용, 같은 VM UI 재시작, .250/MAC·경로·DNS 유지. 기존 같은 IP의 NFS·SMB 파일 및 iSCSI·NVMe direct 4KiB SHA 모두 OK. NFS POSIX와 SMB 파일 권한 보존. |
| 새 STATIC+GW VM `867bfbfb-e370-345d-b5f9-ccd20d044219` | 새 ROOT/DATA sparse, .233/16·GW .1·DNS 8.8.8.8, NFS 실제 마운트/RW, UI SMB 추가 IP .235:445. DHCP 8이벤트 모두 기본/추가 IP·경로·DNS 보존. 통제 실패 phase NETWORK_ALIASES UI 표시 및 기존 서비스 보존. UI 재시작 후 guest/NFS/추가 포트는 정상. **후속 IP 조회의 DB .235 덮어쓰기 발견으로 최종 불합격.** |
| 새 STATIC 무GW VM `fa4905f9-8fa9-339e-8244-e7b78fb3a97e` | 새 ROOT/DATA sparse, .234/16, default route 없음, DNS 유지, 실제 NFS RW 후 UI 재시작. 원 IP 재접속·SHA dc404a613fedaeb54034514bc6505f56b933caa5250299ba7d094377a51caa46·65534:65534/0640 보존. |
| 새 DHCP VM `e4c02b82-7699-36c8-9164-2d6464001414` | 새 ROOT/DATA sparse, 기존 Admin-Isoloated-Net의 실제 DHCP .135/24·GW 10.1.1.1·DNS 10.1.1.1/8.8.8.8. 실제 DHCP client 재요청/ACK·UI 재시작 후 같은 주소·MAC·경로·DNS·DHCP unit active. 격리망의 새 클라이언트 접속 시험으로 확대하지 않았다. |

새 시험 DATA는 각각 5GiB sparse이며 THIN은 생성하지 않았다. IP .233/.234/.235를 UI 자원 및 ARP DAD로 사전 확인했고 Mold가 생성한 MAC을 유지했다. 기존 사용자의 VM과 DATA는 삭제하지 않았다.

[기존 4프로토콜 데이터](images/client-inplace-data-permissions-preserved.png) · [DHCP 8이벤트](images/static-dhcp-events-preserved.png) · [통제 실패](images/controlled-network-failure-console.png) · [무GW 재시작](images/nogateway-restart-ready-dark.png) · [DHCP 재시작/라이트](images/dhcp-restart-ready-light.png)

## 표시 항목과 보존한 환경

실행 영향 없는 표시 항목만 향후 #1468 UI 정리에 기록한다. 해당 작업은 HOLD이며 시작하지 않았다.

- DHCP 재시작 대화상자는 STATIC용 저장 필드를 참조해 IP UNKNOWN·게이트웨이 미지정으로 표시한다. 네트워크 탭은 실제 DHCP 임대·게이트웨이·DNS를 정상 표시하고 동일 VM 재시작은 정상 완료했다.
- 잘못된 prefix 입력 메시지가 선택 항목에도 중복 표시되는 점, 실패 진단에 helper JSON 일부가 길게 노출되는 점, busy 상태 비활성 확인 문구 대비는 후속 표시 정리 항목이다.
- 기존 VM의 일반 networking.service에는 변경 전부터 eth0 ifup 관련 failed 상태가 있었다. SharedFS STATIC/early/reconcile 및 실제 프로토콜 정상과 구분한다.

DB 주 IP 덮어쓰기는 위 표시 목록과 다르며 **#1448의 최종 인수 차단 항목**이다. 시험 VM 3개와 증거를 다음 지시를 위해 보존했다. 통제 실패 사본은 제품 파일을 바꾸지 않았고, 그 진단은 정상 UI 재시작으로 READY가 됐다. 시험 코드 파일은 /tmp 및 기존 시험 NFS의 e1448-nfs.py에만 staging돼 있으며 제품 서비스 설정이나 인증 파일을 바꾸지 않았다. 이 문서 이후 개선 코드·UI 복구·추가 재시험은 실행하지 않았다.
