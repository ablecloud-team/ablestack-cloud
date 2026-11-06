# #1455 R3 — 미게시 SMB 수정 통과 / NFS 삭제 확정 결함으로 중단

현재 판정은 **전체 인수 미완료·이슈 OPEN·Epic PR #1470 Draft**입니다. 요청받은 미게시 SMB setgid 결함은 수정·배포하고 실제 UI와 두 클라이언트에서 통과했습니다. 이후 정상적인 NFS 별칭 삭제에서 별도의 제품 오류를 확인해 즉시 중단했습니다. 오류 뒤에는 상태·로그·데이터를 읽기 전용으로 조사했고 재시도·복구 재개·추가 수정·재시작을 하지 않았습니다.

구현 commit: **e7323382510220e06daeac12620b4492a74c8d69**. 로컬 Europa를 upstream **1c5b169cd6283da23cc3e155d4b6d14e39e42793**로 먼저 동기화하고 divergence 0/0을 확인한 뒤 Epic 브랜치를 이어 갔습니다. origin/epic/1438-sharedfs로만 push했으며 새 이슈나 PR을 만들지 않았습니다.

## 수정한 범위와 유지한 보호

- 새 경로 모드에서 ACL0·guestOk=false인 저장 desired의 UUID/경로 신원이 일치하고, 소유를 확인한 **모든 관리 리스너**에서 섹션 부재가 확인된 경우만 UNPUBLISHED_WAITING_ACL로 구분합니다.
- 실행 config의 전체 testparm 파싱과 전역 force user/group을 조회합니다. 게시된 공유의 섹션 누락, 예상 밖 게시, 저장 신원 불일치·관측 실패, 고정 사용자/그룹과 상속의 충돌은 계속 차단합니다. 예외를 무시하거나 ACL을 넓히지 않았습니다.
- 기존 공통 디렉터리 정책·setgid 대화상자에 미게시/ACL 대기 안내를 넣었습니다. 권한 변경이 공유 게시나 접근 허용을 뜻하지 않으며, 다음 ACL 설정과 실제 클라이언트 검증이 필요함을 표시합니다. 게시된 섹션 누락은 별도 차단 안내로 유지합니다.
- VM 표준 MoldDialog/AntD, 고정 헤더·푸터, 본문 스크롤, 기존 탭/버튼 배치를 유지했습니다. 새 별도 작업 화면은 만들지 않았고 실제 라이트/다크 렌더링을 확인했습니다.

## 구현 전 실제 SystemVM 주입과 회귀

동일한 실제 SystemVM에 후보 코드를 격리 주입했습니다. 기존 실패 지점인 setgid 사전 점검이 미게시 대기 상태를 정확히 판정했고, 실제 metadata/registry/config는 불변·apply 실행0이었습니다.

같은 SystemVM의 private Samba 데몬2개로 **6 PASS**: 의도된 부재와 전역 기본값 조회, 양쪽 게시 섹션 검증, 게시 섹션 누락 차단, 예상 밖 게시 차단, 전역 force group 충돌 차단, inode/데이터 보존. 시험 sandbox는 정리했습니다.

| 범위 | 결과 |
|---|---|
| 게스트 회귀 | 101 PASS |
| Java/API 실제 직렬화·경로/POSIX 회귀 | 22 PASS, 실패/오류/skip0 |
| UI 실제 serializer fixture·기존 경로/상속/안내 | 4 suites / 23 PASS |
| UI 제품·시험 lint | PASS |
| 서명 payload·10파일 profile 및 bootstrap | 첫/반복 총3회 PASS, 이전 target 거부 유지 |
| 관리 모듈 package·UI production build·SystemVM 이미지 | PASS |

시험 전송 도구가 단순 문자열 치환으로 Base64 일부를 바꾸는 실수는 구조화 JSON 전달로 자체 교정했습니다. 기존 UI 시험의 가짜 modal context 누락도 시험만 보완했습니다. 두 항목은 제품 보호를 완화한 변경이 아닙니다. 전체 저장소 CI 통과를 주장하지 않습니다. 기존 공통 checkstyle/rat skip 등 관리 빌드 조건은 이전 단계와 같으며 이번 표는 선택한 회귀와 모듈 빌드 결과입니다.

## 13번 배포와 무결성

- 활성 게스트: 정상 서명 **1455.paths.3 / baseline8**. manifest SHA256 **01974ddc1899f35ef005fd5bb764f2c0c009267b4fcceab8959020d899ff23ba**. 기존 updater를 유지하고 최종 서명 파일/profile을 확인했습니다.
- 관리 JAR: bootstrap profile resource **1개**만 변경, 나머지 **92,555 entries 바이트 동일**. SHA256 **fa2a02d93f75d71773262759d8884cbcf165bd0d1fa8ff5052474451d4d92395**. DB migration·host agent 변경0.
- UI: R1 실제 배포본 기준 달라진 자산 **6개**만 반영. 운영 config.json/color.less 보존. index SHA256 **cf287119ee2540d32d21b889cbc996504469a3bb0f0432c10d923067e76bac21**.
- hot deployment 뒤 오래 실행 중이던 monitor가 시작 시 결정한 예전 CLI 경로를 유지해, **monitor service만 재시작**해 관측 collector를 갱신했습니다. NFS 설정 재적용 없이 fileSharePaths와 nfsLocalProbes 실제 조회를 확인했습니다. R2에 미확인으로 남겼던 UI/관측 안내는 이번에 확인했습니다.
- 새 SystemVM 이미지 빌드/서명/압축/qemu-img 검증 완료: SHA256 **b5f20345ae8658b2347e3b51255b797b61a8f3c0f5f6f42deeea67b0889e64e7**, 498,399,488 bytes. 기준 이미지 대비 허용8경로 변경·33,929경로 동일·호스트 계정 파일 불변. **이미지 업로드/템플릿 등록·새 VM 생성은 하지 않았습니다.** 실제 인수는 현재 배포된 시험 SystemVM에서 수행했습니다.

## 실제 UI·API·두 클라이언트 인수

자원/설정 변경은 실제 Mold UI로 실행했고, 해당 화면에서 발생한 API 결과를 수동 동작과 대조했습니다. QGA는 격리 주입과 읽기 전용 상태/신원/보존 근거 수집에만 사용했습니다. 클라이언트 I/O는 실제 Mold noVNC 콘솔에서 실행했습니다.

기존 전용 DATA는 **10GiB SPARSE XFS**, UUID e070a0aa-dbf5-48c0-83e0-09f874446277, FS UUID 9c8ddfe9-3e23-46f0-9476-5c794b004841입니다. VM d9c423e9-ce89-3ba9-9cd8-f48cdb791da2 / i-2-195-VM, SharedFS 3f0056db-4dfe-4f13-908d-b809dd565d34, instance c1eca707-a554-4c42-85cd-1e9e9744d804. MAC/IP 변경·재포맷0입니다.

| 실제 UI 작업 | operation / 결과 |
|---|---|
| 미게시 s1455-child setgid 점검·명시 ACK·적용 | f7e90951-6fc9-47b6-9fd4-7ca55443b46a / COMMITTED rev31 |
| 기존 로컬 사용자 SMB ACL 게시 | 3e56393b-da07-43f3-bd9f-900f45e9b1cd / COMMITTED rev32 |
| 같은 물리 child의 NFS 별칭 생성 | a6deca45-881f-44a8-a915-9ed980f58ebb / COMMITTED rev33 |
| child NFS 제한 ACL | 3d1809da-4f0a-431f-a17a-e92b4ab683b7 / COMMITTED rev34 |
| 형제 team-b NFS 생성 | e3a86986-e3dc-4b10-8b81-55cd3d3d3fce / COMMITTED rev35 |
| 형제 NFS 제한 ACL | 598c7642-4240-44e8-b27a-b329e64d6cf6 / COMMITTED rev36 |

setgid는 child inode25165961 하나에만 명시 적용해 0:0/0770 → **65534:65534/2775**를 확인했습니다. ACL 전 게시 방지는 유지됐고, ACL 이후에만 SMB 클라이언트 접근을 허용했습니다. NFS ACL은 두 client **10.10.254.224/32 + 10.10.254.223/32**만 사용했으며 서버 IP/loopback/대역을 추가하지 않았습니다.

두 실제 클라이언트 aaa3c43c-4f32-48f2-bdd5-e3930f908a07와 e6744029-299e-4585-981b-65e312d9e752에서 NFSv4.1 parent/child/sibling mount·쓰기·교차 읽기·cmp/hash를 통과했습니다. SMB 로컬 인증으로 **445/1445**에서 NFS가 만든 파일을 읽고 새 파일을 쓰고, NFS에서 수정한 내용을 반대 SMB 포트에서 읽었습니다. client2의 SMB로 NFS 파일을 갱신한 뒤 client1이 같은 내용을 읽고 hash 일치를 확인했습니다. 새 SMB 파일은 UID1001/GID65534, NFS 파일은 UID/GID65534:65534이며 기존 NFS 파일을 SMB로 덮어써도 inode/owner가 유지됐습니다.

의도한 보호 검증은 다음과 같이 통과했습니다. 이는 아래 예상 밖 제품 오류와 구분합니다.

- ../escape: UI 적용 전 차단.
- 실제 parent NFS에서 만든 symlink link-probe: UI 사전 점검 **DIRECTORY_PATH_SYMLINK**, 적용 비활성.
- 자식이 있는 parent 삭제: NFS child/sibling·SMB child 참조를 표시하고 변경 전 차단.
- 참조 중 DATA detach: UI 버튼 비활성.
- 동일 NFS 별칭 중복 생성: **PREPARATION_REJECTED / VALIDATING**, ROLLED_BACK·NOT_NEEDED, 기존 row1개 유지, rev36 유지. operation 6af5f54d-7251-4603-bea0-a328335175ca.

표시만의 차이(원복 불필요 작업의 원복완료 문구, 일시적 ID mapping 관측 차이)는 기록만 하고 #1468 최종 UI 정리 대상으로 남겼습니다. 이 차이로 시험을 중단하거나 범위를 확장하지 않았습니다.

## 실제 제품 오류와 정확한 하위 원인

정상 UI에서 **NFS p1455-child 별칭 삭제**를 처음 실행한 뒤 오류가 발생했습니다. 강제 실패 주입은 설치하지 않은 상태였습니다.

- request **caf503d6-4ade-4381-805e-9fbb57d80027**
- operation **18dc6b50-841f-4980-8eab-2c1a6409fa58**
- job **68c207bc-84ea-4592-aa12-cbfab152021c / numeric4489**
- 삭제 대상 b32c297a-cf16-4b05-bbfa-674275797c71
- base revision36 / target37
- Guest **VERIFIED**, runner exit0, worker 비활성.
- Management **RECOVERY_REQUIRED / COMMITTING**, canresume=true. SQL 확정/lease 해제 전 오류.

정확한 실제 로그:
```
java.lang.ClassCastException: com.google.gson.JsonPrimitive cannot be cast to com.google.gson.JsonObject
 at com.google.gson.JsonObject.getAsJsonObject(JsonObject.java:221)
 at StorageFileSharePaths.applyBindings(StorageFileSharePaths.java:161)
 at StorageServiceConfigurationCoordinator.lambda$commit$7(StorageServiceConfigurationCoordinator.java:349)
 ...
 at StorageServiceManagerImpl.deleteStorageNfsExport(StorageServiceManagerImpl.java:1045)
 at DeleteStorageNfsExportCmd.execute(DeleteStorageNfsExportCmd.java:66)
```

**앞서 #1455 R1에서 제가 추가한 경로 영수증 확정 코드의 결함입니다.** deleteStorageNfsExport는 내부 runConfiguration의 Boolean.class 결과 true를 candidateSnapshot.response에 저장합니다. public API의 SuccessResponse와는 다른 내부 형식입니다. applyBindings는 남은 경로 영수증 검증을 마친 뒤 response가 존재하기만 하면 객체라고 가정해 getAsJsonObject("response")를 호출합니다. true는 JsonPrimitive이므로 이 지점에서 실패합니다.

coordinator의 Transaction 안에서 applyBindings(349) 다음이 plan.commit()(350)입니다. 따라서 게스트 NFS 설정은 적용·검증됐지만 SQL 삭제·revision37 확정·작업 해제에는 도달하지 못했습니다. 생성/수정 DTO 객체 위주로 작성한 기존 회귀에서 **Boolean 삭제 결과를 확정하는 경로가 빠졌습니다.** 미게시 SMB 분기나 네트워크 실패로 추정하지 않습니다.

## 현재 영향과 보존 상태

- 게스트 desired/실행 exports에는 p1455-child가 제거됐고 NFS 별칭 bind mount는 없습니다. SQL은 이전 구성과 복구 대기 작업을 유지합니다. UI가 새 변경을 차단하는 것은 이 불일치에서의 보호 동작입니다.
- 물리 child 디렉터리와 SMB 두 리스너는 남아 있습니다. child는 **65534:65534/2775, inode25165961**이며 ACL도 보존됐습니다.
- 읽기 전용 비교: 기존 **54객체의 hash·owner/group·mode·ACL·inode·device 전부 동일**. 새 **8파일의 내용/hash 모두 클라이언트가 마지막에 쓴 값과 일치**합니다. 상세는 preservation-after-delete-failure.json 및 after-alias-delete.json입니다. 오류 후 클라이언트 접속을 다시 실행했다고 주장하지 않습니다.
- 시험 client NFS mount6개를 모두 해제하고 런타임 암호 변수 제거·로그아웃했습니다. symlink 시험 흔적은 전용 DATA에 남아 있습니다.
- **오류 뒤 복구 재개·삭제 재시도·새 설정·DB 수동 수정·재포맷·MAC 변경·VM/관리 재시작을 하지 않았습니다.**

## 최소 개선 방향과 남은 검증 — 아직 미구현

1. response의 config를 보완하는 부분만 **JsonObject 타입일 때** 수행하고 Boolean true를 그대로 보존합니다. operation/volume/FS UUID/inode·누락/불일치 영수증 검증과 SQL 확정 순서는 유지합니다. 삭제라서 영수증 검증 자체를 생략하면 안 됩니다.
2. 실제 coordinator→DAO 저장·재조회 회귀에 Boolean.class 삭제, 남은 중첩 경로의 영수증, 마지막 bound 공유 삭제/빈 영수증, 같은 작업 재개·중복 관찰을 넣습니다. 잘못된 영수증은 계속 거부하는 반대 시험도 필요합니다. NFS가 실제 확인된 영향이며 SMB 삭제 등 동일 내부 결과 사용 지점은 코드/회귀로 확인해야 합니다.
3. 수정·모듈 배포 후 **동일 operation18dc6b50…**를 재개해 SQL target37 확정과 guest ACK/finalize를 확인합니다. 새로운 삭제 요청·게스트 재삭제·수동 DB 덮어쓰기로 대체하지 않습니다.
4. 그 뒤 설정만 삭제하고 물리 데이터/다른 공유를 보존하는 인수, 의도한 적용 중 실패 원복, 실제 VM/관리 재시작 후 재접속·권한·데이터 보존을 완료해야 합니다.

현재 인수 기준1(교차 접근/보존),2(경로·충돌/권한 처리),4(parent delete/detach 보호)는 근거를 확보했습니다. **기준3의 삭제 확정·실패 원복·재부팅은 미완료**이므로 #1455를 닫지 않았습니다. 제품 오류 중단 지시에 따라 여기서 작업을 종료하고 다음 명령을 기다립니다.

## 증거

- evidence/: 실제 SystemVM 주입/회귀/bootstrap, 모듈 빌드/배포 무결성, UI가 발생시킨 API 대조(인증정보 제거), 삭제 오류 로그와 읽기 전용 상태/보존 비교.
- screenshots/: 미게시 안내·확정·두 실제 client 교차 I/O·안전 경로/삭제/detach 보호·최종 복구 대기 화면.
