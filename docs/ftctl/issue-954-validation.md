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

# #954 구현·배포·실제 UI 검증 — 2026-09-11

## 변경과 브랜치
- 기준 `c117226bc8158288df8c8ed0fb4925c3d5ea2176` (#980 포함), 안전 브랜치 `codex/fix-954-target-compute-sizing`.
- 구현 `c740c03069`: 고정/가변 CPU 수·CPU 속도(MHz)·메모리(MiB), 입력 범위, 사용자 값 보존, 서버 offering 접근/사양 검증.
- 추가 수정 `6717ea73d7`: 실제 UI에서 발견한 부분 편집의 기존 사양·디스크·네트워크·정책 유실 방지. 최초 시험 Plan54의 실패를 제외하고 새 Plan55로 재검증했다.
- qemu 운영 소스는 변경하지 않았다. 기존 guestprep의 target.cpuNumber/target.memory 우선 계약을 재사용한다. 실행별 override/기존 VM 재사이징은 #955다.

## 모듈 검증
- WSL rocky ext4 변경 DR Maven 모듈 package: **470 tests, 실패/오류 0, BUILD SUCCESS** (`dr-build-final-v3.log`). 전체 Cloud 빌드는 하지 않았다.
- UI helper **16 tests PASS**, 변경 JS/Vue lint PASS, UI production build PASS.
- qemu guestprep manifest **3 tests PASS**, boot hardware smoke PASS. qemu 패키지 빌드/배포 없음.
- 서버 회귀: 고정 오퍼링 우선, dynamic 속도 누락, 부분 고정, 음수 거절, 부분 수정의 기존 매핑/정책/확장 JSON 보존.

## 배포
31/32 관리 서버에 위 변경 클래스4개를 기존 통합 JAR의 다른 항목을 보존하여 적용했다. 최종 백업 `/root/issue954-v3-20260911/`. 설치 클래스 SHA256, mold active, /client/ HTTP200, WEB-INF 존재를 양쪽에서 확인했다. 원본13/22 및 compute 호스트의 운영 모듈은 바꾸지 않았다.

UI는 static 파일만 배포했다. WEB-INF/META-INF와 기존 config.json 심볼릭 링크를 보존했다. 양쪽 index.html SHA256 `3d9ab3e7ef29aab0b0481b308c95586965be7235b0852f50f1d9d172422e35b6`.

설치 클래스 SHA256:
- DrPlanTargetPlacementResolverImpl: `beeba45aeae51d00c6e82e964f97680de13e18a426dc8e0f894351cdf36a8fec`
- DrPlanGuidedSpecBuilder: `e53fad56b885d85ae2a9140e4883b4298c539f0f248f147b827931f090b0fc4c`
- DrPlanReadinessValidator: `44cc65448e0a383525f71718ef8732bc2ce705b492baf73026047f700db485f3`
- UpdateDrPlanCmd: `dd7c6339139959a87773df3e05ae5717b2ffbb42c5a1515c0ef9d6b51595d26d`

31 디스크 여유 부족 시 사전 점검에서 중단했다. 이전 시험 백업만 WSL 별도 보관 후 SHA256 대조하여 원격 사본을 정리했다. 현재 JAR/최신 백업을 지우지 않았다. symlink 추출 보존/공간 preflight는 기존 #1033에 후속 기록했다.

## 실제 UI 시험
브라우저에서 생성/편집/확인/삭제 버튼을 직접 조작했다. API 직접 호출을 UI 시험으로 대체하지 않았다. DB는 읽기 전용 저장 결과 대조에만 사용했다.

| 항목 | 실제 결과 |
|---|---|
| 32 사용자 정의 생성 | 정지 VMware R10-EFI-LEGACY-01(vm-4366), 원본2 CPU/4096 MiB보다 작은 **1 CPU/2000 MHz/2048 MiB**, offering 1C1GB-TO-64C96GB-FR 생성 성공. Plan55 `f248f5b4-1f0b-41ea-b13c-fecea5c97388` |
| 재조회/편집 | 인벤토리 재조회 후1/2000/2048 유지. CPU4·8192 MiB만 변경 저장 후 편집 재개에서 **4/2000/8192** 유지. Primary RBD, Custom disk, L2-Network 및 디스크1개 매핑 보존 확인 |
| 고정 offering | 4c8g-rbd-ha 전환 시 **4/2000/8192 모두 disabled**. 저장 및 재편집 후 동일 |
| offering 재전환 | 가변 offering으로 명시 전환 시 원본 초기값2/4096과 빈 속도 표시. 이전 고정 offering 값 혼입 없음 |
| 잘못된 값 | CPU속도 미입력은 필드별 정수 범위 오류로 저장 차단. 동일 UI bundle의 앞선 시험에서 CPU1.5도1–64 정수 오류로 차단 |
| 31 부분 고정 기존 Plan | u26-base DR Plan 편집/인벤토리 재조회: CPU4와8192 MiB는 편집 가능, **속도2000만 disabled**. FR-2Core-4GB-TO-16Core-32GB의 저장값 보존. 변경 없이 취소 |
| 정리 | Plan54 실패 초안과 Plan55 최종 시험 모두 기존 UI 삭제 대화상자에서 force 미선택으로 삭제. 32 목록의 기존2개 계획만 남음. 동기화를 시작하지 않아 새 VM/볼륨 생성 없음 |

31 부분 고정은 기존 계획의 표시·저장값 보존을 검증했고 그 계획의 사양은 변경하지 않았다. 권한별 모든 계정/도메인 조합의 실환경 시험 및 실제 VM admission/quota 전체 조합을 이번 UI 결과로 PASS 처리하지 않는다. Plan은 자원을 예약하지 않으며 실제 VM 생성 시 기존 admission 검사를 사용한다.

## 기존 복제 회귀 상태와 범위
- qcow2: checkpoint1952 CBT_INCREMENTAL, READY/HEALTHY/IDLE. UI WITHIN_RPO/RUNNING/RESUMED.
- RBD: checkpoint4707 CBT_INCREMENTAL, READY/HEALTHY/IDLE.
- VMware: checkpoint273 이후 UI274 incremental COMPLETED, TARGET_READY/WITHIN_RPO/RUNNING/HEALTHY/RESUMED.
- VMware 원본과 대상 QGA는 사용하지 않았다. 이 사양 UI 변경으로 새 failover/failback 전체 체인이나 실제 target VM resize를 시험했다고 주장하지 않는다.

로컬 증거: `/home/ablecloud/work/issue954-evidence/`의 빌드·lint·guestprep·설치검증·final-*.json. UI 결과는 본 표와 도구 실행 기록에 보존했다. 실패 원인은 #954 댓글 및 설계 문서, 배포 preflight는 #1033에 기록했다.

## 다음 작업
Nutanix 제외 개발/보완 **21건(Cloud20+qemu1)**, 핵심 구현·시험 완료/병합·잔여 검증 **34건**. 다음 DR P2는 #1021→#1018, Diplo #1029는 별도 P1. #962 확장+#1006, #951→#952→#953 이후 기존 #950 순서를 유지한다. Cloud PR #1022 충돌 해소/최종 통합 회귀와 upstream 병합은 별도이며 이번에 병합하지 않았다.
