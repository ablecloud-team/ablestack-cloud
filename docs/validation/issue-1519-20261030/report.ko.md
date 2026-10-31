# #1519 VM 복제 API 입력 계약 검증

검증일: 2026-10-30 KST. 대상: Epic #1510 / P2 CL-05.

## 결과

복제 API의 기본값과 입력 검증을 정정했다. `count`, `startvm`, `clonetype` 생략은 **1대·시작 끔·Full**로 동작한다. 수량은 1~50이며, 미지원 타입·소유자·도메인·Zone 변경과 잘못된 이름은 Job 생성 전에 431 입력 오류로 거절한다. 기존과 동일한 계정/도메인/Zone을 명시한 호출은 성공했다.

22 RBD explicit, 32 RBD legacy 및 31 GFS2에서 실제 UI/API → Job → DB → 이미지/게스트를 대조했다. 복제 Job 8건이 모두 성공했으며, UI 복제 5건으로 7대, API 복제 3건으로 3대를 생성했다. 준비 원본 2대를 포함한 QA VM 12대와 볼륨 24개를 정상 API로 정리했다. 최종 활성 자원·참조·계정·도메인·오퍼링은 0건이며 물리 이미지 24개 및 관련 임시 이미지/파일도 없다.

집중 검사는 서로 다른 **42개 사례 통과**다. PR 독립 소스의 Maven 검사 24개와 UI 검사 11개, 미병합 #1518과 통합한 정리 회귀 7개로 구분한다. 전체 Cloud/Release 빌드와 전체 Actions CI는 실행하지 않았다.

## 계약과 변경 범위

| 인자 | 최종 계약 |
| --- | --- |
| count | 선택, 기본 1. 1~50 정수. 빈 값·공백·잘못된 형식·0·음수·초과는 431. 기존 50 초과 호출은 제한 변경의 영향을 받으므로 최대 50씩, Job 완료 후 새 이름으로 분할 요청해야 함 |
| startvm | 선택, 기본 false. true/false 및 기존 대소문자 Boolean 해석 유지. 임의 문자열이 false로 묵시 변환되지 않도록 이 API에서 거절 |
| clonetype | RBD는 소문자 full/linked, 생략은 full. legacy/explicit에 같은 검증. Linked는 모든 디스크가 RBD여야 하며 기존 할당 정책 보호 유지 |
| GFS2의 clonetype | 생략/full은 기존 호출의 호환 값이며 복사 전략을 선택하지 않음. QCOW2는 fast clone 후 Running에서 flatten, Stopped에서는 backing 보존/일시 정지. 조건을 만족하는 정지 RAW는 materialized copy. linked는 거절 |
| account/domainid | 소유권 변경 미지원. account에는 원본 domainid가 필요하며 활성 원본 계정과 일치해야 함. domainid 단독도 원본과 같은 값만 허용 |
| zoneids | 생략 또는 원본 Zone 하나. 다른 Zone 및 복수 값은 거절 |
| name | 기존 RFC 이름 규칙 유지. 배치의 `-1`~`-count` suffix를 포함해 각 최종 이름 63자 이내. 중복 이름도 사전 거절 |

API 메타데이터와 getter를 맞추고 create preflight의 `InvalidParameterValueException`을 530 대신 431로 반환한다. 서버의 공통 범위/타입 검사는 explicit 할당 경로에만 한정하지 않는다. async 실행 시 범위/이름을 재검사하며 기존 권한·백업·암호화·할당 보호를 유지한다. 다른 계정·Zone 복제나 배치 응답 개편은 구현하지 않았다.

UI는 기존에 1~50·시작 끔·Full을 사용하므로 정적 소스/자산을 변경할 필요가 없었다. 배포된 API 설명과 실제 UI 요청·오류·상태를 확인했다. [설계](design.ko.md) 및 [공개 검증 요약](evidence-summary.json)을 함께 참고한다.

## 검증 근거

배포 전 32번에서 최소 요청은 `startvm` 누락 431, startvm=false/count 생략은 530으로 실패했다. 배포 후 최소 요청이 실제 VM 1대와 ROOT/DATA를 만들고 Stopped로 끝났다. 세 환경의 최종 listApis에서 count/startvm/clonetype은 모두 optional이며 범위·기본값·지원 인자 설명이 일치했다.

| 경로 | 공개 Job UUID | 결과 |
| --- | --- | --- |
| 32 최소 API, count/startvm/type 생략 | `b889ce97-68e6-45b4-9a9b-184aabc296e2` | 1대 Stopped, Full ROOT/DATA 독립 이미지 |
| 32 UI 기본 Full 1대·시작 끔 | `8a4aae05-d556-4e60-b797-29cf74085247` | 1대 Stopped, 두 디스크 parent 없음 |
| 32 UI Linked 2대·시작 켬 | `f0b0e53f-9aa1-45aa-8840-bce08c3d48fb` | 정확한 두 suffix 이름, 두 VM Running. 네 RBD 디스크의 부모 일치, 두 게스트 QGA/OS/디스크 경로 확인 |
| 32 동일 계정/domain/Zone 명시 API | `cd3741a1-f981-4281-ae1a-3ad868ed6d59` | 1대 Stopped, 원본과 같은 소유 범위 |
| 31 UI GFS2 2대·시작 끔 | `e0a4c543-f055-4283-bbb1-6295ee136968` | 타입 인자 생략, 두 VM Stopped. 기존 QCOW2 backing/flatten pending 확인 |
| 31 전용 두 복제본 UI 시작 | `4f86111c-e201-4824-ad97-eef768df547f`, `8f2bd895-a77b-487c-a92d-bbcf6c762bb2` | 두 VM Running, QGA/OS/두 디스크 경로 확인. ROOT/DATA 4개 flatten 완료/독립 이미지와 DATA 패턴 확인 |
| 22 UI explicit base Full 기본값 | `c6bffc22-2e07-4b99-b57e-8e83cbb606ef` | 허용된 rocky98-base에서 전용 Stopped 원본 생성. 보호 VM을 사용하지 않음 |
| 22 전용 explicit 원본 최소 API | `0b82d7e1-c0d1-4c59-a7c2-faeabaea2e27` | host/lastHost 없는 정지 원본의 ROOT/DATA, 1대 Stopped, 두 독립 이미지 |
| 22 UI explicit Linked 1대·시작 끔 | `3e7307a0-2067-4835-9f18-f0010aa7562f` | 1대 Stopped, ROOT/DATA의 정확한 RBD 부모 및 새 clone 관측 증거. 호스트의 전체 내용 비교를 완료한 뒤 Job 성공 |

복제 대상 9대(준비용 22번 최초 복제본 제외)의 계정·도메인·Zone, 볼륨 크기·device ID·형식·할당 intent가 각 원본과 같음을 API로 대조했다. 최종 대상 디스크 18개의 실제 의존성을 검사하고 DATA 9개의 0x5a/0xa5 패턴을 확인했다. Running 게스트 4대에는 전용 fixture에만 QGA를 사용했다.

최종 잘못된 입력 행렬은 22/32번 각 19건, 31번 20건으로 58건이다. count 생략의 성공 경로와 별도로 0/음수/51/overflow/문자/소수/빈 값/공백, 잘못된 타입/대소문자, 잘못된 Boolean, 소유자/Zone 및 이름/suffix 경계를 검사했다. 세 클러스터 모두 전후 QA VM·볼륨·스냅샷·매핑이 동일했다. 실제 존재하는 다른 계정·도메인 두 요청과 UI 중복 이름 요청까지 입력 검증 61건이 431/Job 없음으로 끝났다. 일반 사용자 역할은 기존 API 접근 제한 432를 유지했다. 이 역할 검사는 resource ACL 전체 조합 시험을 의미하지 않는다.

UI에서는 잘못된 이름과 50대 suffix 초과 이름을 HTTP 요청 없이 차단했고, 값을 고친 뒤 같은 폼의 Full 요청이 성공했다. 중복 이름은 431과 원래 입력을 유지했으며 새 Job을 만들지 않았다. 50 경계는 메타데이터, UI 수량/이름 검증, 서버의 50개 생성 이름 전체 사전 검사 및 실환경의 이름 검증 도달로 확인했다. **실물 VM 50대를 생성하는 배치는 실행하지 않았다.**

## 빌드·배포·정리

PR 브랜치는 upstream Europa에서 분리했다. WSL ext4 전용 checkout에서 `api,server`만 `clean install`하여 PR 소스 검사 24개를 통과했다. UI CloneAllocation 11개도 통과했다. 테스트 서버에 남아 있는 #1518의 복구 로직을 되돌리지 않도록 `df695fc02df` 통합 소스에서 UserVmManagerImpl을 빌드했고, 최종 API 클래스는 `1eaf403fb97`에서 빌드했다. 통합 소스의 기존 정리 회귀 7개도 통과했다. 반복 실행은 서로 다른 검사 개수에 중복 집계하지 않았다.

관리 서버 3대의 기존 JAR에 API/서버 변경 클래스와 관련 내부 클래스만 패치했다. GFS2 설명을 보완할 때는 API 클래스 한 개만 갱신했다. 그 밖의 압축 엔트리, 롤백 백업, WEB-INF 및 UI 정적 파일을 보존했다. Agent는 재기동하거나 변경하지 않았다. 최종 12개 서비스 active, 관리 서버/Agent 활성 JAR 30개 SHA256 일치, 세 `/client/` HTTP 200 및 WEB-INF 존재를 확인했다.

QA VM 12대/볼륨 24개, 전용 오퍼링 5개와 시험 계정·도메인을 정상 API로 정리했다. 오퍼링의 기존 Inactive 이력과 removed VM/스냅샷/Job 이력은 보존한다. 활성 VM·볼륨·VM/디스크 스냅샷·저장소 참조·계정·도메인·오퍼링은 모두 0이다. 22번 6개, 31번 6개, 32번 12개 이미지의 부재와 RBD 임시 이미지/GFS2 관련 파일 부재를 확인했다. 최종 22번 UI에서도 qa1519 prefix는 0건이다.

22번 정리에서 worker가 임시 스냅샷 8을 먼저 제거해 이미 큐에 있던 삭제 Job `f67f17eb-b12e-4968-8c7c-b31786bc38c0`이 431/not found를 반환했다. 새 목록과 DB에서 이미 제거됐음을 확인하고 정상 정리 절차를 재개했다. SQL 상태 변경이나 강제 물리 삭제는 사용하지 않았다. 준비 중 시험 계정 비밀번호 정책/역할 오류 분류를 보정한 중간 실행은 최종 성공 건수에 포함하지 않는다.

## 보호 범위와 남은 작업

22번 rocky98-perftest 및 ROOT/DATA에는 이 작업에서 전원·디스크·QGA·마이그레이션·내용 명령을 보내지 않았다. 시작/종료 대조에서 PID 1516803/startTicks 201077163, Running/host와 두 볼륨의 경로·할당 정보가 유지됐다. rocky98-base도 Stopped, ROOT 경로/정책 및 활성 스냅샷 0건을 유지했다.

#1518 PR #1522는 별도 OPEN/미병합 상태이며 이 PR은 #1519 변경만 포함한다. 실환경은 두 수정을 함께 유지한 상태로 검증했다. #1520의 배치 전체/부분 실패 응답과 선택 #1521은 범위 밖이므로 Epic #1510을 닫지 않는다. 사용자 요청에 따라 구현·배포·검증 후 #1519를 종료하며 PR 병합 여부와 구분한다.

전체 Cloud/Release CI, 50대 실물 배치, 모든 계정 역할/스토리지/장애 조합을 검증했다는 의미는 아니다. 원시 로그·API/DB/RBD·브라우저 증거는 로컬 raw/에 보관하고 인증 정보가 섞일 수 있는 원문은 Git에 올리지 않는다.
