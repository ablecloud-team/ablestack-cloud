# #1450 R3 — 초기 UI intent 수정·배포 후 지연 적용 POSIX 결함으로 중단
2026-10-27. **OPEN / 추가 제품 변경·재시도 중단.**

## 이번 요청의 수정 및 통과 결과
- canonical WSL /root/work/ablestack-cloud, epic/1438-sharedfs. origin/upstream fetch 후 local base와 upstream/ablestack-europa 차이0/0 확인.
- UI CreateSharedFS.vue 7줄만 추가. createStorageNfsExport의 실제 clean 생성 파라미터에서 name/path/posixchanges를 동일하게 점검에 전달한다. recursivepermission=false는 서버 fields()와 동일하게 POSIX 명시 변경에서 제외한다.
- 서버 보호·4096 UTF-8 byte 제한·SystemVM 코드는 이번 R3에서 변경하지 않았다.
- commit **1fbd4d56351959ebe2e9784f3e6ecd4d601043d3**.
- 실제 이전 실패 API 응답 fixture와 mounted CreateSharedFS 회귀5건 추가. 수정 전5실패/기존4통과, 수정 후 관련4suite **41통과**, 지정 파일 lint통과. RO/no-root/imported의 생략 보존·확인 거절·export COMMITTED 이후 ACL을 포함한다.
- UI production build 성공(Node20/webpack4에 필요한 openssl-legacy-provider 사용). R3 source hash 고정·일치 확인.
- UI12 정적 파일만13번 배포. config/SystemVM 폴더 보존. index SHA256 **497ab6dd4e3637aa05a83a88124234209fa52056ca6bc3d2e6f71b5400eab15f**, module SHA256 **0e10d4bd708830d267dccfccc6ebbfbb34b6fcf90d29993934abfee22cd7f079**.
- management JAR761de3...와 최신 SystemVM template ee2be8d7-6b18-417b-b2a7-a52c7bc14758는 이전 R2 배포본 유지.
- 새 탭에서 app.fc723dfc.js 로드를 확인했다. 실제 UI 요청에서 name/path/volume/ownerUid/ownerGid/mode/ack 동등성과 recursivefalse 미포함 **8항목 모두true**.

## 새 자원 실제 UI 시험 결과
이전 실패 VM·DATA를 재사용하지 않았다.
- SharedFS **7421dd91-7e8a-4faf-a54b-a06320579374**, 이름 epic1438-1450-initial-r3-20261027.
- instance **9a49d3ec-b543-4eaa-a57f-bd877548414a**.
- 새 VM **fcbb55c6-c422-3a0d-9cd5-4a9d94b8a643**, i-2-147-VM, host13.1.
- 새 ROOT **d88572cf-7f51-4f0a-9029-4096aee22447**, 새 DATA **b67f2980-6295-4e5d-929b-db07b829e5e9**. UI에서 둘 다 sparse 확인. DATA10GiB/XFS.
- IP10.10.13.198/16는 실제 클라이언트 콘솔에서 ARP DAD3회 응답0 후 UI로 설정했다. MAC 임의 변경0.
- NFSv4/RW/root squash/CIDR10.10.0.0/16, export **10300b5f-0a8d-42c8-9a20-4172af05c407** e1450-r3, path/export/e1450-r3.
- 프로토콜 작업 **5d6f7574-6928-4162-b20d-90102e05386c** COMMITTED.
- export 작업 **9f83a3d9-f4f0-477a-9894-4d455d2062ba** COMMITTED. 실제 preflight와 apply 모두 ownerUid65534/ownerGid65534/mode0775, fingerprint66d633... 일치. 앞선 IMPACT_ACK_REQUIRED 차단은 해소됐다.
- ACL 작업 **88de783a-6cc4-4c7f-90d0-24865892c0c0** COMMITTED. ACL **f83a6dbb-e7b7-412f-aa64-f0ebfa4f76be** Ready.
- 실제 NoVNC Linux client10.10.254.195에서 NFS 연결 성공(findmnt nfs4/rw, NFS4.2). UI에서도 ESTAB session 확인.
- **파일 생성은 Permission denied.** 클라이언트 ls 및 SystemVM stat 모두 실제 root가 **0:0/0755**. 요청65534:65534/0775가 초기화되지 않았다. 시험 파일0개. 이 시점부터 추가 적용/재시도/권한 우회/삭제0.

## 정확한 하위 원인 — 실제 SystemVM journal 확인
1. 초기 UI는 ACL 준비 전 공유를 게시하지 않기 위해 deferapply=true로 export를 생성한다(CreateSharedFS.vue2454).
2. createStorageNfsExportCandidate는 prepareFileShareBackingVolume으로 디렉터리를 만든다. 실제 lastInspection은 directoryCreated=true, configurationOperationId=**9f83...**를 보존했다.
3. deferapply 분기는 applyNfsDesiredState를 생략한다(Manager858-862). export의 실제 SystemVM request.json은 **candidateCommands=[]**, POSIX apply 명령0으로 COMMITTED됐다.
4. 뒤의 ACL 작업이 nfs export apply를 실행한다. 실제 payload:
   - configurationOperationId=**88de...**(ACL 작업)
   - posixIntent={create:false,fields:{},targetUuid:**f83a...**}(ACL UUID)
   - export.uuid=**10300...**(export UUID)
   - lastInspection.configurationOperationId=**9f83...**(생성 작업)
5. nfs_posix_apply_config(824-849)는 현재 렌더에서 막 만든 디렉터리이거나 동일 작업의 생성 영수증일 때만 초기화를 허용한다. ACL 시점에는 경로가 이미 있고 UUID·작업ID가 다르며 명시fields도 비어 있어 **{}를 반환**한다. 따라서 chown/chmod0회다.
6. NFS·ACL 게시 자체는 성공해 작업이 COMMITTED로 보이지만 root squash 사용자의 디렉터리 쓰기는 실제 POSIX0:0/0755에 막힌다.

SystemVM transaction SHA256 **3f928c61d8c6024b65025841e85d6003f645e49d96f5ff667e3a39ce0c25de19**로 현재 제품 코드와 일치한다. 네트워크·배포 버전·UI 표시만의 문제가 아니다.
이번 #1450에서 기존 권한을 보호하려고 정상 렌더의 저장 POSIX 자동 재적용을 막았지만, **서로 다른 export 생성→ACL 게시 작업으로 나뉘는 지연 초기화를 연결하지 못한 구현 누락**이다.

## 최소 영향 개선 방향 — 미구현
- UI의 올바른 intent 전달은 유지한다. deferapply=false로 단순 전환하면 ACL 전 공유가 노출될 수 있으므로 그렇게 우회하지 않는다.
- **NFS CREATE+deferapply 경로에서 원래 생성 transaction 안에 권한 초기화를 끝내는 범위로 한정**한다. 신규 디렉터리의 POSIX metadata만 적용·검증하는 내부 단계로 연결하고, export 게시/ACL 생성은 기존 순서를 유지한다.
- coordinator가 적용할 export UUID·명시 POSIX intent·동일 작업의 directoryCreated 영수증을 함께 전달한다. 기존 helper와 metadata before-image/실패 원복을 재사용한다. 동일 작업 신원·stat검증·imported preservation·재귀기본false를 유지한다.
- ACL/boot/일반 재렌더에서 과거 저장 owner/mode를 무조건 다시 적용하는 수정은 하지 않는다.
- COMMITTED 전에 요청한 metadata의 실제 stat을 검증해야 한다. 값이 적용되지 않은 상태를 성공으로 처리하지 않는다.
- 구현 전에 실제 SystemVM의 격리 경로/전용 신규 디렉터리에서 **export deferred 생성 작업→ACL 게시 작업이라는 두 작업 경계**를 주입 시험한다. 현재41개 UI 시험은 backend commit fixture로 흐름을 검증하므로 guest의 실제 권한 결과를 입증하지 않는다.
- 이후 신규 준비 DATA/기존 경로/imported 보존/실패 원복의 실제 coordinator·guest 회귀와 새 VM·새 SPARSE DATA의 UI 생성·ACL·클라이언트 쓰기를 검증해야 한다.

## 상태 및 보존
- #1450 OPEN, Epic PR1470 Draft. 다음 이슈 착수0. 새 자원·진단·클라이언트 mount는 보존했다.
- local 구현4커밋1af85bd056dc/a67726e86b5c/4d5bf7b458ea/1fbd4d563519는 보존, 아직origin에push하지 않았다. PR에 완료 코드 반영을 주장하지 않는다.
- 이번에는 UI 표준 레이아웃을 바꾸지 않았고 R2의 VM 표준·다크모드 검증을 보존했다. locale/timestamp 표시 항목은1468 정리 대상으로 유지한다.
- NoVNC가 Shift 기호를 잘못 전달한 초기 mount/Python/redirect 입력은 시험 입력 실수로 스스로 교정했다. 실제 성공한 NFS mount 후 tee의 Permission denied와 guest stat으로 제품 결함을 구분했다.
- API trace에는 수집 공백이 있다. 초기 intent/apply 계약과3개 COMMITTED 작업, native journal/stat, UI/클라이언트 증거만 확정 근거로 사용한다.
