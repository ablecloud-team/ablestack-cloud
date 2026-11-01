# #1455 R4 최종 인수 — Boolean 삭제 확정 해결·전체 인수 완료

**판정: #1455 인수4항목 PASS.** 기존 복구 대기 삭제를 같은 operation으로 확정했고, 실제 UI·API 대조·두 클라이언트의 NFS/SMB 접근·실패 원복·재시작·권한/데이터/진단 보존을 확인했습니다. R3의 ClassCastException은 이번에 해결했습니다. 예상 밖 제품 오류는 없었고, 아래 의도한 단발성 실패는 실패 원복 인수로 구분합니다.

구현 commit **e4de8529e57cb1f09ccdbfbc0841c3cd8abf059d**, 브랜치 epic/1438-sharedfs, 기존 Epic #1438 / Draft PR #1470입니다. 최신 upstream을 fetch하고 로컬 Europa **1c5b169cd6283da23cc3e155d4b6d14e39e42793**를 먼저 정렬해 divergence0/0을 확인했습니다. 새 이슈·분기·PR는 만들지 않았습니다.

## 제품 수정과 유지한 보호

StorageFileSharePaths.applyBindings의 응답 config 보완 조건 한 곳만 다음과 같이 변경했습니다.

```java
if (snapshot.has("response") && snapshot.get("response").isJsonObject()) {
    // 기존 DTO 객체 응답의 config 보완
}
```

내부 Boolean 결과 true는 그대로 보존합니다. 그 이전에 수행하는 operation·volume·FS UUID/inode·누락/불일치 영수증 검증과 SQL transaction·revision 확정 순서는 바꾸지 않았습니다. 삭제라고 영수증 검사를 건너뛰지 않습니다. 객체 응답의 config/경로 신원 보완도 유지합니다.

게스트·UI·DB 스키마·host agent 제품 코드는 변경0입니다. 이전 R3의 미게시 SMB 구분과 게시 섹션 누락·상속/force user/group 충돌 차단을 그대로 유지했습니다. UI는 기존 VM 표준 MoldDialog/세로 탭/공통 컴포넌트·라이트/다크 스타일을 사용합니다. 이번에 별도 레이아웃을 만들거나 전면 UI 정리를 진행하지 않았습니다.

## 구현 전 실제 SystemVM와 회귀

- 현재 SystemVM의 실패 작업은 guest VERIFIED임을 읽기 전용으로 확인하고 실제 pathbindings 응답을 회귀 fixture로 확보했습니다.
- 제품 수정 전에 같은 SystemVM에 격리 주입한 실제 Samba 데몬2개 시험 **6 PASS**: 의도된 미게시·게시 섹션 검증, 게시 섹션 누락/예상 밖 게시/전역 force group 충돌 차단, inode/데이터 보존. 시험 영역을 정리했습니다.
- 실제 coordinator→전용 loopback MariaDB/실제 DAO로 **기존 코드의 Boolean 삭제 ClassCastException을 먼저 재현**했습니다. guest를 모방한 임의 응답 구조로 대신하지 않고 실제 guest VERIFIED 영수증의 형식을 사용하며, 시험 DB/request 식별자만 격리에 맞게 치환했습니다.
- 수정 후 Java/API **46 PASS / 실패0·오류0·skip0**. 실제 coordinator→DAO12개(신규7개 포함), 경로4, 직렬화 계약5, NFS POSIX13, 상속 계약4, 사전 점검8입니다.
- 신규7개는 NFS Boolean 삭제/남은 중첩 경로, SMB Boolean 삭제, 마지막 bound 공유/빈 영수증, 잘못된 operation/volume/inode 거부, 누락 거부 후 동일 durable operation 재개를 검증합니다. SQL 삭제·revision/lease 해제·true 저장/재조회·중복 요청/완료된 재개에서 guest apply1·ACK1을 확인했습니다. 기존 객체 생성 응답의 config 신원 보완 회귀도 계속 PASS입니다.
- 기존 사전 점검 시험은 추가된 NFS/SMB 관계 조회 횟수를 예상하지 않아 Mockito 검증에서 실패했습니다. 현재 읽기2+1회만 허용하도록 **시험 예상만 교정**했고 verifyNoMoreInteractions로 DAO 쓰기 금지를 유지했습니다.
- **26모듈 package PASS**. Maven의 checkstyle/rat/license skip 및 JAXB noOptimize 조건을 명시합니다. 전체 저장소 CI 통과를 주장하지 않습니다. 시험용 DB는127.0.0.1:13344에만 실행했고 종료했습니다.
- 콘솔 입력 도구의 특수문자 Shift 누락은 비밀값 없는 표시 시험으로 원인을 확인하고 도구를 교정했습니다. 최초 로그인 실패 뒤 올바른 인증으로 계속했으며 계정/제품 코드는 바꾸지 않았습니다.

## 13번 최소 배포·무결성

기존 관리 JAR에서 **StorageFileSharePaths.class 1개만 교체**했습니다. 다른 **92,556 entries를 바이트 비교해 동일**함을 확인했습니다. 기존 JAR와 서비스 복구 자료를 보존하고 mold.service를 재시작했습니다.

- 새 클래스 SHA256: **06858c7e59b6de8eec30ac7fc8f741a7700ce5f35a762643a9b6f65782c8e677**, Java major55.
- 배포 JAR SHA256: **9e55bac7f538941a481cba326e68ff5bf12d8701307649c8cbb79ae67aa1f153**.
- 게스트 **1455.paths.3/baseline8**, 기존 UI 배포본 유지. 최종 실제 active helper와 CLI SHA가 현재 소스와 일치합니다. 이번에는 guest/UI 산출물·SystemVM 템플릿을 새로 배포하지 않았습니다.
- 새 DB migration·새 디스크·MAC 변경·재포맷0. 기존 전용 **10GiB SPARSE XFS DATA**를 사용했습니다.

## 실제 UI와 API·클라이언트 순서

자원·서비스 변경은 Mold UI에서 실행했고 그 UI가 발생시킨 API 결과를 대조했습니다. QGA는 격리 주입·명시한 시험 실패 hook 및 읽기 전용 증거 수집에 사용했습니다. 숨은 API로 자원/설정을 준비하거나 UI 단계를 대체하지 않았습니다. 클라이언트 I/O는 실제 Mold noVNC 콘솔에서 실행했습니다.

대상 SharedFS **3f0056db-4dfe-4f13-908d-b809dd565d34**, instance **c1eca707-a554-4c42-85cd-1e9e9744d804**, VM **d9c423e9-ce89-3ba9-9cd8-f48cdb791da2 / i-2-195-VM /13.2**입니다. 원래 IP **10.10.13.220**, MAC **02:0d:00:cc:00:93**을 유지했습니다. DATA UUID e070a0aa-dbf5-48c0-83e0-09f874446277, FS UUID **9c8ddfe9-3e23-46f0-9476-5c794b004841**입니다.

### 1. 같은 삭제 작업의 SQL 확정

UI 작업 내역에서 기존 operation **18dc6b50-841f-4980-8eab-2c1a6409fa58**을 열어 복구 재개1회를 실행했습니다.

- 원 request **caf503d6-4ade-4381-805e-9fbb57d80027** 유지.
- 재개 job **fdb855d5-7964-4e60-a61e-991c67cc3ff2**, API jobstatus1.
- 결과 **COMMITTED / COMPLETED / base36→target37 / result=true / canresume=false**.
- 새로운 delete 요청·게스트 재삭제·수동 DB 변경 없이 기존 영수증과 저장 후보로 확정하고 ACK했습니다.
- UI 목록을 업데이트해 p1455-child 제거를 확인했습니다. 물리 projects/team-a는 유지되고 parent NFS와 SMB445/1445에서 원본 파일의 동일 hash·UID/GID·mode를 확인했습니다.

### 2. 적용 중 의도한 실패 원복

모든 client mount를 해제한 뒤 UI에서 p1455-sibling의 읽기 전용 변경을 사전 점검했습니다. 전용 NFS unit의 **다음 ExecStartPre 한 번만 실패**시키는 /run dropin을 명시적으로 주입했습니다. 적용과 원복은 제품 UI/transaction이 수행했으며 시험 도구로 service restart나 설정 복원을 실행하지 않았습니다.

- operation **be1417ba-c346-479f-b5e6-f838ad8d28a4**
- request **f6d4a043-47e6-4393-ba51-97983b15f037**
- job **1d7e35ba-64e8-4282-89ef-f27ec7763de1**: 의도한 적용 실패로 jobstatus2/530.
- 결과 **ROLLED_BACK / COMPLETED / rollback SUCCEEDED**, base37/target38, SQL 실제 revision은37 유지.
- UI에 최초 **PROTOCOL_APPLY_FAILED · 적용**과 **원복 확인 성공**이 표시됐고 재시작 후에도 진단이 남았습니다. journal의 EPIC1455_EXPECTED_ONE_SHOT_START_FAILURE와 다음 정상 기동을 확인했습니다.
- hook/marker를 제거했습니다. NFS unit은 자동 원복으로 active였고 도구의 수동 service start/restart는0회입니다.
- 원복 전후 설정 hash·기존54객체·시험 트리 metadata/내용 전부 동일. NFS는 RW로 유지됐습니다. 이후 client1의 NFS 쓰기→SMB445 읽기/새 파일 쓰기→NFS cmp·owner/GID 확인을 통과했습니다.

### 3. 실제 UI SystemVM 재시작

UI의 공유 파일 시스템 재시작(정리=false)을 실행했습니다.

- restart job **0ff86afc-efbb-474a-baf3-e1c09398cd65**, API jobstatus1, UI 사용 가능.
- VM stop/start와 네트워크/서비스 준비를 포함해 약146초가 걸렸습니다. 완료 전에 client 접속을 실행하지 않았습니다.
- boot ID가 실제 변경됐고 기존 VM·DATA·원래 IP/MAC을 유지했습니다.
- 부팅 전후 **기존54객체 및 그때 존재한 시험 객체 전부의 내용/hash·owner/group·mode·ACL·inode 동일**, FS UUID와 설정 hash 동일, 실패 hook 부재를 확인했습니다.
- client2에서 parent/sibling NFS 재접속·client1 파일 cmp·새 쓰기, SMB **445/1445**에서 NFS 파일 읽기와 새 파일 쓰기, client1에서 두 파일 교차 읽기·hash 일치를 확인했습니다.
- child는 **65534:65534 /2775 /inode25165961** 유지. NFS 새 파일은65534:65534/0664, SMB 새 파일은1001:65534/0660으로 확인했습니다.

### 4. 관리 서비스 재시작·이력/데이터 보존

mold.service를 재시작해 MainPID **669144→670921** 변경과 JAR 불변을 확인했습니다. 이는 관리 서비스 재시작이며 관리 OS 재부팅을 했다고 주장하지 않습니다.

- 실제 UI 재로그인 후 작업 이력 **42건**의 id/requestid/state/phase/base·targetrevision/created/updated/completed/originalfailure/rollback을 재시작 전과 대조해 전부 동일했습니다.
- UI에서 같은 원복 작업 상세를 열어 최초 실패와 원복 성공 진단을 재확인했습니다.
- client1이 NFS로 새 파일을 쓰고 두 client가 SMB445/1445의 새 연결로 읽어 NFS와 cmp/hash 일치를 확인했습니다.
- 최종 active SMB 관리 리스너2개에서 strict 섹션 조회가 통과했고 inheritowner=no·forceUser/forceGroup 없음, 기본 smbd inactive를 확인했습니다.
- 최종 보존 비교: 기존54객체와 이전 시험8파일/디렉터리의 내용·hash·UID/GID·mode·ACL·inode 유지. 이번에 만든 파일7개는 실제 client가 쓴 내용과 교차 읽기/hash로 확인했습니다. 시험 주입·마운트4개·런타임 암호 변수 제거와 두 콘솔 로그아웃·임시 탭 정리를 마쳤습니다.

## 인수 기준별 판정

| 인수 | 근거 | 판정 |
|---|---|---|
| 같은 볼륨 형제/상하위 NFS·SMB 교차 접근/보존 | R3 parent/child/sibling·동일 경로 alias와 두 client, R4 삭제 이후·원복·재시작 후 실제 접속/쓰기/보존 | PASS |
| 경로 충돌·unsafe path·권한 불일치 처리 | R3 UI 탈출·실제 symlink·중복/권한 채택 보호, R4 native6 보호 재확인·DAO 잘못된 영수증 거부 | PASS |
| 생성/수정/삭제/실패 원복/재부팅 후 비대상 보존 | R3 생성·명시 정책 변경, R4 같은 삭제 확정·단발 실패 보상·실제 UI VM 재시작·관리 서비스 재시작/보존 | PASS |
| 자식 존재 parent 삭제·참조 DATA detach UI 차단 | R3 실제 UI 보호, 이번에 보호 코드 변경0 | PASS |

R3에서 통과한 parent 삭제/detach·경로 이탈/중복 등을 이번에 전부 새로 실행했다고 주장하지 않습니다. R4는 당시 남았던 삭제 확정·실패 원복·재시작과 접속/보존을 마쳤으며 이전 근거와 구분합니다. parent retarget의 별도 새 UI 시나리오나 다른 Epic 기능을 추가 범위로 확대하지 않았습니다.

표시 차이만인 완료 뒤 오래된 heartbeat/worker 경고, 일부 SMB 관측의 알 수 없음, 이미 열린 탭의 목록이 업데이트 전 예전 행을 표시하는 점은 #1468 최종 UI 정리 검토 항목으로 남깁니다. 실제 COMMITTED/ROLLED_BACK 판정·수동 업데이트 후 목록·변경 보호·실제 UID/GID·접속/데이터는 위 근거로 확인했습니다. 이번에 해당 UI 정리 구현을 시작하지 않았습니다.

## 증거와 종료 범위

- evidence/: 실제 guest 입력/주입6, 실패 hook 설치/소비/제거, 실제 DAO46 결과·빌드 조건·배포1class 무결성, 전후 snapshot/보존 비교, 실제 UI가 발생시킨 API(인증정보 제거·관련 계약 필드 선별·반복 폴링 중복 축약, 원본 hash/요청 횟수 포함), 이력42건 대조.
- screenshots/: 삭제 확정·원복·실제 noVNC 접속/I/O·재시작 이후 상태/진단 보존. 인증정보는 저장하지 않았습니다.
- #1455 완료 결과를 이슈와 기존 Epic PR에 기록한 뒤 이슈를 닫습니다. Epic 전체 완료나 전체 CI 통과·PR 병합으로 해석하지 않습니다. Epic PR은 나머지 과제를 위해 Draft를 유지합니다.
- 다음 대상은 **#1456 [SharedFS][SMB] 공유별 force user/group 기반 고정 POSIX UID/GID 지원**입니다. 이번에는 착수하지 않습니다.
