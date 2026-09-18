<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# VM 상세 스냅샷 작업 검증

이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1100

## 변경

승인된 목록/행 작업/확인 대화상자를 전용 VmSnapshotsTab으로 구현했다. 탭 내부 중복 제목은 제거했다. 생성은 기존 VM 작업 폼을 재사용하고 복원/삭제는 제출 직전에 VM 및 스냅샷 상태를 다시 검증한다. 공통 비동기 작업 추적과 VM별 잠금으로 중복 제출을 방지한다.

목록은 10초 주기로 갱신하며 기존 행을 유지한다. VM/프로젝트/사용자 전환의 이전 응답은 무시한다. 작업 종료 시 목록과 VM 정보를 갱신한다. 권한, 상태별 비활성 사유, 다크 테마 및 좁은 화면 메뉴를 적용했다.

## Docker 검증

- Rocky Linux 9.8 linux/amd64, Node 14/npm 6 환경의 UI 모듈만 빌드했다.
- 전체 단위 테스트 59 suites / 537 tests 통과.
- 신규 테스트: 상태/유형/권한/잠금, 10초 자동 갱신, 기존 행 유지, VM 전환 응답 차단, 제출 직전 상태 재검사 및 중복 클릭.
- 변경 파일 lint 통과. 생산용 UI 빌드 통과. 기존 번들 크기 및 Browserslist 데이터 경고는 남아 있다.

## 13번 실제 기능 검증

대상은 별도 VM `issue1100-snapshot-validation` (08e86611-bd8c-4816-8071-4ede6f1e5b58, i-2-167-VM)이다. 사용자 W2025-Base의 스냅샷은 조작하지 않았다.

- UI에서 `issue1100-disk` 생성 → Ready/current 표시 및 생성 완료 알림 확인.
- Stopped + Disk UI 복원 → 완료 알림, 잠금 해제 및 현재 배지 확인.
- API로 `issue1100-periodic` 생성 → 화면 조작 없이 주기 갱신으로 두 번째 행과 부모/현재 정보 표시 확인.
- 사용자 승인 후 해당 periodic 스냅샷을 UI에서 삭제 → 완료 알림, 행 제거 및 이전 스냅샷의 current 배지 반영 확인.
- API로 검증 VM 시작 → 상세 상태 자동 갱신과 `VM 정지 후 복원 가능` 표시/복원 버튼 비활성 확인. 검증 후 VM은 다시 정지했다.
- 700px 폭에서 직접 복원 버튼을 숨기고 더보기 메뉴의 복원/삭제가 접근 가능한지 확인. 검증 후 화면 크기를 원복했다.
- 메모리 포함 생성은 검증 VM의 KVM/스토리지 조합에서 서버가 `KVM does not support the type of Snapshot requested`로 거부했다. 오류가 사용자에게 표시되고 폼을 취소할 수 있음을 확인했다. 메모리 포함 복원 성공 경로는 이 VM에서 실물 검증하지 못했으며 상태 매트릭스는 단위 테스트로 검증했다.
- 다크 테마 실물 검증 중 발견한 확인 표의 밝은 배경과 페이지 이동 버튼은 공통 테마 변수로 수정했다.

## 범위와 잔여 검증

UI만 배포하며 서버 config.json과 WEB-INF 체크섬 보존을 검증했다. 백엔드/에이전트 재시작은 없다. 단위 테스트의 권한·오래된 응답·중복 제출 검증과 실제 관리자 테스트를 구분한다. 일반 사용자/프로젝트 계정, 메모리 포함 복원, 실제 통신 단절의 전체 시나리오는 이번 클러스터 검증에 포함하지 않았다.

검증 VM은 정지 상태로 남기고 UI에서 생성한 디스크 스냅샷 하나를 재확인용으로 보존한다.

## 최종 배포 확인

- 소스: `586d67f58188c9678dde3889ff21b5777d63de76`
- UI 압축 산출물 SHA256: `717d442d8a4d8b0bee764da5d5c6927880e5dad7d129b0580e448e4c7d8fdc0a`
- 직전 UI 복구 파일: `/var/tmp/issue1100-20260916-015804/ui-before.tar.gz`
- 최종 배포 후 다크 테마 확인 대화상자의 항목명/값/경계선과 페이지 이동 버튼을 육안 확인했다.

## 후속 UI 보완

등록되지 않은 EllipsisOutlined가 빈 커스텀 태그로 렌더링되어 더보기 버튼이 비어 보였다. 기존 등록된 DownOutlined를 버튼 icon 슬롯에 배치했다.

VM 스냅샷에서 볼륨 스냅샷을 만드는 폼은 기존 400px 고정 폭을 새 탭의 대화상자에도 적용해 오른쪽 여백이 남았다. 새 탭에서만 fullWidth 옵션을 사용해 가용 폭을 채우고, 항목 간격과 하단 구분선/버튼 정렬을 정리했다. 기존 전체 메뉴에서 재사용하는 폼의 기본 폭은 유지한다.

변경 파일 lint 및 관련 컴포넌트 테스트 5개 통과.

후속 수정 Docker UI 빌드 및 13번 배포 완료. 실제 DOM에서 아래쪽 화살표 SVG를 확인했고, 메뉴 열림과 대화상자 472px 가용 폭/390px 화면의 폼 및 버튼 잘림 없음을 확인했다. 볼륨 생성은 배치 확인 범위에서 실행하지 않았다.

- 후속 배포 소스: `d3debad735cd7cf8683df98e3f3b13582d098770`
- 산출물 SHA256: `79eed8b19ab5b3e3ae31d46ed5e3087365bebd8495d41dfafc7f5a796c2d4286`
- 직전 UI 복구 파일: `/var/tmp/issue1100-20260916-020933/ui-before.tar.gz`
