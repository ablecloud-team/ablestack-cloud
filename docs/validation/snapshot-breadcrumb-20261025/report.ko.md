# 스냅샷 목록 breadcrumb·검색 필터 VM 표준 보완

2026-10-25 · Epic #1473 · PR #1492 · `dhslove/epic-1473-storage-snapshots` · 기준 `63c81b221c9073e4bfcd426d2c7bb4bc433bafa4`.

## 최종 변경

스냅샷만 별도 분기로 breadcrumb 아래에 생성·프로젝트·업데이트를 배치하던 구조를 제거했다. VM과 같은 공통 행·열, breadcrumb end 슬롯을 사용한다. 왼쪽은 경로·작은 업데이트·프로젝트 전환, 오른쪽은 생성·권한별 작업·검색이다. 폭이 부족하면 열 안에서 작업·검색을 줄바꿈한다. 상세 화면의 공통 헤더 구조는 유지한다.

검색은 VM과 같은 필터 팝오버·세로 폼·작은 아이콘 재설정/검색 버튼으로 정리했다. SearchView와 SnapshotSearch의 검색창 여백·addon·필터 버튼·폼 버튼 스타일을 공통 `search-view.less`로 추출했다. 스냅샷의 많은 조건은 기존 storage 검색 폼의 제한 폭·높이와 내부 스크롤을 사용한다. 후보 조회·추가 페이지·오류 재시도, 삭제 원본 UUID 유지, 권한/소유 범위와 URL 계약은 보존한다.

폼 모델과 네이티브 submit을 연결했다. 일반 검색 버튼, Enter, Ctrl+Enter가 현재 조건을 한 번 전달한다. Escape는 필터를 닫고 필터 버튼으로 초점을 돌린다. 적용된 필터는 VM과 같은 활성 필터 아이콘으로 구분한다. 생성 버튼은 기존 볼륨 선택 경로를 사용한다. 조회 중/실패 시 선택 데이터 보존과 작업 보호를 유지하며 StorPool 전용 복제 옵션은 노출하지 않는다.

## 31·32 실제 검증

| 검증 | 31 GFS2 | 32 RBD |
|---|---|---|
| 기본 목록 API/UI | 23건, 첫 페이지 20행과 순서 동일 | 7건, 7행과 순서 동일 |
| 프로젝트 포함 전환 | `projectid=-1`, 24건 → 기본 23건 | `projectid=-1`, 8건 → 기본 7건 |
| 정확한 이름 검색 | 대상 1건의 UUID·이름·볼륨을 API/UI/독립 DB 대조 | 동일 |
| 일반 검색 버튼·Enter·Ctrl+Enter | 모두 실제 GET 완료, 각각 요청 1회 | 동일 |
| 키워드와 유형 | `keyword=E1345` 입력 후 MANUAL 조건 보존, 대상 1건 | 동일 |
| 후보 조회 503 | 검색 차단·입력 유지·인라인 재시도 후 검색 성공 | 동일 |
| 상세 진입·뒤로·재설정 | URL 조건 복귀 후 초기화 | 동일 |
| 갱신 503 | 행·선택 유지, 갱신 중 버튼/작업 잠금, 수동 재시도 복구 | 동일 |
| 선택 작업 | 이동된 삭제 버튼으로 표준 창 열기·취소, 선택 해제 | 동일 |
| 페이징 | 2페이지 3행/전체 23건, 이전 페이지 복귀 | 7건으로 다음 페이지 없음 |
| 생성 | 스냅샷 생성 → 볼륨 선택 → 뒤로 복귀 | 동일 |

503은 지정한 XHR 하나에 한정한 통제 주입이다. 실제 스냅샷 삭제·복원·생성은 이 레이아웃 보완에서 실행하지 않았다. 최종에는 모든 요청 주입과 기기 크기 강제를 해제하고 원래 다크 테마·선택 0으로 복구했다.

두 클러스터 × 라이트/다크 × 390/768/1280px에서 **목록 24개와 필터 24개, 총 48개 최종 화면**을 저장했다. 스냅샷 Escape 초점 복귀 12개도 통과했다. VM과 열 구조, 업데이트 24px, 필터 버튼의 폰트·줄 높이·패딩·색상·폭/높이와 필터 라벨을 대조했다. 페이지 넘침, 헤더 버튼 겹침, 라벨/입력 겹침과 팝오버 경계를 검사했다. 실제 `innerWidth`와 PNG 픽셀 크기도 일치한다. 두 환경 모두 최종 앱 `js/app.7b27d56f.js`, 처리되지 않은 JavaScript 예외 0이다.

- [라이트 목록](browser/31-final-light-1280-snapshot.png)
- [다크 목록](browser/32-final-dark-1280-snapshot.png)
- [모바일 라이트 필터](browser/31-final-light-390-snapshot-filter.png)
- [데스크톱 다크 필터](browser/32-final-dark-1280-snapshot-filter.png)

## 검사·배포·보존

- 집중 검사 **5묶음/84개**, 변경 파일 lint, production UI build 통과. 실제 AntForm의 네이티브 submit 회귀 검사를 포함한다. [검사 로그](logs/tests.txt) · [lint](logs/lint.txt) · [빌드](logs/build.txt).
- 최종 정적 자산 **922개**의 SHA-256을 31·32에서 전부 대조했다. WEB-INF/META-INF·설정·관리 JAR·대용량 파일을 보존하고 Mold active, `/client/` HTTP 200을 확인했다. 활성 webapp 루트 전체 교체나 삭제 동기화는 사용하지 않았다. [31](31-ui-deploy.json) · [32](32-ui-deploy.json) · [자산 해시](asset-manifest.json).
- 스냅샷·사본 참조·볼륨 전체 DB 투영의 행수와 SHA-256이 검증 전후 동일하다. Windows/WSL의 기존 dirty FT 작업 HEAD·status·diff 해시도 동일하다. 검증을 위해 제품 업무 데이터를 보정하거나 기존 데이터를 정리하지 않았다.
- Java·업무 API·DB 스키마 변경, 전체 Cloud/Maven 빌드, Actions dispatch는 없다. 집중/실환경 검증은 전체 Cloud CI 통과를 뜻하지 않는다. Epic #1473과 PR #1492는 열림/미병합으로 유지한다. #1489 통합 회귀는 별도 후속 작업이다.

## 증거 재검증

[증거 압축 파일](browser-evidence.zip)에 화면·치수·키보드·요청/완료·오류/재시도 기록 176개를 보존했다. 리뷰에는 대표 화면 4개를 직접 노출하고 원본은 압축 파일로 모았다. 비밀번호·키·sessionkey를 포함하지 않는다.

[검증기](verify.py)를 이 디렉터리에 두고 `python verify.py`로 실행한다. 압축 파일을 읽어 원본 해시와 API/UI/DB·배포·FT 보존·소스 해시를 대조한다. **844개 PASS**. [결과](verification.json) · [원본 해시](browser-manifest.json) · [소스 해시](source-manifest.json).
