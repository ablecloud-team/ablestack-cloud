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

# #1090 TPM/vTPM 통합 구현 및 13번 검증

## 변경

- 기준: `ablestack-europa` `39f798757d5109e6717386680a75cb497b20a91b`.
- legacy `tpmversion`과 `virtual.tpm.model/version`을 공통 resolver로 통합했다. 명시하지 않은 NONE을 API가 자동 삽입하지 않으며, 명시적 NONE에는 TPM 호스트 필터를 적용하지 않는다.
- ISO/템플릿 및 볼륨 생성 API의 legacy 입력을 정규화한다. 템플릿 상속 우선순위를 보존하며 충돌과 CRB+1.2를 거부한다.
- 에이전트는 swtpm 실행과 libvirt domcapabilities를 확인하여 enabled/model/version을 ReadyAnswer로 보고한다. 미지원/미보고는 이전 true를 유지하지 않는다.
- 실제 환경에서 `swtpm_setup 0.8.0 --version`은 정상 버전을 출력하면서 exit 1을 반환했다. `--print-capabilities`로 실행을 검증하고, 기존 Script 출력 처리의 Stream closed 문제를 피하도록 시간 제한이 있는 ProcessBuilder 실행을 사용한다.
- 생성 UI: 템플릿 설정 상속 / 비활성 / 1.2 / 2.0. 1.2는 TIS만, 2.0은 TIS와 CRB를 선택한다. 버전 변경 시 모델을 TIS로 초기화하고 비활성 상태에서는 canonical model/version을 전송하지 않는다.
- 기존 VM의 구·신 TPM 설정을 한 행으로 표시한다. 기존 TPM의 일반 상세 키 변경/삭제는 상태 보존 작업 없이 허용하지 않는다.
- KVM XML에 TPM 0개 또는 1개만 생성한다. transient domain의 TPM 상태 보존을 위해 `persistent_state=yes`를 사용한다. 속성 의미는 [libvirt TPM 문서](https://libvirt.org/formatdomain.html#tpm-device)를 참고한다.

## 빌드 및 자동 검증

소스·의존성·빌드·테스트는 Rocky Linux 9.8 amd64 Docker 안에서 수행했다.

| 범위 | 결과 |
|---|---|
| API resolver / deploy API | 9개 통과 |
| UserVmManagerImpl / FirstFitAllocator / KVMGuru | 313개 통과 |
| LibvirtComputingResource / LibvirtVMDef / TPM capability / migration guard | 359개 통과 |
| 기존 UI 전체 회귀 | 48 suites, 482개 통과 |
| 추가 TPM UI 요청 테스트 | 6개 통과 |
| 변경 UI ESLint / git diff --check | 통과 |
| UI production build | 통과, 소스맵 제외 |
| API, server, engine/orchestration, KVM 및 필요한 의존 모듈 빌드 | 통과 |

중간 실패는 수정 후 해당 범위를 다시 검증했다. UI 전체 482개와 신규 TPM 6개는 별도 실행 결과다. KVM 전체 테스트의 일시 정지 실행은 완료 결과로 계산하지 않았으며, 최종 359개 실행은 실패·오류·스킵 0이다.

## 배포

- 13번 관리 서버와 세 KVM 에이전트의 변경 모듈만 교체했다. 관리 서버는 기존 fat JAR 구조여서 빌드한 API/server/orchestration/KVM class를 기존 배포 bundle에 반영하고 ZIP 무결성을 검사했다.
- 배포용 worktree에는 기존 13번의 #1086 다중 ISO 및 #1083/#1089 UI 변경을 보존했다. PR 자체는 기준 Europa에 대한 #1090 변경만 포함한다.
- 세 에이전트 재시작 전후 기존 실행 VM UUID 목록이 동일했다. UI 배포는 config.json과 WEB-INF 체크섬을 보존하고 새 entry point를 마지막에 교체했다.
- 최종 관리 서버 JAR 백업: `/var/tmp/issue1090-20260915-063030/before`.
- 최종 에이전트 JAR 백업: .1 `062635`, .2 `062638`, .3 `062640` (`/var/tmp/issue1090-20260915-<시각>/before`).
- 최종 UI 백업: `/var/tmp/issue1090-20260915-062827/ui-before.tar.gz`.

## 실제 확인

세 호스트의 등록 값은 모두 `host.tpm.enable=true`, models=`tpm-tis,tpm-crb`, versions=`2.0`이다. UI에서 1.2를 제공하더라도 이 호스트에 1.2를 배치할 수 있다는 의미는 아니다.

호스트를 지정하지 않고 Windows 설치 ISO + VirtIO ISO로 테스트했다.

| VM | UUID | 결과 |
|---|---|---|
| issue1090-none (156) | 314ac3d3-d7a1-4119-abbf-9f9c0c4be039 | ablecube2 자동 배치, Running, TPM XML 0개 |
| issue1090-crb (158) | 56854a96-b97d-41bc-92c8-2d05075fcc16 | ablecube3 자동 배치, Running, CRB 2.0 XML 1개 |
| issue1090-tis (160) | 8f690b8c-8aa2-42ae-9aca-b1fa003404da | ablecube3 자동 배치, Running, TIS 2.0 XML 1개 |

- NONE/CRB VM에 설치 ISO와 VirtIO ISO의 서로 다른 CD-ROM 장치가 연결된 것을 확인했다.
- 기존 TPM의 model 변경 및 NONE 변경 요청, 신규 요청의 NONE+활성 충돌·CRB+1.2·알 수 없는 모델이 API에서 거부되는 것을 확인했다.
- CRB VM을 강제 중지 후 호스트 미지정으로 다시 시작하여 같은 ablecube3에서 Running으로 복귀했다. 중지 상태에서도 UUID별 TPM state 파일이 남았으며 재시작 후 같은 경로를 사용하고 swtpm manufacturing 로그가 추가되지 않은 것을 확인했다. Windows 게스트 내부 키 동일성 검증은 별도다.
- CRB VM의 중지 후 다른 호스트(ablecube2)를 명시한 시작은 거부되었고 기존 호스트(ablecube3)를 명시한 시작은 성공했다.
- 최초 일반 중지 검증은 관리 서버 재시작과 겹쳐 완료되지 않았으므로 성공으로 계산하지 않았다. 설치 미디어 상태의 테스트 VM에 대한 강제 중지/시작으로 재검증했다.
- 브라우저에서 1.2/TIS, 2.0/CRB, CRB→1.2/TIS 초기화, 비활성 시 모델 숨김을 확인했다. 다크 테마에서 새 selector의 배경·텍스트 가독성을 시각적으로 확인했다.
- 초기 capability 탐지 실패 시 생성된 157번 테스트 VM은 usable volume 없이 남아 있어 재시작 검증에 사용하지 않았다. 사용자의 기존 154번 VM은 변경하지 않았다.

검증 종료 시 위 3개 테스트 VM은 모두 Stopped로 정리했다. 관리 서버 service active / HTTP 200 및 세 호스트 Up/Enabled를 확인했다.

## 지원 범위와 후속 검증

TPM 상태는 현재 호스트 로컬이다. TPM VM은 기존 호스트에서 재시작하도록 제한하고 호스트 간 migration을 차단한다. 상태를 이동하지 않은 채 다른 호스트에서 새 TPM identity를 만드는 동작을 방지하기 위한 제한이다.

Windows 설치 완료 후 Get-Tpm/BitLocker, 호스트 간 상태 이동·HA·백업 복원·복제·영구 삭제 정리 및 기존 legacy-only VM audit는 [#1091](https://github.com/ablecloud-team/ablestack-cloud/issues/1091)에서 추적한다. XML/실행 프로세스 확인을 Windows 게스트 내 TPM 인증 완료로 간주하지 않는다. 볼륨/스냅샷을 포함한 모든 생성 유형의 실물 검증을 수행했다는 의미도 아니다.
