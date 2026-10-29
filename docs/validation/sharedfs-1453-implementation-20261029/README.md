# #1453 구현·빌드와 실제 SystemVM 사전 검증

2026-10-29 기준 **구현과 모듈 빌드 완료, 13번 배포 및 제품 API·실제 UI·클라이언트 인수 전**이다. 이슈는 OPEN, Epic PR #1470은 Draft를 유지한다. 아래 사전 시험을 제품 기능 인수로 대체하지 않는다.

## 구현

제품 commit: `b2459c366ee2dfea6bc87bda8a62c745488893bd`.

- SMB Create/Update의 optional Boolean `inheritowner`를 기존 config_json에 저장한다. 미변경 비제출·명시 false·기존 키 보존을 유지한다.
- 부모 GID 상속은 #1452 공통 정책의 mode에서 setgid 비트만 변경한다. 새 DB·작업 유형·포맷 경로는 없다. UID/GID·inode·다른 특수 비트와 기존 하위 객체는 자동 변경하지 않는다.
- public preflight와 실제 command가 같은 bool·정책 UUID·부모 관측을 사용한다. 실제 API serializer가 만든 응답 fixture로 UI 계약을 검증한다.
- 저장 설정과 소유권이 검증된 실제 Samba endpoint의 force identity 충돌을 검사한다. 기존 masks·AD 가입·NFS squash는 바꾸지 않는다.
- 기존 coordinator·shadow DAO·revision·영향 ACK·journal·실패 원복을 재사용한다.
- 기존 MoldDialog/Actions와 공통 정책 탭을 사용한 작은 상속 폼을 추가했다. 미확인/DRIFT·보호 상태·만료·늦은 응답·재진입을 보호한다. 실제 배포 후 VM 표준 및 라이트/다크 시각 인수는 남아 있다.
- updater5가 활성 상속 기능을 지원하지 않는 target의 downgrade/rollback을 막는다. 관리 파일10개 계약과 서명 검증을 유지한다. 기존 updater4 VM에 일반 번들만 적용한 것을 보호 완료로 판정하지 않는다.

[승인 설계와 A/B/S 목업](../../design/sharedfs-1453-parent-inheritance-20261028/README.md)

## 시험·빌드

| 구분 | 결과 |
|---|---|
| 서버 실제 API 직렬화·정규화·기존 NFS/POSIX·coordinator→실제 DAO 저장/재조회 | 27 tests PASS |
| 실제 컴포넌트·API 응답 fixture·기존 보호/NFS/SMB 회귀 | 6 suites /58 tests PASS |
| 변경 UI lint | PASS |
| guest POSIX·상속·서명 updater/기존9·10파일 회귀 | 38 tests PASS |
| 관리 서버 모듈 및 의존 모듈 | 151개 모듈 package PASS |
| Vue production build | PASS |
| 서명 guest 모듈·QCOW2/bzip2 이미지 | PASS |

첫 UI 시험 실행기에서 존재하지 않는 spec 파일명을 지정한 오류를 고쳐 실제 `SharedFSPreflight.spec.js`로 실행했다. 이는 시험 실행기 오류이며 제품 장애로 분류하지 않는다. 이미지 profile은 변경 payload의 실제10파일 해시로 재생성한 뒤 서명/반복 bootstrap 회귀를 통과했다.

[시험 요약](evidence/verification-summary.json) · [빌드 산출물·SHA256](evidence/build-artifacts.json)

## 실제 SystemVM 후보 주입

SharedFS `1f9d218d-4fc1-4c7c-a96d-527cb0801436`의 SystemVM `8c3a6de9-41cc-3cae-a61d-4f7d3d876526`에서 기존 **XFS10GiB SPARSE** DATA의 nonce 하위 경로와 private mount/network namespace·Samba TDB·데몬을 사용했다. 포맷·운영 파일 교체·MAC 변경은 하지 않았다.

후보 renderer의 실제 AST와 후보 common POSIX 엔진을 주입해 부모2개 × inherit owner on/off × setgid on/off **8조합/40개 새 객체**를 확인했다. READ_ONLY/미인가 거부, 바로 위 부모의 독립 상속, NFS23049/23050 교차 I/O, Samba reload/restart, 중복 무적용, peer 실패 ROLLED_BACK/SUCCEEDED를 포함해 **7개 검사 그룹 PASS**다.

setgid 활성화 전에 실제 testparm의 고정 identity도 검사한다. 기존 특수 비트가 이미 켜진 경로의 다른 legacy 변경에 새 활성화 조건을 적용하지 않는다. 새 검사에 대한 실제 POSIX 회귀는 부모 메타데이터와 seed 보존·충돌 거부·적용/원복을 확인했다.

별도 실제 SystemVM 시험에서 정상 서명·profile·10파일 검증, updater5의 최초/반복2회 bootstrap, private mount namespace에 활성 feature 상태를 주입한 실제 compatibility guard의 구형 target 거부를 확인했다.

두 시험 모두 **productionUnchanged=true / temporaryResourcesRemoved=true**다. 운영 코드·설정·계정·PID/NIC·기존6파일은 보존됐다. 이는 새 제품 API/UI transaction·실제 VM 재부팅 인수의 대체 결과가 아니다.

[후보 주입 코드](evidence/native-implementation-probe.py) · [상세 결과](evidence/native-candidate-result.json) · [실제 서명/bootstrap/guard 결과](evidence/native-signed-bootstrap-result.json)

## SystemVM 이미지 무결성

런타임 `epic1438-1453-inherit-b2459c366ee2-r1`, updater5. 기존 cluster13 신뢰키로 서명했고 개인키는 DPAPI CurrentUser에서 런타임에만 복호화해 memfd로 전달했다. 개인키/운영 암호를 파일이나 저장소에 기록하지 않았다.

압축 이미지 SHA256 `b9a511a9ef6f76cb7525e53bb257f75e6ba08c5e99950bda537647ae2d35ce32`.

검증된 baseline 이미지와 비교해 예상8경로만 바뀌었고, 나머지33,929경로와 WSL 호스트 계정 파일은 보존됐다. qemu-img check/bzip2 test/서명 payload 검증을 통과했다. 아직 13번에 등록·배포한 이미지가 아니다.

## 13번 배포 기준의 외부 변경과 남은 인수

이전 #1452 배포 JAR SHA256은 `39cd997647853bab1f686d6745eafa680f6e5a03e5acf56df80c11bd942be46c`였다. 이번 시작에 RPM 이름의 `cloudstack-4.23.0.0-ABLESTACK.Mold.jar`로 바뀌어 있었고, 확인 중에도 manifest revision이 `2931c1c45b43`에서 `1bf8708827ed`로 다시 바뀌었다. 최근 관측 SHA256은 `e3030f280a01fb49957b94db62d1cb9c208434a5fae319c8ee9960527860a67d`다. 현재 JAR에는 #1452 공통 디렉터리 API class가 없다. 변경 주체와 다른 배포 종료 여부는 확인되지 않았다.

DB는 읽기 전용으로 확인했다. version4.23.0.0, 기존 storage_directory_policy14컬럼/1행과 기존 구성 작업 테이블이 남아 있다. #1453 신규 DDL은 없다. 임시 조회 helper만 실행/정리했으며 DB를 변경하지 않았다.

다른 배포와의 충돌 여부를 사용자에게 확인 요청했다. 기존 플러그인·설정과 현재 배포본을 보존하는 배포 준비까지만 진행하고, 현재 실행 JAR/UI는 덮어쓰지 않았다. **배포 완료·제품 오류 수정 완료·이슈 인수 통과로 기록하지 않는다.**

또한 현재 SharedFS는 로컬 인증/AD 미가입이다. 기존 ADSvr은 현재 VM 이름 검색에서 확인되지 않았고 현재 Windows 후보2대는 QGA 미연결이다. AD 대상 확인을 요청했으며, 미검증 AD를 성공으로 기록하지 않는다. 관련 없는 사용자 VM 설정은 변경하지 않았다.

남은 순서는 배포 충돌 해소 → 관리/UI 및 updater5 SYSTEM 이미지 배포 → 실제 UI에서 새 SPARSE VM/ROOT/DATA/클라이언트 준비 → NFS/SMB 공통 정책 등록과 두 상속 설정/해제 → 실제 API 대조/클라이언트 UID·GID·mode·RO/미인가·교차 I/O·기존 데이터 보존 → 충돌·동시 변경·원복·UI 재부팅/재접속 인수다. 실제 AD는 확인된 지원 도메인을 사용해야 한다. 통과 전 #1453을 닫지 않는다.
