# #1479 작업 대상·최신 상태·비동기 재제출 보호 검증

Epic #1473 / SS-06 / P1. 기준 `fa60459bceccbab36abba8e098f3da4222f1f12a`, 브랜치 `dhslove/epic-1473-storage-snapshots`, 기존 PR #1492에 구현을 누적한다. #1477·#1478의 보관·일반/강제 삭제 계약을 유지한다. 이 보고서의 최종 판정과 파일 해시는 `evidence/verification.json` 및 각 증거 파일을 기준으로 한다.

## 구현 결과

- 확인창을 열 때 snapshot/volume/VM/owner/project/Zone과 API를 복사하여 고정한다. 목록의 선택·정렬·페이지 갱신이 실행 대상을 교체하지 않는다. 목록/상세 경로 변경 전에 양식을 닫고, 프로젝트/로그인 전환 및 종료된 양식의 늦은 응답은 후속 조회와 변경 요청을 중단한다.
- `listSnapshots`/`listVolumes`의 선택적 `includesnapshotcontext=true`가 현재 원본·연결 VM·생성/되돌리기 제한 사유를 반환한다. 원본 소유 권한이 없으면 정보를 숨긴다. 실제 생성/되돌리기도 원본 소유 권한과 복원 충돌을 검증한다. 권한 검사는 상태나 백업 정보를 노출하기 전에 수행한다.
- 실행 직전 API 권한·현재 자원·사본/Zone·작업별 제약을 다시 확인한다. 미완성 조회·대상/범위 변경·권한 회수는 이유를 표시하고 중단한다. 보관/삭제의 기존 dryrun/scopehash도 사용한다.
- 원본이 삭제되었거나 조회할 수 없는 snapshot도 유효한 사본에서 새 볼륨·템플릿을 복구할 수 있다. 원본 자체의 되돌리기는 막는다. 생성 폼의 원본 allocation 조회가 없는 원본 복구를 막던 동작을 수정했다. snapshot 복구에서 불필요한 custom 크기/IOPS 입력을 숨겼으며, 전체 크기·오퍼링 계약 #1476은 계속 별도 범위다.
- 컴포넌트와 독립된 작업 registry가 사전 검사부터 volume/snapshot/VM 단위 예약을 잡고 `checking/submitting/accepted/complete/failed/unknown`을 구분한다. 현재 scope의 작업 패널에 요청 UUID/job ID·이벤트·결과 자원 링크·기존 결과 조회를 제공한다. 새로고침은 세션 저장소의 미확인 요청을 보존하고 프로젝트별 기록은 분리한다.
- 실제 Chromium은 socket reset 뒤 동일 POST를 재전송했다. 기존 `customjobid`를 정규 UUID로 발급·보존하고, 서버가 UUID 잠금 안에서 사용자/계정/명령/프로젝트/대상/입력 해시를 확인하여 **새 할당 전 기존 job을 반환**한다. 다른 요청이 같은 UUID를 쓰면 거부한다. UI는 자동 POST 재제출 없이 UUID로 결과만 조회한다.
- 일반 복원/삭제·생성·새 볼륨·템플릿·Zone 복사/삭제·삭제 잔여 복구에 공통 보호를 연결했다. 일괄 작업은 전체 고정 대상을 먼저 검사하고 각 항목 실행 직전 다시 검사한다. 첫 항목 실패/미확인 시 뒤 항목을 실행하지 않는다. 강제 삭제는 DB 종결과 실물 종결을 계속 구분한다.

## 31 GFS2 / 32 RBD 실환경 결과

두 클러스터 각각 전용 `SNAP1479-31/32-*` 자원을 사용했다. 운영 자원에서 테스트 쓰기/복원을 수행하지 않았다. `*-fixtures.json`, `*-api-fixtures.jsonl`, UI AX/screenshot, 단계별 DB/실물 JSON을 연결한다.

| 검증 | 실제 동작과 판정 | 주요 증거 |
|---|---|---|
| Running/Stopped·연결/분리 | 전용 VM에 연결된 Running 볼륨의 스냅샷 생성 성공. Running 되돌리기 UI 차단 및 API 실패. 분리 볼륨 UI 되돌리기 성공 | `*-checks-running.json`, `*-context-running.json`, `*-pattern-verify-other.json`, UI running/revert 기록 |
| 확인 후 상태 변경 | Stopped 되돌리기 확인창을 연 뒤 VM을 시작하면 fresh preflight에서 차단, 변경 POST 없음 | UI drift 기록, fixture API job/DB |
| VM snapshot/백업 복원 충돌 | 정지된 전용 VM에 VM snapshot 및 NetBackup Restoring 메타데이터를 각각 주입. UI/API 생성·되돌리기 거부, allocation 전 차단. 주입행 전부 복구 | `*-checks-conflict-*.json`, `*-conflict-clear.json` |
| 소유·권한 | 일반 사용자 자신의 snapshot 허용/다른 소유자 거부. 자신의 양식을 연 뒤 복제 역할 createVolume 권한 회수 → 명확한 안내·POST 0. 원본만 다른 소유자로 바꾼 경우 원본 생성/되돌리기 거부·snapshot의 새 볼륨 복구 허용 후 소유자 정확히 원복 | `*-owner-acl.json`, `*-owner-fault-api.json`, `*-owner-clear.json`, `*-permission-final.txt` |
| 원본 삭제 복구 | 원본을 API 삭제한 뒤 실제 UI와 API로 새 볼륨·템플릿 생성. 복구 볼륨과 NFS 템플릿에 `qemu-io` 1MiB `0x5a` 검증 | `*-orphan-context.json`, `*-pattern-verify-orphan-*.json`, `*-template-restored-physical.json` |
| 실물 되돌리기 | 원본 `0x5a` snapshot 후 분리 볼륨을 `0xa5`로 변경. UI 되돌리기 후 `0x5a` 복원 | `*-pattern-change-other.json`, `*-pattern-verify-other.json` |
| API 동시/재호출 | createSnapshot/createVolume/createTemplate 각각 동시 2회와 완료 후 재호출 → 같은 job 1개·자원 1개. 입력 변경+같은 UUID는 거부 | `*-dedupe-api.json`, `*-dedupe-db.json` |
| UI 응답 소실·F5·중복 클릭 | fixture 프록시가 변경 응답을 서버 접수 후 끊고 query를 503으로 차단. 브라우저 전송 재시도도 같은 UUID/job으로 합침. F5 후 UUID와 재제출 차단 유지, 조회만 재시도하여 완료 | `*-reload-protection-proof.json`, `*-reload-*.txt/png` |
| 접수/완료·프로젝트 전환 | 실제 접수 job ID를 유지하며 query 장애에서 unknown 표시. 프로젝트로 전환하면 이전 scope 분리, 기본 보기 복귀 후 같은 job 조회로 완료 | `*-query-*.txt`, `*-project-*.txt` |
| 일괄 부분 중단 | 두 snapshot의 고정 UUID를 확인. 첫 삭제의 응답/결과를 차단하면 transport POST 3회가 같은 job 1개로 합쳐지고 두 번째 변경 POST는 0. 두 번째 DB BackedUp/실물 보존. 첫 job 조회 회복 후에도 뒤 대상 자동 실행 없음 | `*-bulk-transport.json`, `*-bulk-db.json`, `*-bulk-physical.json`, `*-bulk-unknown.txt` |
| 지연 응답·경로 전환 | 생성 사전 조회를 지연시킨 뒤 다른 자원 경로로 이동. 이전 양식 종료, 원래/새 대상 변경 POST 0, 금지 이름 DB 행 0 | `*-late-route-final.txt`, `*-late-route-proof.json`, `*-final-counters.json` |

## 발견·수정한 실패와 환경 차이

1. UI 전송 1회 호출에도 Chromium의 socket 재전송으로 snapshot 할당이 중복됐다. 서버의 UUID receipt 재사용을 추가하고 실제 동시 API 및 소실 응답/F5로 재검증했다. 수정 전 Allocated/Error 전용 자원도 정리 대상에 포함한다.
2. 저장된 job이 cold bootstrap의 token/user 초기화로 지워졌다. 초기 수화와 실제 보안 문맥 교체를 구분하여 최종 F5 양 클러스터 통과를 확인했다.
3. 원본 삭제 복구 양식이 원본 allocation 조회를 필수로 간주하고, TakeSnapshot 버튼은 차단 사유가 있어도 활성화됐다. 원본 부재 계약과 submit disabled 상태를 보완했다.
4. 권한 오류가 `listapisresponse` envelope로 반환되어 HTTP 530 일반문구만 표시됐다. command별 errortext 추출 후 양쪽 UI에서 권한 안내와 변경 POST 0을 확인했다.
5. 지연 사전 조회 중 다른 메뉴로 이동하면 요청은 차단되지만 과거 양식이 새 자원의 양식으로 남았다. route guard에서 resource 교체 전에 닫도록 수정하고 관련 회귀 3개 및 실제 UI를 재검증했다.
6. request manager를 잘못 Spring component로 주입한 최초 패치는 module context 빈 미등록으로 기동에 실패했다. 기존 AsyncJobDao를 이용하는 무상태 팩토리 방식으로 수정했고 이후 정상 기동/5개 agent Up/8개 정상 조회를 확인했다.
7. 32 기존 fat JAR와 Epic QueryManagerImpl의 버전 차이로 template/ISO 및 SSH key VM 필터 참조가 빠졌다. 동일 Epic 소스의 `ListTemplatesCmd/ListIsosCmd/VMInstanceDetailsDao/Impl` 4개 의존 클래스를 추가했다. 20개 배포 클래스의 내부 참조 검사에서 missing=0이며 정상 조회를 재검증했다. 이 네 파일은 이번 소스 변경이 아니다.
8. 최종 실물 감사에서 31 Running snapshot의 절대 install_path가 기록된 참조는 정상 delete job/DB Destroyed 이후에도 NFS 백업 파일 1개가 남았다. 이미 Destroyed인 참조는 강제 복구 scope에서도 제외되는 기존 삭제 경로의 별도 결함이다. 이 실패는 `31-existing-delete-residual.json`에 보존한다. snapshot/참조 Destroyed·원본 Expunged·정확한 경로/파일 SHA256을 다시 확인한 뒤 해당 전용 파일 하나만 수동 unlink했다. SQL 쓰기로 정리하지 않았으며 제품 정리 PASS로 표시하지 않는다. #1489에 후속 결함으로 기록한다.
9. 32에서 재기동 직후 SSVM 미연결 시점의 첫 요청은 `No remote endpoint`로 실패했다. 모든 agent Up 뒤 새 UUID로 재시험했다. 또한 기존 hot-attach의 AgentAnswer→AttachAnswer 형 변환 오류를 관찰했다. 전용 VM을 정지한 연결 경로로 Running/Stopped 검증을 완료했으며 hotplug 결함은 이번 수정 성공으로 처리하지 않는다.

## 검사·배포·정리

최종 범위 판정은 **SS-06 PASS**다. 두 환경 합계 snapshot 24개·볼륨 19개(ROOT 2개 포함)·템플릿 7개·VM/프로젝트/계정/역할 각 2개를 사용했다. 활성 API/DB와 알려진 실물은 최종 0이며, 31 파일 1개의 수동 정리는 제품 삭제 성공에서 제외한다. 계정 삭제 이후 기존 usage 증가 결함은 이번에도 31에서 7건·32에서 9건 관찰됐고 #1489에 유지한다.

- WSL ext4에서 변경 Maven api/server 및 배포 의존성 engine/schema 모듈만 검사했다. server 집중 검사 **93 tests / failures=0 / errors=0 / skipped=0**, api install 및 engine/schema install 성공.
- 최종 UI **129 tests / 10 suites**, 전체 no-fix lint 오류 0, production build 성공. Browserslist의 기존 caniuse-lite 갱신 안내 및 번들 경고를 전체 CI 통과로 해석하지 않는다.
- 관리 서버 JAR 20개 클래스와 static 920개 파일을 해시 대조한다. 변경하지 않은 기존 JAR entry, WEB-INF/config를 보존한다. `/client/` 200, Mold active, 각 클러스터 Routing 3+SSVM+Console Up 및 정상 조회 8종을 확인한다. 롤백 백업은 관리 서버 `/root/snapshot-actions-1479-*`에 보존한다.
- 원본 소유자/충돌 주입은 원복했고 모든 테스트 VM/볼륨/템플릿/snapshot/프로젝트/계정/복제 역할은 API로 정리한다. 실패 자원의 복구 삭제도 허용된 관리자 API를 사용한다. 단, 아래 기존 절대 경로 잔여 파일 1개는 DB/해시 확인 후 수동 정리했으며 제품 자동 정리 성공으로 계산하지 않는다. 제거된 snapshot의 Destroyed 이력은 보존하며 활성 자원/미종결 참조/알려진 실물은 0으로 확인한다. `final-counters`, `physical-cleanup-verification`, `preservation`이 최종 판정이다.
- 보호된 Windows/WSL FTCTL dirty 작업 트리의 branch/HEAD/status/diff/파일 해시를 보존한다. qemu/FTCTL 프로그램 변경·빌드, 전체 Cloud Actions dispatch 및 PR 병합은 수행하지 않는다. PR은 검토/병합 대기다.

## 범위와 한계

실제 NetBackup 데이터 복원 엔진을 실행한 결과가 아니라 전용 정지 VM의 복원 메타데이터 충돌 판정이다. 데이터 디스크 템플릿의 패턴 검증은 OS 부팅 검증과 다르다. 32 템플릿의 기존 RAW 표기/실제 QCOW2 문제는 #1474, 복원 크기 전체 계약은 #1476에 남긴다.

현재 두 환경은 단일 Zone이다. source Zone 고정·목적지 부재/불완전 조회/잘못된 Zone 차단은 검사했지만 다른 Zone으로 실제 복사를 성공시켰다는 주장은 하지 않는다. 보관/복사 전체 진입 UX는 #1481, 사본 집계/행은 #1480, VM 기준 목록·상세·탭·대화상자 시각 표준은 #1485~#1488 범위다.

UUID 중복 방지는 async_job 이력이 남아 있는 기간의 재시도를 보호한다. job purge 후 영구 중복 방지, job 기록 전 프로세스 crash의 완전한 exactly-once, 서로 다른 탭에서 별도 UUID로 보낸 독립 요청까지 같은 의도로 추정하는 기능은 포함하지 않는다. 전체 Cloud 빌드는 사용자의 명시 요청이 없어 실행하지 않았으며 집중 검사와 실환경 결과를 전체 CI로 표시하지 않는다.

## 다음 작업

새로 재현한 **31 절대 경로 참조의 삭제 후 실물 잔여 결함(#1489 추적)을 우선 보완**한다. 앞서 정한 스냅샷 작업 흐름의 다음 순서는 **#1480 → #1481**이다. 사본의 Zone/스토리지 식별·건수·페이징 계약을 먼저 확정하고, 이번 대상/상태/비동기 보호를 보관·복사 UX에 연결한다. **#1474는 독립 P1**으로 먼저 또는 병행 착수할 수 있다. #1489의 계정 삭제 usage 중복과 hotplug 진단은 미해결로 추적하고, Epic #1473은 계속 열어 둔다.
