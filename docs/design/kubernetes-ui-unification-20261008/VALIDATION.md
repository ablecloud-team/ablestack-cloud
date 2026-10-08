# 설계 목업 검증 기록

검증 대상은 이 문서 아래 prototype의 별도 Vue 엔트리다. 운영 UI/backend/전체 Cloud/클러스터 생명주기 기능을 이번 검사로 다시 PASS 판정하지 않는다. 최신 요구를 반영한 검토용 설계와 이미지이며 운영 소스 구현/배포는 후속 작업이다. 같은 #1330을 갱신하며 중복 이슈/PR을 만들지 않는다. 커밋의 [skip ci]로 전체 Cloud 자동 빌드를 시작하지 않는다.

## 기준과 환경

- Europa 기준 c169d9a203f49ce07e038297873bc3c24cd8ffb4. WSL rocky ext4 독립 worktree; 기존 Windows/WSL FTCTL 변경 보존.
- Node 20.20.2/npm 10.8.2, 잠금 파일의 Vue 3.2.37/AntD Vue 3.2.20/Vue CLI 4.5.19/Webpack 4.46.0/Icons Vue 7.0.1.
- 기존 MoldDialog/ResourceLayout/Less/테마 토큰 사용. EventsTab/AnnotationsTab/ListView는 운영 파일 그대로 import한다. 별도 엔트리의 exact fixture alias로 HTTP 없는 메모리 예시를 연결하며 운영 API/프록시/자격증명을 사용하지 않는다.

## 이번 보완 검증

| 검사 | 결과 / 범위 |
| --- | --- |
| 개발 서버 컴파일 | PASS |
| 목업 엔트리 production build / 정적 결과 실행 | build-result.json의 최종 결과 참조. Full Cloud/Maven 빌드 아님 |
| 76 시나리오 두 테마 | 152장, 페이지 전체 가로 넘침 없음 |
| 모바일 390×844 | 대표 9개 화면 두 테마 18장, 가로 넘침 없음. 표 내부 스크롤 허용 |
| 상세 정보 전용 | 클러스터/ISO 두 테마의 상세 탭 본문 버튼 0개. 리소스 상단/기능 탭 작업과 구분 |
| 네트워크 공통 표준 | IP/Kubernetes 같은 NetworkRulesTabLayout 및 NetworkRuleForm 사용, 상단 주 버튼/업데이트·상세 mini pagination |
| 콘텐츠 배치 | 관련된 입력만 쌍으로 묶고 긴 CIDR은 한 행 전체 사용. 구분 제목과 입력 라벨 모두 14px, 굵기 600·1px 선·일정한 여백으로 구분 |
| PF 목적별 배치 | 프로토콜 단독 → 공인 시작/끝 → 게스트 시작/끝 → VM/NIC. 각 쌍 desktop top 좌표 일치, mobile 한 열. 구분 label은 본문과 같은 14px/굵기/얇은 선이며 글자 확대 없음 |
| LB 명시 대상 선택 | 기본 선택 0개/제출 비활성 → worker-01 체크 → VM 1개/NIC 1개·제출 활성. 선택 IP만 연결하는 API 계약 확인 |
| LB 규칙만 생성 | 명시 모드 전환 후 대상 없는 생성 안내/별도 버튼 활성. 자동 전체 VM 연결 없음 |
| ICMP/SSL 조건 | 프로토콜 선택 시 ICMP 유형/코드, SSL 인증서/backend SSL 입력 전환 확인. 추가 이미지 4장 |
| LB 선택/규칙만 생성 분기 | 두 테마 추가 이미지 4장 |
| 다크 안내 대비 | 검정 도움말 재현 후 공통 토큰 보정. 캡처의 일반 안내/도움말 합성 대비 최소 4.77:1. 기준 4.5:1 이상 |
| 다크 컨트롤 | disabled NIC의 흰 배경 제거, radio/multi-select/행 기본·선택·hover를 semantic 토큰으로 통일. 실제 수정 화면 시각 확인 |
| 공통 이벤트/코멘트 | 직접 import한 기존 컴포넌트의 열 선택/업데이트·코멘트 입력/공개 범위·기존 페이지네이션 표시 확인. 삭제/공개 범위 2개 확인 상태도 기존 Popconfirm 직접 사용. 소스 수정 없음 |
| 복수 페이지 | 메인 42개/20개 크기/3페이지, 기존 이벤트 42개/10개 크기/5페이지에서 모두 41–42 표시. 4장 |
| 이미지 | JPEG 182장. image-manifest.json의 크기/SHA256 기록 |
| 목록·상세 공통 메뉴 | 기존 ResourceContextMenu/ActionButton/ResourceActionMenu 직접 사용. 우클릭·Shift+F10·Escape, 상태/관리 유형/다중 선택 메뉴 관측. 9개 메뉴 상태 두 테마 18장 |
| 목록 Kubernetes 아이콘 | SVG currentColor/테마 텍스트 토큰. 다크 일반 행의 관측 대비 13.35:1, 선택 행도 밝은 색 유지. 이클립스 버튼 0개 |
| 가상머신 탭 주 버튼 | CloudManaged: 클러스터 확장(수동/AutoScaler). ExternalManaged: 외부 노드 추가(기존 VM/역할 연결). API 차이 소스 검증 |
| 외부 생성 후 연결 | 기본 등록만/등록 후 연결 선택 → 검토 전환. 외부 VM 기본 미선택 제출 제한 → 제어 역할/VM 선택 후 활성 확인. 실제 API 호출 없음 |
| 운영 변경 | ui/src diff 없음, 배포/실제 네트워크 API/31번 자원 변경 없음 |

기존 소스의 ListView fragment에 전달한 emits 선언 관련 개발 모드 Vue 경고가 남는다. 이미 표준화된 공통 컴포넌트를 수정하지 않는 이번 범위에서 이를 숨기거나 변경하지 않는다. runtime error와 구분한다. 기존 production build의 번들 크기/performance 경고와 Browserslist 안내도 의존성 변경 없이 기록한다.

## 증거와 한계

[이번 렌더링·그룹·선택·페이지 관측](network-render-checks.json) · [이미지 해시](image-manifest.json) · [실행 방법](README.md) · [공통 네트워크/API/보호 계약](NETWORK-DESIGN.md) · [통합 설계](DESIGN.md) · [이전 목록·외부 등록 보완 관측](correction-checks.json)

이번 파일의 DOM 색상/좌표/예시 선택 검사는 실제 API/RBAC/async job/네트워크 트래픽의 통과를 의미하지 않는다. 전체 키보드 접근성이나 모든 Provider/VPC 조합도 아직 운영 검증하지 않았다. 긴 본문의 스크롤 아래 안내와 일반/hover/disabled/오류 상태, VM 및 다른 공통 대화상자의 실제 회귀는 구현 수용 기준이다.

이미지는 Chrome에서 실제 Vue 목업을 캡처한 자료다. 공개 이름/IP는 예시이고 운영 자격증명·31번 실제 화면은 포함하지 않는다. 최초 31번 VM 레퍼런스 및 이전 breakpoint/외부 등록/페이지 조작 근거는 이전 관측 JSON으로 보존한다. 현재 변경은 후속 구현 요구로 #1330에 통합한다.
