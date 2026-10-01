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

# C6 프로세스 탭 — 구현 전 UI 목업 v4

검토 이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1176

**목업 전용 산출물입니다. 실제 Vue/API 구현, 배포, VM 변경은 하지 않았습니다.**
모든 VM 이름, PID, 프로세스 및 시각은 예시 데이터입니다. 이미지는 VM 상세의 우측 탭 영역을 확대하여 보여 줍니다. 기존 VM 요약 영역/전역 내비게이션을 교체하는 제안이 아닙니다.

## 기준 템플릿

소스 기준 Cloud C5 `6540fc3d9ee0840b15eef4e6559675b24b5feee7`에서 목업 전용 브랜치로 누적 분기했습니다. 기존 미병합 PR은 병합하지 않았습니다.

- `ui/src/views/compute/InstanceTab.vue`: 좌측 세로 탭 구조.
- `ui/src/views/compute/VmSnapshotsTab.vue`: 작업 버튼 → 새로고침 → 우측 검색, small table, 행 작업 메뉴, 우측 하단 pagination, bordered descriptions 확인창.
- `ui/src/views/compute/VmVolumesTab.vue`: 가운데 대화상자, 대상 설명 표, 경고, 고정 하단의 취소/실행 버튼, 작업 결과 확인 패턴.
- `ui/src/views/network/NicsTab.vue`: 작업별 비활성 사유, 삭제/변경 구분, 수직 폼과 대화상자 흐름.
- `ui/src/style/theme/tokens.less`: light/dark surface·text·border·상태 의미 색상.
- 테스트 관리 UI의 현재 VM 상세/스냅샷 탭도 읽기 전용으로 확인했습니다.

맨 앞 파란 버튼은 선택한 대상의 핵심 작업입니다. 연결 서비스가 있으면 서비스 재시작, 일반 프로세스와 Windows 예시에서는 강제 종료를 배치했습니다. 단일 행 선택과 선택 대상 표시를 추가했으며 새로고침/준비 상태 확인은 일반 보조 버튼입니다. 강제 종료는 별도 확인창과 기본 해제 동의를 거칩니다. 탭 순서는 상세 → IP 구성 → 메트릭 → 프로세스이며 나머지는 기존 순서를 유지합니다. 일괄 종료나 임의 명령 실행은 제안하지 않습니다.

## 다크모드 가독성

- 기존 의미 색상 토큰을 사용해 표 헤더, 행, hover, 입력, 메뉴와 모달의 배경을 분리했습니다.
- 비활성 사유는 opacity로 흐리게 만들지 않고 읽을 수 있는 secondary/muted 색상을 사용합니다.
- 경고/오류는 색상 외에 아이콘과 문구로 구별합니다. 보호 대상/미지원/만료/UNKNOWN 사유를 명시합니다.
- 주 버튼과 위험 버튼은 흰 글자 대비를 확보한 파랑/빨강을 사용합니다. 강제 종료 동의는 **기본 해제**, 동의 전 실행 버튼은 비활성입니다.
- 기본/보조/보충/링크 텍스트, 비활성 사유, 4종 상태 안내, 주/위험 버튼의 30개 색상 조합을 계산했습니다. 모두 4.5:1 이상, 최소 4.97:1입니다. `contrast-check.json` 참조. 이는 목업의 선택된 색상 조합 검증이며 완성 제품 전체 접근성 인증은 아닙니다.

## 동작 설계 메모

- Global `vm.process.management.enabled=false`에서는 VM 내부 목록과 변경 요청을 보내지 않습니다. Global 설정 진입은 관리자만 표시하며 일반 사용자는 관리자에게 문의 안내로 대체합니다.
- Tools 설치 후 RPC 8개와 도구 상태를 재확인합니다. ISO 연결/게스트 설치/재검증을 분리하여 설치만으로 준비 완료를 표시하지 않습니다.
- 목록은 관측 시각과 경과 시간을 보여 줍니다. 10초를 넘으면 변경 버튼을 차단합니다. 확인창에서 만료되어도 제출은 차단하고 새 목록에서 대상을 다시 선택하도록 합니다. 자동으로 바뀐 PID에 실행하지 않습니다.
- 일반 프로세스 자체의 재시작은 지원하지 않으며 연결 서비스만 재시작합니다. 서비스가 여러 개이면 확인창에서 명시적으로 하나를 선택하는 후속 상세 설계가 필요합니다.
- Windows 정상 종료 미지원은 비활성 사유로 표시합니다. 권한 없는 작업은 숨기고, OS/보호 대상 등의 제한은 사유와 함께 비활성화합니다.
- 실행 중에는 해당 VM의 변경 작업을 잠그고 완료 후 목록 영역만 새로고침합니다. UNKNOWN은 실패로 단정하지 않고 ‘작업 결과 확인’만 제공합니다. 종료/재시작 재전송이나 임의 잠금 해제 버튼은 없습니다.
- 이미지의 표는 예시 행만 발췌했습니다. 검색·정렬·페이지 수는 실제 API 응답으로 구현할 예정이며 지금은 정적 표시입니다.

## 검토 이미지

### 01. 프로세스 목록과 작업 메뉴

라이트

![프로세스 목록과 작업 메뉴 라이트](images/01-list-light.jpg)

다크

![프로세스 목록과 작업 메뉴 다크](images/01-list-dark.jpg)

### 02. 정상 종료 확인

라이트

![정상 종료 확인 라이트](images/02-terminate-light.jpg)

다크

![정상 종료 확인 다크](images/02-terminate-dark.jpg)

### 03. 강제 종료 확인

라이트

![강제 종료 확인 라이트](images/03-kill-light.jpg)

다크

![강제 종료 확인 다크](images/03-kill-dark.jpg)

### 04. 서비스 재시작 확인

라이트

![서비스 재시작 확인 라이트](images/04-restart-light.jpg)

다크

![서비스 재시작 확인 다크](images/04-restart-dark.jpg)

### 05. Tools 설치 안내와 RPC 확인

라이트

![Tools 설치 안내와 RPC 확인 라이트](images/05-tools-light.jpg)

다크

![Tools 설치 안내와 RPC 확인 다크](images/05-tools-dark.jpg)

### 06. Global 비활성

라이트

![Global 비활성 라이트](images/06-disabled-light.jpg)

다크

![Global 비활성 다크](images/06-disabled-dark.jpg)

### 07. 결과 미확정과 읽기 전용 조회

라이트

![결과 미확정과 읽기 전용 조회 라이트](images/07-unknown-light.jpg)

다크

![결과 미확정과 읽기 전용 조회 다크](images/07-unknown-dark.jpg)

### 08. 오래된 목록과 변경 차단

라이트

![오래된 목록과 변경 차단 라이트](images/08-stale-light.jpg)

다크

![오래된 목록과 변경 차단 다크](images/08-stale-dark.jpg)

### 09. Windows 미지원 작업 안내

라이트

![Windows 미지원 작업 안내 라이트](images/09-windows-light.jpg)

다크

![Windows 미지원 작업 안내 다크](images/09-windows-dark.jpg)

## 재현

이 디렉터리를 정적 HTTP 서버로 열고 `index.html?state=list&theme=dark`로 접속합니다.
`state`는 list/terminate/kill/restart/tools/disabled/unknown/stale/windows/cpu-sort,
`theme`은 light/dark입니다. 하단 링크는 목업 화면 전환용이며 작업 버튼은 실제 동작하지 않습니다.
`&capture=1`은 화면 전환용 링크를 숨깁니다.

검토 이후 #1176 구현에서 실제 Ant Design 컴포넌트와 번역/API를 연결합니다. 이번 산출물만으로 #1176 완료 처리하지 않습니다.

## v2 변경

사용자 검토 반영: 선두 파란 버튼을 핵심 변경 액션으로 전환하고 프로세스 탭을 네 번째로 이동했습니다. 준비 미완료/Global 비활성/UNKNOWN/만료 상태에서는 핵심 액션을 차단하며 보조 확인 기능을 파란 주 버튼으로 승격하지 않습니다.

## v4 — CPU 사용량·정렬 검토 반영

메모리 앞에 CPU 사용량 열을 배치했습니다. 소수점 한 자리 %와 오른쪽 정렬을 사용하고 미측정은 ‘—’, 실제 0은 ‘0.0%’로 구분합니다. 한 코어 100% 기준이므로 여러 코어 사용 시 100%를 초과할 수 있습니다. CPU 열의 정렬 동작을 추가했으며 높은 순으로 볼 때 미측정 값은 마지막에, 같은 값은 PID 순서로 표시합니다.

라이트·다크의 기존 18개 화면을 갱신하고 CPU 높은 순 정렬 화면 2개를 추가했습니다. 이미지는 표시 형식 검토용 예시 데이터이며 실제 API 호출이나 VM 변경을 수행하지 않습니다.

백엔드 선행 작업: [qemu #75](https://github.com/ablecloud-team/ablestack-qemu-exec-tools/issues/75), [Cloud #1183](https://github.com/ablecloud-team/ablestack-cloud/issues/1183).

### 10. CPU 높은 순 정렬

라이트

![CPU 높은 순 정렬 라이트](images/10-cpu-sort-light.jpg)

다크

![CPU 높은 순 정렬 다크](images/10-cpu-sort-dark.jpg)
