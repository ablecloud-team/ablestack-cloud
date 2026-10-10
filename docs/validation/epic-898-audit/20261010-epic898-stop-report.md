# Epic #898 중간 완료·작업 중단 보고

사용자 지시에 따라 현재 진행 중인 OWNED 시험 프로필 저장·설정 건만 마무리하고 작업을 중단한다. all4 기준 구성 검증 writer, POSIX/원복·cold, 백업/F2/SYSTEM/ROOT/AD/작업 제어/대용량 재시험과 최종 UI #1275는 새로 시작하지 않는다.

## 현재 코드·배포·검증

- 기준 브랜치 ablestack-europa와 upstream 차이는0 0이다. 구현은 epic/898-sharedfs에서 커밋하고 origin에만 push했다.
- 통합 PR #1271은 OPEN/Draft다. 모형 PR은 닫혔고 실제 구현을 하나의 PR로 통합한다. 미병합 상태다.
- 최신 제품 소스978ec10a의 관리 모듈994 tests/130 suites, failure/error/skip0 및 Checkstyle 통과. 핵심 OWNED admission22개와 독립 검토도 통과했다.
- 실제 관리 JAR fa751f36d1452a15ab84f56410554f29947412a8714ee64aa42c2dbbe32bb9db/PID1430252에 Manager/Profile2클래스만 반영했다. 다른 클래스·라이브러리9·Runtime3·UI3495·config·trust53을 보존했고 정상API3Up/8Running과 자원 참조를 확인했다.
- 게스트 sourceff1/CLI9b1의 정상480 tests/51그룹, UI source20a의373 tests/22 suites·lint·production850 검증을 통과했다. UI는 필요한6자산만 반영했다.
- 실제 F1의 ROOT+FILE2+RAW2가 모두 SPARSE다. NFS/LOCAL SMB 및 iSCSI CHAP·상호 CHAP·NVMe DHCHAP/상호DHCHAP을 API와 실제 UI 및 외부 클라이언트로 검증했다.
- iSCSI READ_ONLY가 실제 쓰기를 허용하던 결함을 수정해 exact SCSI status2/sense7/ASC0x27/ASCQ0 거절·쓰기0을 확인했다. 공통 ACL writer 범위가 블록 ACL을 NFS 조회로 거절하던 결함도 수정·배포하고 같은 ACL의 실제 갱신 성공을 확인했다.
- NVMe C1 single4096@1MiB·fsync/namespace FLUSH0·first/fresh31a8 및 C2 stable publication READ/fresh31a8를 확인했다. 최종 연결/namespace/devnode0, RAM 자격 폐기, direct data·기존 iSCSI4080/FILE/NFS/SMB/ROOT를 보존했다.
- 마지막 현재 건: 새 보호 store leaf를 service UID985 소유0700으로 준비하고 artifact b252의 정상 제품write/readback(2108B/0600/SHA 일치)을 완료했다. normal configure job4bd3fffe-8d63-4981-b114-72690df55ea7는 status2/result530, 정확 오류 Fresh installed rendered generation handler proof is unavailable로 실패했다. Profile CAS 저장 전 handler 검증 경계이므로 활성화·profileRev1 성공을 주장하지 않는다. 현재 건은 실패로 기록하고 재시도하지 않는다.

## 하위 이슈별 상태

| 이슈 | 현재 확보한 결과 | 남은 완료 조건 |
| --- | --- | --- |
| #911 런타임 코드 인플레이스 업그레이드 | CLOSED. 후속 서명 CODE도 실제 UI COMPLETE100/API 성공 및 설치 해시·health·availability 검증 | 이번 중간 보고에서 다시 열거나 완료 범위를 확대하지 않음 |
| #1333 생략된 볼륨 경로 보존 | CLOSED. 해당 범위의 수정·검증 완료 | 없음 |
| #1269 상세 무한 로딩 | 초기 지연·오류 종료/재시도·정합성 보완, 실제 로그인·목록/직접상세·새로고침·light/dark 확인 | 실제 부분 실패·request coalescing 등 미확인 인수 조건 |
| #892 원자적 롤백·상태 복구 | 원 SOURCE 정상 원복, 프로토콜 생성/인증·데이터 보존, 실제 iSCSI 권한/ACL 범위 수정, NVMe 실I/O, OWNED profile 지원 | 최초 rendered all4/LKG 승격, 네 프로토콜 create/update/delete 실패·응답 유실·원복 전체 인수 |
| #900 SMB endpoint / #907 IP·CIDR | LOCAL SMB 인증·RW/RO·CIDR·fresh I/O subset 검증 | 두 IP 독립 I/O/삭제·cold 및 남은 조합 |
| #894 squash / #903 POSIX / #906 숫자 UID / #910 중첩 공유 / #915 상속 / #916 하위 ACL / #919 mask / #908 force UID | 기능 코드·모듈 회귀 및 NFS/LOCAL SMB 기본 subset 있음 | all4 기반 실제 정책 조합·소유권·상속·mask·symlink/bind·cold·연속성 인수 |
| #891 온라인 scale / #896 offering / #904 기존 볼륨 초기 생성 / #905 고유 용량 | 제약·선택·SPARSE/기존 볼륨·용량 구현/회귀 및 실제 재사용 subset | 실제 scale/안내·전체 볼륨 용량·NEW/EXISTING 초기 생성 요구 전체 |
| #913 게이트웨이 / #914 다수 Export / #918 제한 probe | 소스·정상 회귀 및 NFS 실제 subset | 재기동 지속성·export1/10/100·probe 실패 격리·부하/연속성 |
| #909 백업·업로드·LKG 복원 | archive/검증/다중 NEW allocation·same-ID 재개 등의 구현·회귀, 이전 UI ZIP 저장 확인 | 현재 all4 정상 backup/download/upload/plan, F2 NEW·EXISTING, tamper/stale/LKG·응답 유실 실제 인수 |
| #920 SYSTEM/ROOT 교체 | template pin·provision/provenance·ROOT identity/rollback 구현·회귀, SYSTEM-A/F2 계획 준비 | SYSTEM 등록·F2생성·실제ROOT교체/보상·retained bootstrap·cold/모든인증복구 |
| #924 런타임 카탈로그 | 실제 UI REGISTERED→VERIFIED→AVAILABLE, 서명/해시/consumer 업그레이드 검증 | 신규 적용 중지·지원 종료·폐기·사용 중 consumer 보호·권한/호환성 전체 |
| #902 SMB AD·별칭 | ADSvr/C3 DNS·Kerberos prerequisite, Sysprep backup/generalize 및 관련 코드·회귀 | F3 서버 AD가입·DNS/SPN별칭·ADACL/실I/O·backup/ROOTrestore, Windows OOBE 후 clientAD 인수 |
| #897 장시간 작업 / #895 삭제 보존 | writer/lease/control·queue·삭제 계획/보존·실패 재개 구현·회귀 및 일부 실제 보호 검증 | cancel/drain/expiry/release실패/queue·RECOVERY hold, preserve/detach/delete 실제 전체 |
| #974 대용량 포맷 | 부분 포맷·timeout/재개 가능한 준비 기능, 기존 실패볼륨 증거와 데이터 보존 | 정확 새 SPARSE10TiB 재시험·reconcile/resume 실제 종결 |
| #1275 최종 UI 표준 | OPEN·미착수. 기존 목업 이미지 연결, 기능상 누락 입력만 별도 보완 | 사용자 추가 지시 전 시작하지 않음 |

하위30개 상태는 GitHub를 직접 다시 조회했다. 28OPEN/2CLOSED(#911/#1333), Epic898OPEN이며 이번 중단 보고에서 새로 닫는 이슈는 없다.

## 지연·차단 요인

1. **기능 검증에서 실제 결함 발견:** 블록 기본 포트2049, iSCSI 읽기 전용 권한 누락, 블록 ACL 공통 범위 오분류를 먼저 수정·정상빌드·제한배포·API/UI/client 재검증했다. 현재 해당 subset은 해결됐다.
2. **검증 도구의 오류·잘못된 기대값:** SSH memfd offset, 응답/파일명/출력순서, cached CAP, 조기 namespace identity pin, old JUnit classpath, mutual NONE 거절 기대값 때문에 도구를 수정하고 재검증했다. 원 실패를 성공으로 바꾸지 않았다. mutual target 인증은 요청된 경우를 부정시험으로 사용했다.
3. **버퍼드/direct 읽기 차이:** 서버 buffered0과 O_DIRECT31a8을 실제로 구분했다. direct 읽기가 client first/fresh와 같고 bounded 외영역도 유지됨을 확인했으며 cache flush/재포맷하지 않았다.
4. **native begin530:** 원op0ac827/rev29BLOCKED의 정확 native 원인은INFO 로그에 남지 않아 미확정이다. current28/pendingnull/idle/데이터 보존을 확인하고 새 idempotency+expectedrevision의 정상 writer29/30은 성공했다. 원 실패 audit은 보존되며 정상 superseded reconcile은 아직 미실행이다.
5. **all4 시험 profile24h:** 두 FILE이24h경과했고 추가FILE NEW 생성job조회0으로, NEW 출처를 주장하지 않았다. 기존 NEW조건을 유지하고 protected OWNED시험profile+매 사용 DAOnoBacking/범위/CAS 검증을 구현·994개정상검증·실제2클래스반영했다.
6. **보호 store 미준비:** defaultleaf absent/root0755parent에service985생성권한없음과 호환되는 store거절을 확인했다. 부모/기존stores를 변경하지 않고 새전용leaf service-owned0700을 provision하고 정상 제품write/readback을 완료했다.
7. **현재 직접 차단:** OWNED profile 설정이 Fresh installed rendered generation handler proof is unavailable로 거절됐다. CLI9b1 CODE 인수와 rendered handler 선언·응답 소비 조건은 별도이며 아직 원인 추가분석을 하지 않았다. 사용자 중단 지시에 따라 코드 변경·재제출·all4 검증을 시작하지 않는다.
8. **사용자 개입 조건:** Windows SMB-Client OOBE의 약관·Administrator 암호 직접입력, 정확 신규SPARSE10TiB의 pool/factor/headroom 선택이 남아 있다. 지금 중단 요청에 따라 반복 질문하지 않는다.
9. **현재는 사용자 요청에 따른 중단:** all4기준검증·정책/원복/cold·backup/F2/SYSTEM/ROOT/AD·작업제어/대용량 및 #1275는 후속 미실행 상태다. 위 미확정native원인과 human조건을 전체 작업의 완료로 숨기지 않는다.

## 중단 시점 및 산출물

- OWNED profile 저장은 완료, 설정은 위 handler 검증 오류로 실패했다. 마지막 실제 보존 읽기8b1b7417을 통과했고 모든 작업 세션·RAM 인증 FD를 종료해 중단·대기한다.
- 새로운 all4 verifyBaseline writer나 후속기능은 시작하지 않는다.
- 관리·게스트·프로필 자원은 삭제/초기화하지 않고 그대로 보존한다.
- 통합 PR1271은 Draft로 유지하고 향후 재개 때 남은 실제 인수를 계속한다.


마지막 보존 확인: actualGEN30/op1df2/config498d/CLI9b1/BOOT 동일, pendingnull/writeridle/session0·ownTCP0/WP0·1, NVMe O_DIRECT31a8/iSCSI4080·헤더/앞뒤·FILE/NFS/SMB/ROOT 유지. profile absent/revision0 지속은 CAS 전 실패에 따른 **소스 추론**이며 새 DAO 조회로 확인한 값이 아니다. 모든 실행 세션·RAM 인증FD는 종료했고 설정 재제출·all4 verify·BLOCKED reconcile은0이다.

[보호 store 저장](owned-all4-profile/actual-owned-b252-provisioned-store-public-proof.json), [설정 실패](owned-all4-profile/normal-owned-b252-profile-configure-public-proof.json), [마지막 실제 상태 보존](owned-all4-profile/after-profile-configure-preservation-public-proof.json).

관련 문서: [iSCSI 실제 인증·권한](20261010-iscsi-permission-fix-normal.md), [ACL 범위 수정](20261010-iscsi-acl-writer-scope-normal.md), [NVMe 실제 UI·인증·I/O](20261010-nvme-ui-auth-io-actual.md), [OWNED profile 소스·정상 모듈·배포](20261010-owned-all4-profile-normal.md), [상호 CHAP UI 실제 검증](20261010-mutual-chap-ui-normal.md).
