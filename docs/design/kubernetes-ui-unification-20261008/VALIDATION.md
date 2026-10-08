# 설계 목업 검증 기록

검증 대상은 `docs/design/kubernetes-ui-unification-20261008/prototype/`의 검토 엔트리다. 운영 UI 기능·backend·전체 Cloud·클러스터 생명주기 기능을 이번 검사로 다시 PASS 판정하지 않는다. 문서·목업 커밋에는 [skip ci]를 적용해 push의 자동 Coverage Check가 전체 Cloud 빌드를 시작하지 않도록 한다. 운영 PR은 이번 요청 범위에 포함하지 않는다.

## 기준과 환경

- upstream/origin Europa 기준 `c169d9a203f49ce07e038297873bc3c24cd8ffb4`; 게시 전 upstream branch SHA 재확인.
- WSL `rocky` ext4 독립 worktree. 기존 Windows 및 WSL FTCTL 작업 내용 보존.
- Node 20.20.2 / npm 10.8.2. 설치 라이브러리와 ui/package-lock.json의 Vue 3.2.37, Ant Design Vue 3.2.20, Vue CLI 4.5.19, Webpack 4.46.0, Icons Vue 7.0.1 일치.
- 기존 ui/vue.config.js와 Babel/PostCSS 및 공통 MoldDialog/ResourceLayout/Less를 재사용. API client import/호출, API proxy 없음.

## 수행 결과

| 검사 | 결과 | 한계 |
| --- | --- | --- |
| 목업 개발 서버 컴파일/접속 | PASS | localhost:8775의 검토 엔트리 |
| 최종 목업 엔트리 production build | PASS · exit 0 | Full Cloud/Maven 빌드가 아님 |
| 빌드된 정적 결과 브라우저 실행 | PASS · HA/etcd 어두운 테마 | 제목·폭·하단·강조 색상·콘솔 확인 |
| 43 시나리오 × 2 테마 렌더링 | PASS · 86장 | 샘플 데이터의 렌더링 검사 |
| 390×844 대표 상세/HA·etcd/ISO/삭제 × 2 테마 | PASS · 8장 | 모든 폼 조합의 모바일 기능 시험은 아님 |
| 765/766/991/992/1279/1280px 상세·HA 폼 | PASS · 12회 | 실 브라우저 DOM 레이아웃 관측 |
| 페이지 가로 넘침 | 없음 | 테이블 내부 스크롤은 허용 |
| 모달 하단 버튼의 viewport 내 위치 | 확인 | 긴 본문만 스크롤 |
| 생성 다음/이전/취소→목록 | 확인 | 입력 보존 및 실제 API submit은 후속 구현 |
| 업그레이드 후보 없음 / ISO 입력 오류 실행 버튼 | 비활성 확인 | 고정된 오류 시나리오이며 서버 검증은 아님 |
| 브라우저 error/warn 로그 | 수집 결과 0건 | 관측한 목업 탭의 로그 |
| 이미지 목록·형식·해시 | JPEG 94장 확인 | image-manifest.json에 SHA256 기록 |
| 추가 JS/Vue/HTML 소스 license header | Apache 표기 확인 | GitHub Actions License Check 실행을 뜻하지 않음 |
| 문서 링크·git diff --check | 확인 | 게시 후 이미지 URL도 별도 확인 |

공통 ResourceLayout에 xs 값이 없어 768px 미만에서 열 너비가 내용에 맞춰 줄어드는 것을 경계 폭 검사에서 관측했다. 목업의 해당 분기만 전체 너비로 보정하고 다시 확인했다. 운영 공통 컴포넌트와 VM은 수정하지 않았으며 구현 수용 기준에 공통 xs 처리/타 화면 회귀 확인을 포함했다. 어두운 테마의 주요/파괴적 버튼도 기존 의미 토큰으로 표현한 제안이다.

Babel/webpack 컴파일과 테마 생성에 기존 Browserslist 데이터 갱신 안내가 있을 수 있다. 기존 라이브러리 잠금 파일은 유지한다. 공통 public 자산/전체 AntD 의존성으로 인한 번들·엔트리 크기 경고 3건(asset size / entrypoint size / performance recommendations)은 목업의 기능 컴파일 실패와 구분한다.

## 증거와 재현

[렌더링 관측 원본](render-checks.json) · [이미지 해시](image-manifest.json) · [실행 방법](README.md) · [통합 설계](DESIGN.md)

사진은 Chrome에서 실제 Vue UI를 캡처한 JPEG이며 이미지 생성/합성 자료가 아니다. 모든 공개 이름·IP·체크섬은 예시 데이터다. 클러스터 31의 현재 화면은 레퍼런스로 읽기만 했고 자원 생성/수정/삭제·UI 배포를 수행하지 않았다.

실제 API/state/RBAC/async job·업그레이드·CSI 정리·스토리지·접근성·언어 회귀는 통합 설계의 후속 구현 체크리스트로 남겨 둔다.
