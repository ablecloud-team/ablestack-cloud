# CNI VM 표준 정리 및 31번 검증 (#1408)

검증 기간: 2026-10-17~18 KST. Epic #1395 / 누적 Draft PR #1409 / 브랜치 `dhslove/epic-1395-cni`.

최신 upstream Europa를 다시 조회했으며 기준은 `dc31262567f2afe5f6278fae61c1cef7e307fe3e`, 작업 시작 HEAD는 `739ef47b135765a708c20982a2a8b8c24bd4e0e7`이다. 별도 WSL ext4 작업 사본에서 작업했고 Windows의 기존 FT 변경은 건드리지 않았다.

## 결과와 적용 범위

현재 구현된 CNI 목록·상세·4개 탭·등록·삭제·고급 검색 및 Kubernetes CNI 선택기를 VM 공통 구조에 맞췄다. Breadcrumb 검색 설명, 본문 계약/용량 설명, 사용처 설명/중복 노드 제목, 등록 소개/API placeholder, 선택기 중복 선택 설명을 화면에서 제거했다. 삭제는 짧은 확인 문구와 필요한 참조/차단 사유를 표시한다.

사용처·노드·삭제 표는 VM 대표 탭의 작은 행 밀도와 표 내부 스크롤을 적용했다. 공통 `detail-tab-toolbar`와 `MoldDialog`를 재사용하며 갱신 보조 아이콘, 버튼/폼 여백, 자동 높이 본문을 정리했다. 프로젝트 소유자는 폼 라벨로 표시하고 AS_NUMBER 값은 ‘자동’으로 표시한다. 선택 상태는 시각적으로 숨겨도 접근성 status로 유지한다.

실제 빈 등록 폼에서 동일 본문 오류가 두 번 표시되는 것을 발견했다. CNI 이름·본문에 `validate-first`를 적용해 첫 오류만 표시하고 기존 required 표시/규칙과 일반 UserData 동작은 보존했다. API·ACL·삭제 전 재검사·바이트 보존 로직은 변경하지 않았다.

[설계와 화면 매핑](../../design/cni-ui-standard-1408.ko.md)

| 현재 화면 | VM 기준 | 검증 |
|---|---|---|
| 목록/Breadcrumb/검색/선택/페이지네이션 | AutogenView + ListView | 공용 구조 유지, 상시 검색 설명 제거, 긴 이름·UUID/다수 행/빈 결과 |
| 요약·소유 범위·상세·4개 탭 | ResourceLayout/ResourceView/InfoCard/DetailsTab | 동일 카드/탭, 긴 한글/파라미터 줄바꿈, 모바일 스택 |
| 본문 | VM과 공용 UserDataContent | 텍스트/Base64/바이너리, 코드 스크롤·키보드 포커스, 복사·파일 |
| 사용 Kubernetes/노드 | VmVolumesTab의 공통 툴바·작은 표 | 실제 클러스터 1·노드 2 UUID 일치, 내부 1100/800px 스크롤 |
| 코멘트·삭제 확인 팝오버 | VM과 공용 AnnotationsTab | 23개 코멘트, 실제 등록/삭제, 모바일 진입 후 팝오버 |
| 등록·삭제 대화상자 | MoldDialog 공통 크기/헤더/본문/푸터 | 필수 오류·제출 잠금·취소·Escape·사용 중 삭제 차단·실제 등록/삭제 |
| 고급 검색 팝오버 | AutogenView 공통 폼 | 동일 입력/버튼, 정확 이름 검색 |
| Kubernetes CNI 선택 영역 | UserDataSelection 공통 선택기 | 선택/접근성 상태·변수·자동값·표/모달 반응형 |

## 실행한 검증

- 초기 기능 회귀: **12 suites / 375 tests PASS**. 마지막 오류 중복 제거 후 영향받는 등록 소비자 **2 suites / 68 tests 재통과**. 두 숫자는 합산한 고유 테스트 수가 아니다.
- 최종 전체 UI lint 및 production build 성공. Browserslist 갱신 안내와 기존 번들 크기/성능 경고는 남는다. 전체 Cloud 빌드/전체 CI/Maven 빌드는 실행하지 않았다.
- 실제 API **52 checks**: 전용 CNI 24개 UUID/이름/저장 Base64, 프로젝트 참조·Running 클러스터·노드 2개, 소유자 허용/비회원 거부. 이후 실제 UI 등록 2건의 저장 바이트도 별도 대조했다.
- 실제 UI 기능 **33 checks**, DOM 기록 **99건**. 최종본 **8개 화면 × 4개 폭(1440/1024/768/390) × 2개 테마 = 64개 조합**을 포함한다. 64개는 기능 테스트 수와 다른 레이아웃 조합 수다.
- 기본 UUID 검색·정상 빈 목록·고급 정확 이름 검색, 등록 입력 잠금/완료, 실제 코멘트 생성/삭제, CNI 본문/클립보드/다운로드 바이트, 사용처·노드 링크·사용 중 삭제 차단을 확인했다.
- 본문과 사용처의 제한된 XHR 503/403 주입에서 기존 결과 보존/권한 데이터 제거·재시도를 확인했다. 이는 실제 ACL 검증과 구분한다. 실제 비회원 로그인에서도 프로젝트 본문/내보내기/사용 데이터가 노출되지 않았다.
- 마지막 배포본에서 빈 폼 오류 2개(필드당 1개), 첫 이름 입력 포커스·Tab·Escape, 실제 등록 33바이트/삭제 후 목록 복귀, 바이너리 Base64 전용, 코드 키보드 포커스를 재확인했다.
- 실제 본문 툴바 버튼 높이 32px, 좌표는 복사 x787.22 → 다운로드 x943.22 → 갱신 x1246.25였다. 코드/표 내부 스크롤과 전체 페이지 가로 넘침을 분리했고 기록한 화면에서 페이지 폭 초과/모달 푸터 이탈은 없었다.
- VM 목록·상세/볼륨·스냅샷 생성 대화상자를 읽기 전용으로 비교했다. 운영 VM의 생성 확인은 제출하지 않았다. 일반 UserData 본문/등록 소개·placeholder 및 기존 소비자 회귀를 확인했다.
- 브라우저의 기본 보기·관리자 로그인·원래 다크 테마·902×870 뷰포트를 복원하고 모든 XHR 제어를 해제했다.

[기능/레이아웃 기록](ui-evidence.json) · [실측 좌표](layout-metrics.json) · [요약](verification-summary.json) · [테스트](focused-tests-summary.txt) · [최종 등록 재검증](final-registration-tests.log)

## 같은 뷰포트 전후 및 VM 비교

기존 정적 번들/최종 스타일을 1440×1000 다크에서 비교했다. 비교 촬영 시 이전 정적 파일 877개 해시를 확인한 뒤 최종 정적 파일 877개를 복원했다. 최초 CDP 뷰포트의 캡처 배율 문제가 있어 최종 증적은 공식 viewport 기능으로 다시 촬영했다. 일반 팝오버는 변경된 폭에서 해당 버튼으로 다시 열어 확인했다. 선택한 34장만 저장소에 포함하고 전체 99건의 DOM 측정을 JSON으로 남겼다.

| 화면 | 전 | 후/기준 |
|---|---|---|
| 목록 | [전](screenshots/before-list-dark-1440.jpg) | [후](screenshots/after-list-dark-1440.jpg) · [VM](screenshots/vm-list-reference-dark-1440.jpg) |
| 등록 | [전](screenshots/before-register-dark-1440.jpg) | [후](screenshots/after-register-dark-1440.jpg) · [오류 중복 제거](screenshots/final-register-errors-deduplicated.jpg) |
| 본문 | [전](screenshots/before-content-dark-1440.jpg) | [후](screenshots/after-content-dark-1440.jpg) · [대용량 모바일](screenshots/large-content-dark-390.jpg) |
| 사용처 | [전](screenshots/before-usage-dark-1440.jpg) | [후](screenshots/after-usage-dark-1440.jpg) · [실제 사용 노드](screenshots/populated-usage-light-1440.jpg) |
| 삭제 | [전](screenshots/before-delete-dark-1440.jpg) | [후](screenshots/after-delete-dark-1440.jpg) · [모바일](screenshots/final-delete-dark-390.jpg) |
| 상세/대화상자 기준 | [VM 상세](screenshots/vm-detail-reference-dark-1440.jpg) | [VM 대화상자](screenshots/vm-dialog-reference-dark-1440.jpg) |
| 선택기 | [라이트](screenshots/wizard-cni-light-1440.jpg) | [다크 모바일](screenshots/wizard-cni-dark-390.jpg) |

## 최종 배포 및 정리

최종 백업: `/root/cni-1408-ui-20261018-001217`. 활성 경로 `/usr/share/cloudstack-management/webapp`에 **정적 877개 파일 해시 일치**로 배포했다. `WEB-INF`는 전후 존재하며 `META-INF`는 기존과 같이 부재다. 현재 manifest 안의 CNI/FTCTL 표식을 확인했다.

- index SHA256: `00fbc6196d92e75a83d3c47e33e741dcfbcd94fa8e5961624f9c9e3fe097f872`
- JAR SHA256: `5ef511623cdfa29df4b5e0d519a46a0acdc0bb1b2c239902bc5c63e608671174`
- config SHA256: `b7de5f6a1f7a3a17310ee7d34ca801bb316fc6f841039f865fbabf154cd59a13`
- Mold PID **1510297 유지**, 서비스 active, `/client/` **HTTP 200**. JAR/호스트 패키지 변경 및 서비스 재시작 없음.

전용 CNI **26개(실제 UI 등록 2개 포함)**, 일반 UserData 1개, 코멘트 24개(초기 23+UI 1), Kubernetes 1개·노드 2개·전용 네트워크·프로젝트·계정 2개·도메인을 정상 API/UI로 정리했다. 클러스터는 생성 job 성공/Running/노드 2개로 확인했으며 게스트 Kubernetes Ready 전체 체인을 추가 검증한 것으로 주장하지 않는다.

활성 잔여 데이터/코멘트/클러스터/참조/노드 매핑/VM/볼륨/네트워크/프로젝트/계정/도메인/사용자/API키는 **모두 0**. 호스트 31.1/2/3의 전용 노드 도메인도 **0**이다. 기존 CNI **3개 전체 API 응답**, Kubernetes **7개 안정 필드·노드 UUID**, 선택한 소유 범위 DB **36행**이 전후 동일하다. DB 전체의 모든 열을 비교했다는 의미는 아니다.

DB 비교 SHA256: `2d61f159980ac4fa490f62c74e16ce6024ac1f8bb9210c2c6caaa366c74357f7`.

[배포 증거](deployment-verification.txt) · [정리·기존 데이터 대조](cleanup-summary.json) · [호스트 정리](host-cleanup-summary.json)

## 종료 범위와 다음 작업

#1408은 **현재 구현된 전체 CNI 화면 및 공통 스타일**에 대해 종료한다. 아직 구현되지 않은 #1404 파일/도움말, #1405 변수 미리보기, #1406 복제, #1407 이력/비교는 이 보고서의 PASS 대상이 아니다. 각 이슈에 동일 표준/반응형/실배포 검증 기준을 연결하며, Epic 최종 전체 화면 완료 판정은 이 네 기능까지 구현한 뒤 수행한다. Epic #1395와 누적 PR #1409는 Open/Draft를 유지한다.

다음 순서: **#1404 → #1405 → #1406 → #1407**. #1407은 #1406의 관계 메타데이터에 의존한다. #1404부터 현재 일반 문서를 가리키는 도움말을 CNI 파일/예시/입력 흐름과 함께 보완한다.
