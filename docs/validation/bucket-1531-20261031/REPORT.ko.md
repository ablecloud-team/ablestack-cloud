# #1531 할당량 검증·예약과 Glue 변경 실패 복구 결과

Epic [#1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527), 기존 PR [#1541](https://github.com/ablecloud-team/ablestack-cloud/pull/1541)의 같은 `dhslove/epic-1527-buckets` 브랜치에서 구현했다. 2026-10-31 KST 기준 31(GFS2), 32(RBD)에서 실제 UI와 API·MySQL·32번 Glue RGW를 대조했다. 두 Mold의 버킷 데이터 경로는 동일한 32번 Glue다.

## 개선 결과

생성·수정의 명시적 quota를 1~2147483647 정수 GiB로 검증하고 정확한 long byte 변환을 사용한다. 외부 변경 전에 계정·프로젝트·부모 도메인 제한, 저장소 확정 할당과 진행 중 예약을 검사한다. 축소는 캐시된 Cloud size 대신 Glue의 실제 사용량을 확인한다.

저장소 분산 잠금, 버킷 활성 작업 유일성, 저장소 할당량 CAS와 지속 작업 원장을 추가했다. 증가분은 계정 원장과 durable hold로 보유하고, 외부 통신을 SQL 트랜잭션 밖에서 실행한다. Glue 적용·재조회 뒤 quota/계정/저장소/작업 완료를 함께 확정한다. 외부 성공 후 DB 실패는 정확한 이전 enabled/max_size/max_objects/check_on_raw로 보상한다. 보상 실패는 NeedsReconcile과 예약을 유지하며 중복 변경을 막는다. 작업 UUID를 지정한 ACL 적용 재시도와 30초 주기 복구로 응답 유실·관리 서버 재기동을 처리한다.

확인하지 못한 생성 결과는 자동 삭제하지 않는다. 확인된 신규 생성의 보상만 S3 empty-only 삭제를 사용한다. 외부 삭제 성공 후 DB 실패도 지속 DELETE 작업으로 복구한다. 일반 비어 있지 않은 버킷/버전 삭제 정책 전체는 #1532 범위다.

UI는 공통 MoldDialog·Status·날짜·버튼과 테마 토큰을 사용한다. 입력 오류는 할당량 필드에 연결하고 서버 내부 SQL은 표시하지 않는다. 용량/예약은 확인 시각이 있는 스냅샷이며 서버가 최종 판정한다. 실제로 바뀐 필드만 보내고 quota와 보호 옵션의 동시 변경은 분리 적용을 안내한다. 기존 0 quota는 다른 옵션 단독 수정을 허용하고 양수 입력으로 명시적으로 정상화한다.

[최종 상세 설계](../../design/bucket-1527/1531-quota-consistency.ko.md).

## 실제 기능 검증

| 항목 | 확인 결과 | 증거 |
|---|---|---|
| 입력·단위 | 양쪽 생성/수정의 음수·0·빈 값·소수·지수·상한 초과·오버플로와 용량 초과를 외부 부작용 없이 거부. 상한 정수의 정확한 byte 변환은 집중 검사로 확인 | `data/invalid-create-*.json`, `data/canary-api-*.json` |
| 제한 | 계정·프로젝트·격리 자식 도메인·부모 도메인 제한을 실제 Glue 쓰기 전에 거부 | `data/limits-*.json` |
| 동시 생성 | 두 클러스터에서 UI 두 창의 3+3 GiB/4 GiB 저장소 요청 중 하나만 성공. 실패 창의 필드 오류와 1 GiB 재시도로 3+1 확정 | `data/ui-concurrent-create-final-*.json`, `ui/ui-concurrent-create-requests-*.json` |
| 동시 수정 | UI 두 창의 서로 다른 버킷 1→3 GiB 경쟁에서 총량 초과 요청 거부. 공간 해제 후 재시도하여 2+2 확정. 같은 버킷 API 경쟁도 직렬화되고 원장이 일치 | `data/ui-concurrent-update-final-*.json`, `data/same-bucket-concurrent-*.json` |
| 실제 사용량 | Cloud 캐시 0인 버킷에 실제 1 GiB+1 byte 객체를 넣고 UI 2→1 축소 거부. 원래 quota와 객체 HEAD 크기/ETag 유지 | `data/live-usage-shrink-rejected-31.json` |
| legacy·혼합 수정 | 전용 quota=0 버킷의 UI 버전 관리 변경에서 quota=0을 보내지 않음. 양수 2 GiB 정상화 성공. quota+다른 옵션의 실제 혼합 변경은 제출 전에 거부 | `data/legacy-*.json` |
| 외부 quota 정밀 복원 | 실제 Glue 1073741825 byte, max_objects=17, check_on_raw=true와 이전 disabled quota까지 정확히 재조회·복원 | `data/provider-exact-client.json` |
| DB·카운터 실패 | 실제 Glue 변경 후 DB 실패를 보상. checked decrement 실패 시 DB/원장 원자성 확인. 31번 실제 UI 오류 후 재시도 성공 | `data/fault-matrix-32.json`, `data/ui-db-retry-31.json` |
| 보상 실패·수동 복구 | 양쪽 UI에 NeedsReconcile·작업 UUID·시각·예약 표시. 31번 저장소 축소/endpoint 변경/삭제 차단, 같은 작업 재시도 후 한 번만 확정. 32번 UI 재시도도 성공 | `data/ui-recovery-31.json`, `data/pending-store-guards-31.json`, `data/fault-ui-compensation-failed-32.json`, `ui/ui-32-pending-dark-*.jpg` |
| 응답 유실·재기동 | Glue 적용 후 응답 유실은 보상 후 재시도, Glue 실제 쓰기 후 관리 프로세스 종료는 Applying을 보존하고 재기동 후 복구 worker가 attempt=2로 완료. 실제 객체 SHA256 유지 | `data/fault-matrix-32.json`, `data/fault-after-restart-32.json` |
| 생성·삭제 DB 실패 | 확인된 신규 빈 버킷 생성의 DB 실패 보상과, 외부 삭제 후 DB 실패의 지속 작업 재시도 확인. 재생성이나 중복 카운터 차감 없음 | `data/create-delete-failures-31.json` |
| 최종 반영본 | 양쪽 최종 JAR에서 2→3→2 GiB와 실제 Glue·저장소·계정 원장 일치 | `data/final-canary-*.json` |

## VM 메뉴 스타일·다크모드·반응형

실제 가상머신 메뉴와 버킷 툴바를 비교했다. 주 버튼 32px/14px 글꼴/32px 모서리, 새로고침 아이콘 24px/14px/24px 모서리가 같다. 모달 확인·취소·복구는 공통 32px 높이/4px 모서리 규격을 사용한다. 주 버튼과 아이콘 버튼의 위치도 기존 표준을 유지한다.

최종 UI에서 31·32 × 생성/수정 오류 × 밝은/어두운 모드 × 360/768/1280px의 24화면과 복구 대기 다크모드 3화면을 확인했다. 가로 넘침 0건, 측정한 활성 제목·라벨·안내·오류·입력 텍스트의 4.5:1 미달 0건이다. 최소 대비는 밝은 모드 **7.557:1**, 다크모드 **6.792:1**이다. 기존 오류 입력란의 4.252:1 대비를 테마 토큰 기반 입력 배경·문자로 보완했다. disabled 컨트롤은 활성 텍스트 대비 집계에서 제외했다.

한글/영문 안내·입력 검증 표본도 확인하고 최종 한글·다크모드를 복원했다. 이것은 전체 앱의 모든 문구/색상에 대한 접근성 인증이 아니다. 용량 시각은 조회 스냅샷이며 사용량 최신성 기능 #1537의 완료를 뜻하지 않는다.

UI JSON은 실제 DOM viewport와 디코딩된 JPEG 크기를 각각 기록한다. 모바일 DOM 360×844, 768×844에서 브라우저 도구의 JPEG는 각각 360×203, 768×432로 반환됐으므로 이를 844px 높이의 원본 캡처로 계산하지 않았다. 1280px 캡처는 1280×720다.

- [실측 전체 결과](ui/ui-style-analysis.json), [VM 메뉴 기준](ui/vm-menu-style-reference.json), [버킷 툴바](ui/bucket-toolbar-style-reference.json)
- [32번 다크모드 수정 오류](ui/ui-32-update-error-dark-1280.jpg), [31번 밝은 모드 생성 오류](ui/ui-31-create-error-light-1280.jpg), [복구 대기](ui/ui-32-pending-dark-1280.jpg)

## 빌드와 배포

- 소스 backend: `969739b4e9cc9ed0264e757ed417408c9f16b5e4`. UI 소스: `182438e1fc784bb811efc78fcd059093838d897e` (이후 Java import·문서만 변경).
- WSL ext4 작업본에서 변경 Maven 모듈 `api`, `engine/api`, `engine/schema`, `server`, `plugins/storage/object/ceph`만 빌드했다. backend 집중 검사 **48건**, UI 4 suites/**89건**, 변경 UI lint·production build PASS. 최종 import 정리 후 schema 재빌드, server/ceph 집중 검사 44건 재통과.
- 변경 Java 파일을 명시적으로 지정한 Checkstyle PASS. 모듈 전체 Checkstyle은 기존 VM clone/스냅샷 정책 파일의 wildcard import 6건 때문에 실패하므로 전체 통과로 보고하지 않는다.
- 이미 같은 4.23 버전인 두 서버에는 `setup/db/migrate-bucket-quota-operations.sql`을 JAR보다 먼저 적용했다. 정규 업그레이드 SQL/Europa 보완 경로에도 포함했다. 실제 추가 DDL 반복 적용과 로그인·DAO 작동을 확인했다.
- 기존 검증된 관리 uber JAR에 변경 entry **45개**만 overlay하고 다른 **204558개** 압축 payload를 그대로 검증했다. 서명 entry·중복 이름 없음, 반영 class 42개 의존 클래스 누락 없음. 전체 Cloud 빌드 결과물로 주장하지 않는다.
- 양쪽 최종 주 JAR SHA256: `4b8968f405d59d27d689b3a3e15071fe11fc202311cecf1c72a55c56d8b75ada`.
- 양쪽 최종 index.html SHA256: `fd6431425c8e03771952edc8752f6b564c5f8f63333585625b4609f0ec8c21a2`, 진입 번들 `js/app.6283616f.js`. 실행 라이브러리 9개와 정적 파일 626개 SHA256 일치.
- `/usr/share/cloudstack-management/webapp`의 정적 파일만 갱신했다. WEB-INF·config·다른 서버 라이브러리 보존, META-INF는 두 서버 모두 기존부터 없음. Mold active, HTTP 200, API 로그인, 양쪽 Routing host 3대씩 Up, FTCTL 기존 bundle marker 유지.
- 원본 JAR/설정 백업: 31 `/var/tmp/bucket-1531-20261031/backup`, 32 `/nfs/bucket-1531-20261031/backup`. 최종 반영에 rollback 필요 없었다.
- API 표시 버전 `4.23.0.0-Mold.Europa-202609222315`, UI `V4.0-4.0.15` 및 RPM 메타데이터 유지. 호스트 Agent·Glue·qemu 패키지는 변경하지 않았다. **전체 Cloud 빌드/Full CI/GitHub Actions/qemu 빌드는 실행하지 않았다.**

[최종 상태](data/final-health.json), [JAR manifest](data/jar-manifest.json), [배포 기록](data/backend-deployment-32.json).

## 정리와 원본 보존

동시성·장애 복구 시나리오는 버킷/계정/저장소 원장이 별도 보정 없이 일치했다. 전용 버킷·Object Store·계정·프로젝트·도메인은 일반 API로 삭제했고, 전용 외부 사용자는 소유 버킷이 비어 있음을 확인한 뒤 제거했다. 주입 DB trigger·proxy·활성 quota 작업·임시 예약·시험 credential 행이 남지 않았다. 감사/soft-delete 작업 이력은 보존한다.

legacy 재현 준비에서 전용 Cloud 행을 1→0 GiB로 직접 바꾼 뒤 계정만 재집계하여 ROOT 도메인에 시험 준비분 1 GiB가 남았다. 최종 대조에서 이를 구분해 표준 `updateResourceCount(domainid=ROOT,resourcetype=15)`로 재집계했다. 동시성/실패 복구 결함을 수동 SQL로 보정한 결과가 아니다. [준비 잔여와 표준 재집계 기록](data/legacy-fixture-domain-recount.json).

최종 31번 활성 버킷/저장소 0/0, ROOT bucket/object_storage 0/0이다. 32번은 확인용 버킷/저장소 1/1, ROOT bucket/object_storage 1/1 GiB다. 양쪽 pending quota 작업·예약 byte·시험 credential·임시 예약 0. Glue HEALTH_OK, RGW 3/3이며 원래 dashboard·시험 관리자·확인용 소유자 세 사용자만 유지했다.

**확인용 `qa-bkt-1540-32-files` 하나를 유지했다.** 원래 11개 객체 이름·크기·내용 SHA256과 소유 계정 상세가 모두 동일하다. [Mold 브라우저 탭](http://10.10.32.10:8080/client/#/buckets/3e952d59-a311-4a2b-86fe-d66ed38f050c?tab=browser), [보존 대조](data/retained.json), [최종 UI](ui/retained-bucket-final.jpg). 파일 목록의 loading 종료와 root 파일 5개·폴더 3개를 확인했다. 총 11개에는 폴더 marker/하위 객체가 포함된다.

원래 Windows/WSL FTCTL 작업본의 HEAD·브랜치·기존 추적 diff를 보존했다. 별도 작업에서 새로 나타난 WSL 미추적 DR 테스트 파일도 그대로 뒀다. 이번 구현은 격리된 dhslove ext4 작업본에만 적용했다. [보존 확인](data/protected-worktrees.json).

## 잔여 범위와 다음 작업

PR #1541은 미병합, Epic #1527은 **4/13 완료·OPEN**으로 유지한다. 완료는 #1528·#1540·#1529·#1531이고, 남은 P1은 #1530·#1532·#1533, P2는 #1534~#1539다.

다음 권장 작업은 **#1533 버킷·Object Store 자격 증명 노출 제거와 명시적 키 관리**다. 일반 목록/상세/async/동기화 응답과 URL·로그의 key 의존을 제거하고 권한 검사된 명시적 조회, 메모리 보관, 관리자 키 교체 실패 보존과 tenant 회전 경로를 검증해야 한다. 현재 응답/상세의 정상 키 노출을 이번 quota 개선의 완료에 포함하지 않는다.
