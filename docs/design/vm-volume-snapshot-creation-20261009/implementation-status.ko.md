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

# Epic #1335 구현 및 검증 진행 상태

**최종 상태(2026-10-10):** 기존 #1335 및 #1336–#1343 검증에 더해 하위 #1345의 일반 업로드·파일 등록·DATA 스냅샷 생성 경로를 현재 Mold UI에 구현했다. 변경 모듈 빌드·31/32 배포·실제 UI 결과는 [일반 볼륨 추가 범위 최종 보고](external-volume-validation.ko.md)에 연결한다. PR #1344는 검토·병합 단계다. [기존 장애·회귀·배포 보고](resilience-ui-validation.ko.md)는 이전 ROOT 출처 검증 증거이며 아래 버전별 기록도 당시 증거로 유지한다.

기존 Epic #1335와 #1336–#1343 안에서 구현했다. 대표 행렬 **16개 모두 실제 UI와 게스트 데이터 검증을 통과**했다. 권한·삭제 원본·용량·응답 유실·재시작·부분 실패·회귀 결과는 추가 보고서에 연결했다. 31번 최초 Agent 장애의 운영 복구 필요와 집계 IOPS 미설정 등 범위를 명시하고 전체 자동 복구·전체 CI PASS로 확대하지 않는다. 코드 검토와 병합은 별도 단계다.

## 실제 UI 생성·게스트 검증

| 환경 | 볼륨 startvm=false | 볼륨 startvm=true | 스냅샷 startvm=false | 스냅샷 startvm=true |
|---|---|---|---|---|
| 31 GFS2 / Linux BIOS | PASS | PASS | PASS | PASS |
| 32 Ceph krbd / Linux BIOS | PASS | PASS | PASS | PASS |
| 31 GFS2 / Windows UEFI | PASS | PASS | PASS | PASS |
| 32 Ceph krbd / Windows UEFI | PASS | PASS | PASS | PASS |

각 PASS는 실제 UI 원본 선택·확인·제출·생성 결과·부팅·정상 정지와 연결한다. 볼륨은 같은 UUID의 ROOT/device0 편입 및 원본 분리 보존, 스냅샷은 새 ROOT/device0와 백업 시점 한글 파일 SHA256 일치 및 원본 보존을 확인했다. startvm=false는 최초 Stopped를 확인한 뒤 UI에서 시작했다. API·host XML·QGA·게스트 SSH 파일 읽기는 UI 경로의 보조 증거다. 케이스별 VM/job/ROOT/체크섬은 `evidence/case*-linux-*.json`에 기록했다.

추가로 두 환경에서 스냅샷 ROOT 100GiB + 새 DATA 10GiB ×2, 수동 ROOT/DATA 풀 선택, UI 합산 120GiB, device0/1/2, 실제 게스트 디스크 3개와 백업 시점 데이터 확인을 통과했다. 31번 사례는 대표 snapshot-on과 같은 VM이며 중복 집계하지 않는다.

Windows UEFI 8개도 실제 UI 제출, Administrator 로그인, GPT 100GiB, 한글 UTF-8 파일/Notepad, SHA256, ROOT/device0 및 정상 정지를 통과했다. 볼륨은 변경 후 파일을 같은 UUID에서 읽었고, 스냅샷은 새 UUID의 ROOT에서 변경 전 파일을 읽었다. 각 VM의 q35/UEFI secure=no와 고유 NVRAM 경로, GFS2 qcow2 파일 또는 krbd raw 블록 장치를 호스트 XML로 대조했다. 게스트 호스트명/계정은 복제된 그대로 유지되었으며 같은 환경의 Windows 테스트 VM은 한 번에 한 대만 실행했다. 비밀번호 변경·QGA 파일 주입·원격 게스트 접근 설정 없이 콘솔에서 검증했다. [Windows 상세 및 증거](windows-ui-validation.ko.md)를 연결한다.

## 실환경에서 발견하고 수정한 결함

1. 사전 검사 BaseListCmd 응답을 `count: 1, creationsource: [...]` 목록 계약과 일치시켰다.
2. UI POST의 잘못된 API 인자 전달로 서비스 오퍼링이 빠지는 문제를 수정했다.
3. 볼륨 편입이 legacy `details.volumeId` shortcut을 타면서 DATADISK를 ROOT/device0으로 변경하지 못하는 문제를 수정했다. 실제 public allocate 회귀 테스트 4개를 추가했다.
4. 스냅샷 Allocated ROOT의 poolId를 미리 지정해 allocator가 스토리지를 제외하는 문제를 수정했다. 대상 풀 요구사항은 유지하고 복구 완료 때 위치를 지정한다.
5. 복구 실패를 한글 안내와 기술 오류 상세로 제공하며 자동 재제출을 하지 않는다. 부분 생성 VM 링크를 출처 정보로 복구한다.
6. cloud-init이 새 VM 부팅 때 호스트명·SSH 호스트 키를 재생성하는 실제 동작에 맞춰 안내를 보완했다.
7. 추가 DATA 수량 입력과 크기/수량 검증을 보완하고, 스냅샷 DATA 조회에 snapshotid와 ROOT/DATA 동시 용량 계산을 포함했다.
8. 프로젝트 유형만 선택하고 프로젝트는 비워 둔 상태에서 이전 계정 원본이 되살아나는 문제를 발견했다. 소유자 확정 전 조회·제출을 차단하고 오래된 응답을 무시한다.
9. 밝은 테마의 녹색 상태 태그가 3.37:1로 표시되는 문제를 발견해 공통 텍스트 토큰으로 대비를 개선했다.
10. 템플릿 사전 선택 URL에서 폼의 미설정 압축 값 때문에 비활성 템플릿/오퍼링을 불일치로 거절하는 문제를 수정했다. 리소스의 실제 kvdoenable을 비교하고, 템플릿을 다시 선택하지 않은 실제 UI 제출로 Stopped VM과 ROOT/device0 100GiB 생성을 확인했다.

처음 실패한 VM·볼륨은 진단 증거로 남겼다. 후속 수정 결과를 이전 실패의 PASS로 합산하지 않는다. krbd 호스트에서 읽기 전용으로 사용 중인 원본은 최종 UI 검사에서 다른 VM 사용 중 사유로 차단되었다. 임시 domain/map 정리 후 기존 runtime 목록이 일치했다.

## 모듈·UI 검증 및 배포

변경 Maven 모듈 API/Core/Server/Engine Orchestration/KVM만 WSL ext4에서 빌드했다. 초기 관련 Java 테스트 154개, 최종 orchestration 집중 테스트 53개가 통과했다. Checkstyle 0건, 모듈 RAT 미승인 0건이다. 최종 UI 집중 테스트 34개와 변경 파일 ESLint가 통과했다. 겹치는 테스트 집합은 합산하지 않는다.

더 넓은 VirtualMachineManager 테스트 100개는 98개 통과·2개 NPE다. 같은 2개 테스트가 깨끗한 Europa 기준 `2871963`에서도 실패했다. 전체 통과로 표시하지 않는다.

앞선 배포 당시 양쪽 관리 JAR은 소스 `1c3c9c41341`의 변경 클래스 82개와 빌드 산출물 해시가 일치한다. 31번 관리 JAR SHA256은 `706ff0f3a853c828dfbab3a46ca7b61ab0b0cda6a07c5a42b98b7599a111be5d`, 32번은 `7c6a65eecbcdd37099eef3c13a69607f6988c215ed6d375cbdd6c8044d420551`다. 두 환경의 Agent 3대씩 배포되었고 설정 및 실행 domain 보존을 확인했다.

앞선 UI 검증 소스 `24e4d7b0074`는 소유자 미선택 차단·상태 태그 대비 개선·템플릿 사전 선택 압축 검사 수정을 포함한다. 생산 빌드와 두 클러스터 배포를 완료했다. archive SHA256은 `430743ed1ebe5f914eb1b09ac976d326f20323d72538624ec4e7ab4c2e06e6f2`이며, 양쪽 정적 파일 840개 해시, WEB-INF/META-INF/config.json 보존, mold active와 `/client/` 200을 확인했다. 배포별 백업과 결과는 `evidence/ui-deployment-final.json`에 있다.

검증 후 기존 31번 VM 74개·32번 VM 13개의 UUID/상태/호스트가 그대로이며 호스트 6대 모두 Up이다. Linux 검증 종료 시 전용 테스트 VM은 31번 11개·32번 12개였으며 이 원본 목록과 따로 기록했다. 동시성 실패의 Error VM과 ISO 슬롯 제한의 Stopped VM도 진단용으로 보존했다. 원본 볼륨은 Ready/미연결, 스냅샷은 BackedUp이다. 최신 읽기 전용 증거는 `evidence/final-linux-runtime-preservation-31.json`, `-32.json`이다. 예전 `runtime-preservation.json`은 배포 전 API 미지원 상태의 기록으로 최종 배포 증거가 아니다.

Windows 8개 검증 후에도 기존 31번 VM 74개·32번 VM 13개의 UUID/상태/호스트가 유지되고 호스트 6대가 Up이다. 전용 테스트 VM은 31번 15개·32번 16개다. Windows seed와 생성된 VM 4개씩 모두 Stopped, 원본 볼륨은 volume-on VM에 Ready/ROOT/device0으로 보존했고 스냅샷은 BackedUp이다. volume-off VM에서는 UI 보존 분리를 수행했다. 최신 증거는 `evidence/windows-final-preservation-31.json`, `-32.json`이다.

## UI 검토와 남은 조건

실제 UI 후보·요약·확인·성공·복구 실패·부분 VM 링크, 한글 상태, 검색 빈 결과, 연결 중 차단을 확인했다. DATA 10000GiB ×2 + ROOT100GiB는 19.63TiB 필요/8.04TiB 추가 할당 가능으로 양쪽 풀에 용량 부족을 표시하고 생성 버튼을 비활성화했다. 요청은 제출하지 않았다.

UI 소스 `957f61f39cf`의 390/1366/1680px 라이트·다크 6개 실제 화면에서 문서 가로 넘침 없이 표 내부 스크롤을 확인했다. 검색 입력의 Tab 이동도 확인했다. 최신 UI에서 소유자 미선택 시 후보·요약 제거와 생성 비활성화, 유효한 계정 복구를 32번에서 확인했다. 31번 기존 프로젝트 선택에서는 admin 원본이 노출되지 않았고 계정 복귀 시 복구되었다. 최신 원본 영역의 표본 텍스트 최소 대비는 라이트 6.88:1, 다크 6.11:1이다. 문서 가로 넘침은 없었으며 모바일 표 내부 스크롤과 Tab 포커스 이동이 유지되었다. 이것은 전체 화면의 접근성 감사 완료를 의미하지 않는다. `deployed-ui-review.json`은 소스 `957f61f39cf`의 6개 화면 기록이다. 최종 `24e4d7b0074`는 템플릿 검사만 바꾸고 해당 화면 구조/스타일은 유지했다. 최종 배포의 32번 다크 데스크톱 원본 선택도 별도 `deployed32-latest-source-spotcheck.json`으로 확인했다. 6개 기존 증거의 소스 버전을 최종 소스로 바꾸어 기록하지 않는다. 로컬 오류 화면 검토는 `local-ui-review.json`이며 실제 생성 PASS와 구분한다.

추가로 32번에서 원본 선택 후 다른 정지된 테스트 VM에 DATA/device1로 연결했다. 실제 생성 버튼의 최종 검사에서 연결 중 및 원본 변경 한글 사유로 거절되었고 새 VM은 0개였다. 이후 UI로 보존 분리했고 원본 Ready/미연결, 대상 VM Stopped, 스냅샷 BackedUp을 확인했다. `deployed32-source-state-change.json`과 runtime 증거를 연결한다.

31번 템플릿 사전 선택 URL의 첫 압축 설정 불일치 거절은 소스 `24e4d7b0074`에서 수정했다. 동일 템플릿을 다시 선택하지 않고 실제 UI로 `E1335-31-template-prefill-r3`를 생성했으며 VM `5dbc658d-f6a3-46d4-9afd-3dd9768a3880`의 최초 Stopped와 ROOT/device0 100GiB를 확인했다. 템플릿의 startvm=false ROOT는 최초 부팅 전 Allocated 상태였으며 생성 검증에 한정한다. 기존 명시적 선택 seed는 UEFI 콘솔 부팅·정지를 통과했지만 이 사전 선택 run의 게스트 부팅 또는 Windows 대표 원본 경로를 통과했다고 표시하지 않는다. `deployed31-template-prefill-regression.json`과 runtime 증거를 연결한다.

양쪽 환경에서 같은 볼륨에 대한 요청 2개를 1ms 이내에 동시에 제출했다. 한 요청만 동일 UUID를 ROOT/device0으로 편입했고 다른 요청은 `SOURCE_ATTACHED`로 거절되었다. 실제 UI에서 승자 Stopped/ROOT 1개, 실패 VM Error/볼륨 0개를 확인하고 UI로 원본을 보존 분리해 Ready/DATADISK/미연결로 복구했다. `runtime31-concurrent-adoption-attempt.json`, `runtime32-concurrent-adoption-attempt.json`과 UI JSON·사진을 연결한다. 동시성 주입은 API로 수행했고 최종 결과/분리는 UI로 검증했다. 다중 부하 스트레스·게스트 부팅 또는 UI만으로 동시 제출한 테스트로 확대 해석하지 않는다.

31번 기존 ISO 경로는 부팅 ISO+추가 드라이버 ISO, ROOT20GiB, ConfigDrive 없는 기존 L2-Test 네트워크로 실제 UI 생성(최초 Stopped)·시작·Rocky Linux 9.8 한국어 설치 화면 부팅·정상 정지를 통과했다. ISO 두 개의 슬롯 3/4와 호스트 XML의 SATA CD-ROM 2개, 최종 Ready ROOT/device0 20GiB를 확인했다. OS 설치는 진행하지 않았다. `deployed31-iso-compatible.json`과 콘솔·정지 사진 및 runtime 증거를 연결한다.

별도의 ConfigDrive 네트워크 ISO run은 두 ISO로 Stopped 생성 후 시작이 슬롯 제한으로 거절되어 Stopped를 유지했다. UI에는 ConfigDrive가 두 번째 슬롯을 사용할 수 있다는 안내가 있었으며 관리 로그는 `Destination cannot accommodate the attached ISOs (cluster limit, host capability or ConfigDrive)`를 기록했다. `runtime31-iso-slot-conflict.json`에는 해당 job의 민감 값 없는 오류 줄만 저장했다. 이 run은 부팅 PASS가 아니며 ConfigDrive와 ISO 2개 조합의 시작 제한/사전 검사 개선 여지는 기존 Epic에 남긴다. 32번은 Ready 부팅 ISO가 없어 ISO 부팅 회귀를 수행하지 않았다.

남은 범위: 진행 중 Agent/management 재시작·응답 유실·중복 제출 방지·재진입, IOPS/스토리지 불가, 실패 후 안전한 재시도와 더 넓은 template 회귀. 일반 사용자·프로젝트 생성, 태그 불일치, 삭제 원본 복구, 32번 ISO, ConfigDrive 사전 검사 완료 증거는 추가 UI 경계 검증 보고서에 있다.

## PR CI

전체 Cloud 빌드는 수동 시작하지 않았다. PR push가 자동 시작한 전체 Build/RPM은 중지했다. 이전 관련 head의 License·Conflict는 통과했지만 Lint 및 UI Build 실패는 집중 모듈·UI 테스트와 별도다. UI CI 9개 실패 중 변경되지 않은 4개 suite의 8개는 깨끗한 기준에서도 재현했고, vmDiskDeployment Array.at는 CI Node14에서 실패·로컬 통과였다. 9개 전부 기준 실패 또는 전체 CI green으로 표시하지 않는다. UI Build `d3c4b441f40` head는 취소, Lint 실패, License/Conflict 통과였다. 앞선 UI 코드 head `24e4d7b0074`의 Actions/PR 체크는 조회 시 빈 목록이었으므로 성공으로 표시하지 않는다. 최종 문서 head 결과도 별도로 조회한다.

- [Epic #1335](https://github.com/ablecloud-team/ablestack-cloud/issues/1335)
- [Epic PR #1344](https://github.com/ablecloud-team/ablestack-cloud/pull/1344)
- [검증 산출물](evidence/implementation-verification.json)
