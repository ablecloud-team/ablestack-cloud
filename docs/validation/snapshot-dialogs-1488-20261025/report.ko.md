# #1488 스냅샷 대화상자 설계·구현·검증

2026-10-25 · Epic #1473 · PR #1492 · `dhslove/epic-1473-storage-snapshots` · 기준 `ae174ed6eac5a4b07ade1e305a9ca890be9bf8a2`.

생성·볼륨 복원·템플릿·원본 되돌리기·다운로드·복사·보관·단일/다중/Zone 삭제를 공통 MoldDialog로 정리했다. 관리자 삭제 잔여 복구와 기존 알림의 작업 상세도 같은 규격을 사용한다. Java·업무 API·DB 스키마는 변경하지 않았다.

## 구현 결과

| 영역 | 최종 동작 |
|---|---|
| VM 기준 | 실제 VM 기존 볼륨 연결 대화상자와 공통 MoldDialog를 비교했다. 기존 VM 창은 520px이며 모든 VM 창이 720px라고 해석하지 않는다. 스냅샷에는 공통 md 720px, 다중 lg 960px, 좁은 화면 양쪽 여백과 본문 스크롤을 사용한다. |
| 폼 | 고정 헤더/푸터와 24px 항목·8px 라벨 간격을 사용한다. 생성·볼륨 복원·템플릿은 상세 화면의 메타데이터 표를 반복하지 않고 입력·상속/영향 안내를 중심으로 표시한다. |
| 삭제 요약 | 고정 대상 이름, 원본 볼륨, 연결 VM 이름·한글 상태, 소유자, 스냅샷 상태, 동적 가상 크기, 주기, 생성 시각과 범위를 표시한다. 원본 조회 불가와 연결 VM 없음을 구분한다. 다중 표는 1,500px 가로·300px 세로 내부 스크롤을 사용한다. |
| 삭제 범위 | 전체 삭제와 선택 Zone의 모든 Primary·Secondary 사본 삭제를 구분하고 다른 Zone·원본 볼륨 보존 여부를 명시한다. Zone 삭제를 한 저장소 사본 삭제로 표현하지 않는다. |
| 작업·오류 | 구체적인 작업명과 위험 버튼, 확인 체크, busy·Cancel/Escape 잠금을 유지한다. 조회 오류는 입력/선택을 보존하고 다시 조회한다. 일부 제출 완료 후 실패하면 결과 표와 닫기·잔여 재선택 안내를 제공한다. |
| 태그·키보드 | 태그 Enter는 태그만 추가하고 삭제/취소 후 새 태그 버튼으로 복귀한다. Ctrl+Enter는 폼 제출이다. 조회 중 비활성화/제거된 컨트롤은 스냅샷 창 안으로 초점을 복구한다. |
| 닫기 초점 | 모바일 생성 창에서 숨겨진 메뉴 항목과 헤더 Affix 재생성으로 BODY에 남는 초점을 재현했다. 현재 작업 버튼을 우선 사용하고 모달 닫힘·헤더 재배치 이후 BODY에만 제한해 복구한다. 경로/범위/작업 세대가 바뀌거나 다른 컨트롤에 초점이 있으면 복구하지 않는다. |
| 테마·지원 범위 | 테마 색상 변수로 라벨·입력·설명·체크·표를 표시한다. StorPool 복제/Primary 풀 선택은 노출하지 않으며 실제 복사 인자에도 전달하지 않는다. |

## 실제 31·32 검증

새 `SNAPDIALOG1488-31/32-*` 전용 도메인·3계정·프로젝트·분리 볼륨·정지 VM/연결 데이터 볼륨·ROOT·원본 삭제 사본과 추가 Zone/NFS 저장소를 사용했다. 마지막 연결 볼륨의 UI 생성까지 포함한 UUID는 `evidence/*-fixtures.json`에 보존한다. 기존 운영 데이터에는 패턴을 쓰지 않았다.

| 검증 | 31 GFS2·32 RBD 결과와 증거 |
|---|---|
| 생성 | 태그 추가/삭제·Enter, Ctrl+Enter와 중복 클릭으로 실제 생성했다. 마지막 연결 볼륨 생성도 최종 배포에서 수행했다. `browser/*create*network.json`, `*-tag-*-focus.json` |
| 템플릿 | OS 조회 503 → 입력 유지·재시도, 제출 전 431 → 폼 유지·상태 재조회 → 실제 생성. QCOW2/1GiB와 1MiB 0x5a 패턴을 독립 대조했다. `*template*`, `evidence/*-db-derived.json` |
| 볼륨 | 중복 클릭 후 실제 서버 수락 응답을 먼저 저장하고 브라우저에 503을 반환했다. job 조회도 503으로 거부했다. busy Escape 차단, 결과 미확인·창 재개·Ctrl+Enter의 재제출 차단, 기존 job 완료 확인을 검증했다. DB 생성 1건과 상속 크기/오퍼링·실물 패턴이 일치했다. `*volume-accepted-before-loss.json`, `*volume*`, `*-db-derived.json` |
| 원본 복원 | 전용 원본 첫 1MiB를 0xa5로 변경하고 UI 확인/Enter 제출 후 0x5a를 확인했다. `*restore-submit-network.json`, `evidence/*-pattern-change-owner.json`, `*-pattern-verify-owner.json` |
| 다운로드 | 중복 클릭은 실제 준비 1회다. 완료 작업 상세의 파일명/QCOW2/1GiB/물리 크기와 HTTP 206·실제 magic/헤더를 확인했다. 원본 Zone 삭제 후 이전 URL은 404다. URL은 공개 증거에서 제거했다. `*download*`, `evidence/*-download-http.json`, `*-download-revoked.json` |
| 보관 | 31의 기존 Secondary 검증과 32의 Secondary 재사용 후 Primary 정리를 확인했다. 32 RBD 스냅샷 이름 부재와 원본 볼륨 보존을 독립 확인했다. `*archive*`, `evidence/*-db-archived.json`, `*-ui-delete-oracle.json` |
| 복사 | 대상 조회 503 시 선택 유지/미제출/재시도, 이전 대상 지연 응답 무시, 실제 실패 안내와 UI 재시도를 확인했다. 원본·대상 SHA-256/1GiB/QCOW2/0x5a가 일치한다. 실제 실패와 성공 job을 구분한다. `*copy*`, `evidence/*-db-copy.json` |
| Zone 삭제 | 원본 Zone 전체 삭제 후 대상 Zone Ready 사본 1개, 전역 BackedUp, 원본 볼륨과 복구 데이터가 유지됐다. 원본 백업 파일은 없다. `*zone-delete*`, `evidence/*-db-zone-deleted.json` |
| 단일·다중·복구 | owner-next 단일 삭제, vmdata 완료/other 제출 거부의 부분 결과와 other만 재선택 재시도, 원본 볼륨이 삭제된 deleted 사본의 강제삭제를 실제 실행했다. 네 스냅샷 Destroyed/활성 참조 0, 백업 파일 4개 부재를 확인했다. 32는 보관을 포함해 RBD 스냅샷 4개 부재도 확인했다. `*single-delete*`, `*bulk*`, `*recovery-force*`, `evidence/*-ui-delete-oracle.json` |
| 권한·고정 범위 | 일반 계정의 자기 사본 조회, 다른 소유자 비노출과 복사 대상/보관/삭제 진단 거부를 확인했다. 실제 지연/응답 유실 시험과 집중 검사를 구분한다. `evidence/*-dialog-acl.json`, `*copy-late-scope*` |

브라우저 503·제출 전 431·지연은 해당 XHR에 한정한 통제 주입이다. 볼륨 수락 응답 유실은 실제 backend job을 만든 뒤 응답만 바꾼 시험이다. 실제 서버 전송 실패는 별도로 기록했고 성공으로 세지 않았다. 한글 상태·행동 안내와 서버 원문 진단을 구분한다.

## 화면·포커스 검증

두 클러스터 × 12종 × 라이트/다크 × 390/768/1280px = **144개 최종 화면**을 저장했다. 같은 조합에서 Escape·닫기 초점 복귀를 확인하고 모바일 **48개**는 Shift+Tab/Tab 순환도 검사했다. 실제 제출에서는 Enter/Ctrl+Enter를 사용했다. VM 정보가 있는 ROOT 단일 삭제와 연결 데이터 사본을 포함한 5개 다중 표로 요약·스크롤을 검증했다.

`browser/final-layout-matrix.json`과 `*-repaired-layout-*.json/png`는 최종 배포 증거다. 라벨/컨트롤의 **가로·세로 교차**로 겹침을 판정한다. 소유자 선택의 의도된 가로 배치는 세로 위치만 비교해 오류로 세지 않는다. 헤더/본문/푸터 위치·페이지 가로 넘침·이미지 크기·다크 텍스트 색상을 교차 검사했다. 초기 모바일 BODY 초점 결함 기록과 수정 후 증거를 함께 보존한다.

- [모바일 생성](browser/32-repaired-light-390-create.jpg)
- [다크 다중 삭제](browser/32-repaired-layout-dark-1280-bulk.jpg)
- [실제 실패 안내](browser/31-copy-real-server-failure.jpg)
- [강제삭제 완료](browser/32-recovery-force-complete.jpg)

## 검사·배포·정리

- 최종 집중 검사 **14묶음/201개**, 전체 lint 및 마지막 변경 파일 lint, production UI build 통과. 로그는 `logs/`에 보존한다.
- 최종 정적 자산 922개의 SHA-256을 두 서버에서 확인했다. WEB-INF/META-INF/config/관리 JAR·32 대용량 파일을 보존하고 Mold active, `/client/` HTTP 200을 확인했다. 활성 webapp 루트 전체 교체나 `rsync --delete`는 사용하지 않았다.
- 전용 개체는 정상 API로 정리하고 API/독립 DB·백업/템플릿·GFS2/RBD/trash/libvirt의 활성 잔여를 확인했다. 스냅샷/결과/사본 행을 성공 상태로 보정하지 않았다. 감사 이력/tombstone은 유지한다.
- 기존 운영 자원 8종의 핵심 속성과 Windows/WSL dirty FT 작업의 HEAD·status·diff를 보존했다. 비밀번호·sessionkey·키·다운로드 bearer URL을 공개 증거에서 제거했다.

## 시험 인프라와 검증 경계

추가 Zone·Pod·네트워크·NFS 저장소는 제품 API로 만들었다. 기존 SSVM 안에 별도 TLS AgentShell 프로세스와 nosharecache NFS 하위 경로를 연결했고 새 image_store.parent의 실제 마운트 매핑만 준비 단계 SQL로 등록했다. 실제 사본/완료 결과는 제품 전송으로 생성했다.

같은 SSVM의 자기 HTTP 주소가 기본 `/copy` 접근 제한에 걸려 첫 복사 job이 실제 530/실패로 끝났다. UI 결과 확인 후 **전용 원본 파일 하나·자기 SSVM 주소만** 허용하는 임시 .htaccess로 재시도했다. 성공 후 즉시 제거했고 원래 .htaccess 해시 유지·HTTP 403 복귀를 확인했다. 원래 에이전트 PID/신원/인증서를 보존했다. 전체 agent.properties 바이트 동일 여부와 신원 보존은 별도 판정한다.

이 구성은 별도 프로세스와 같은 NAS의 독립 하위 경로를 사용한 기능 검증이다. 신규 SSVM VM 전체 생성, 독립 NAS/네트워크 간 전송 성능, StorPool 실행은 포함하지 않는다. ROOT 템플릿 새 VM 부팅·예약/보존·Running/역할 전체 조합·DB_ONLY 정보 누락 복구 전 범위를 이번 UI 정리에서 재검증했다고 보고하지 않는다.

전체 Cloud 빌드·Maven·GitHub Actions dispatch·qemu/FTCTL 빌드는 실행하지 않았다. 집중/실환경 결과는 전체 CI 통과를 뜻하지 않는다. Epic #1473과 PR #1492는 열림/병합 대기로 유지한다.

다음은 **#1489 통합 회귀와 Epic 완료 판정**이다. SS-01~SS-15를 같은 배포 구성으로 대조하고 ROOT 부팅·Running/Stopped·프로젝트/제한 역할·예약/보존·실제 다중 사본·부분 실패/동시성·잔여 정리를 통합 검증한다. 기존에 추적한 절대 install_path 삭제 잔여·계정 삭제 usage 중복·32 Running hot-attach를 이 UI 이슈의 성공으로 닫지 않는다.

## 최종 증거 집계

| 클러스터 | 실제 UI 변경 요청 | DB job | 성공 job | 실제 전송 실패 job | 정리 후 DB 활성 잔여 | 백업/템플릿 파일 | Primary 볼륨 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 31 | 18 | 15 | 14 | 1 | 0 | 12 | 7 |
| 32 | 18 | 15 | 14 | 1 | 0 | 12 | 7 |

저장 증거 대조: **971개 PASS**. dryrun 진단 job은 실제 변경 제출 수에서 제외하며, 제출 전 431은 DB job이 없음을 확인한다. [검증기](verify_evidence.py) · [검증 결과](evidence-verification.json) · [소스 해시](source-manifest.json) · [자산 해시](asset-manifest.json).

마지막 브라우저 확인: 두 환경의 전용 접두어 목록 행 0, 최종 app 번들 일치, 처리되지 않은 JavaScript 예외 0. `browser/*-cleaned-list.*`, `*-final-console.json` 참조.
