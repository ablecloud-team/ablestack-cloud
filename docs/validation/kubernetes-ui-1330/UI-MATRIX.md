<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
# #1330 Europa 실제 UI 검증 매트릭스

2026-10-08–09, 관리 서버 31번, Vue 3 / Ant Design Vue 기반 production UI. 승인 목업은 `docs/design/kubernetes-ui-unification-20261008/`이며 이 문서는 실제 배포 UI와 API·Kubernetes·스토리지 결과를 기록한다. 화면 이미지는 실제 Chrome 화면이다.

| 구간 | 실제 검증과 결과 | 판정 |
|---|---|---|
| 소스 | `codex/kubernetes-ui-1330`, upstream `ablestack-europa` c169d9a203f49ce07e038297873bc3c24cd8ffb4 기반, dhslove ext4 작업 트리 | 확인 |
| 검사 | 변경 파일 lint, 관련 18 suites / 151 tests | PASS |
| UI 빌드 | Node 20.20.2, `NODE_OPTIONS=--openssl-legacy-provider npm run build`; production UI 모듈 | PASS, 최종 배포 기록은 README |
| 목록 | VM 공통 행·자원 표시·이름 검색 1건·선택/해제·이름 정렬·우클릭 메뉴·전체 7건 pager | PASS |
| 상세 | 좌우 공통 ResourceLayout·정보 탭 버튼 0개·상단 작업 메뉴·좌측 정보 우클릭·짧은 외부 워커 레이블 | PASS |
| 관리형 생성 | 5단계, 기본 KVM 값 제출, strict GFS2 4CPU/8GB, root20GB, control1/worker1, 1.34.12 ISO, CSI off | PASS, 최종 cluster75 Running / 2 Ready |
| 생성 거절 | 부적합 egress·strict VR 디스크 제약·잘못된 값: 생성 전 API 거절, 입력 유지 | PASS |
| 확장·축소 폼 | 760px 전체 너비·두 열 오퍼링/AS min/max·초안 증감 요약·footer gap8px·390px 정보/폼 한 열; 초안 취소 | PASS, 실제 light/dark 검증 |
| 관리형 생명주기 | cluster70 워커1→2→1, AS enable(1/1)/disable, stop/start·Affinity, 1.34.9→1.34.12, UI 삭제 | PASS |
| 외부 관리형 | 3단계 등록, 등록 후 노드 연결 안내, VM 워커/컨트롤 매핑·해제·보존 삭제 | PASS, 삭제는 removed 플래그 확인 |
| 관리형 외부 워커 | VM352 추가 job10275 / v1.34.12 Ready, 분리 job10281 / VM Running 보존 | PASS, 준비 이미지 전제조건은 #1314 |
| 액세스 | 다운로드/업데이트 8px 간격·카드 구분·구성 기본 숨김·Show/Hide·API kubectl 접근 | PASS |
| UI 파일 저장 | 최종 cluster75 구성 다운로드 실행, Windows 저장 창의 최종 파일 확인 | PASS, 5,636bytes 및 API 구성 SHA 일치; 저장 파일로 2 Ready / 13 Running |
| 방화벽 | 18443–18444 제한 CIDR 생성·삭제 후 교체·삭제, API/SSH 소유 규칙 보호 | PASS |
| PF | public/guest port pair·VM/NIC 목적 그룹, VM352 18443→18080 생성·18081 변경/원복·삭제 | PASS |
| LB | VM352만 선택·기본 NIC 지정, 18444→18080 생성·CIDR/알고리즘 편집·VM 해제/재연결·태그 추가/삭제 | PASS, 수동 규칙 정리 기록은 README |
| PF/LB 삭제 후보 | Present로 Destroyed/Expunging 제외; 실제 후보4→2, 삭제 이름 검색0·초기화2·기본 NIC 선택, 두 테마; Stopped/paging/count 단위 회귀 | PASS, #1334 |
| 실제 통신 | Windows 시험 PC 10.10.21.101/32에서 PF/LB `UI1330 backend OK` 응답 | PASS |
| 갱신 | 기존 규칙 및 대상 행 유지·전체 skeleton0/spinner0·버튼만 loading1·toolbar gap8px | PASS, 최종 패키지의 실 화면 재확인 완료 |
| SourceBased | 재열기 누락 #1331 복원 패치·회귀 검사, 실제 10k/30m 재열기→20k/40m 수정·재열기→정책 제거 | PASS |
| 보호 | API6443 LB/PF·관리 SSH 규칙 체크박스/설정/태그/삭제 비활성화, 일반 규칙 작업 가능 | PASS |
| ISO | 이름 검색1건·URL 등록·잘못된 semantic version API431 및 입력 유지·state-only 수정·Disabled | PASS |
| ISO 다운로드 | 기존 Release URL은 관리 서버 redirect=false 정책으로 HTTP302 차단. #1228/#1230에 명시된 전제조건 | PASS, 사용자 승인 후 true→Ready 확인→원래 false 복구 |
| ISO 로컬 업로드 | live API·1000px 폼·다크 가이드·필수값 거절 확인, 공개 Release ISO 832,899,072bytes 및 SHA-256 확인 | PASS, 사용자 파일 선택 후 UI 제출·832,899,072bytes 실제 전송·Ready; 시험 ISO 삭제 |
| 공통 화면 | 일반 VM strict custom root120GB/GFS2 생성, 공통 네트워크 화면/이벤트 목록·코멘트 생성/삭제 | PASS, #1233 회귀 수정 포함 |
| 키보드 | Kubernetes/ISO 목록 Shift+F10·Escape, Tab/Shift+Tab·모달 안 초점 순환·Escape/취소·실행 버튼 복귀, 삭제 트리거/새 모달 DOM 회귀 | PASS, 전체 접근성 인증은 범위 밖 |
| 테마 | 주요 입력/안내문/표·팝업 light/dark, ko/en; 실제 언어 전환 후 LB VM 머리글 반영 | PASS |
| 좁은 화면 | 390×844, 화면 body380px·대화상자380px, 내용 내부 표 스크롤, 기본 viewport 복구 | PASS |
| 조건부 기능 | VPC ACL·Tungsten·SSL 인증서·LB AutoScale VM 그룹: 31번 제공/설정 범위 외. 공통 API 권한 조건 및 정적/단위 검증 | 해당 환경 실 동작 미검증 |
| 장기 클러스터 | 기존6개 cluster31–36 Running·removed=NULL; 시험 자원과 별도 유지 | 보존 |
| PR | Korean PR·전체 추적 소스 RAT·GitHub License Check·Conflict | README와 PR의 최신 결과 참조 |

전체 Cloud 빌드나 최종 릴리즈 통합 시험 결과로 해석하지 않는다. UI 작업은 브라우저로 실행하고 API/DB/SSH/kubectl은 결과 확인에 사용했다. 비밀번호, 세션 키, kubeconfig 원문과 원본 보안 로그는 저장소나 이슈에 게시하지 않는다.
