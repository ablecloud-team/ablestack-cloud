<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# VPC-02 / 이슈 #1652 구현 설계

기준은 Epic 브랜치 dhslove/epic-1650-vpc의 `6ef9ac9a5c1aa59098af9923950fc47c60b7de27`이다. 31(GFS2)·32(RBD)에 배포된 #1651의 제공자 보존을 유지하며 복제 기본값·상속 계약만 수정한다.

## 실환경 실증

양쪽에서 Enabled/Disabled 시험 원본을 만들고 enable 생략/true/false의 6조건씩을 대조했다. 생략은 원본 상태를 상속하고 true/false는 명시 상태로 생성됐다. conserve mode=true 원본의 복제에서 conservemode를 생략하면 false가 됐으며, true/false를 명시하면 해당 값을 보존했다. 각 결과의 domain/Zone/라우팅은 원본과 같았다. 결과 오퍼링은 정리하고 UI 회귀용 시험 원본 2개씩만 유지했다.

수정 전 실제 UI에서 Enabled 원본의 스위치가 OFF인 채 제출됐고, 요청은 enable을 생략했으며 결과는 Enabled였다. 네트워크 모드를 선택하면 편집한 이름이 원본 이름으로 되돌아갔다. 초기화가 후보 조회 완료 때마다 재실행되는 경로를 분리해야 한다.

## 필드별 정책

| 속성 | 초기값·전송 | 변경 지원·범위 |
|---|---|---|
| 활성화 | Enabled=true / Disabled=false. 매 제출에 boolean 명시 | 기존 스위치로 ON/OFF. 미상 상태는 원본 유지 |
| 서비스·제공자 | #1651의 전체 배열/변경 감지 유지 | 미변경은 생략, 변경 시 전체 쌍 전송 |
| conserve mode | 응답의 원본 boolean을 읽기 전용 요약하고 명시 전송 | 이번 폼에서 변경하지 않음. 미제공 시 값 추측 금지 |
| 인터넷 프로토콜·네트워크 모드·라우팅 | 응답 값으로 초기화, 라우팅 소문자 정규화. 조건에 맞는 노출 필드만 전송 | 기존 조건부 편집 유지. 모드 갱신은 이름/활성화/범위를 초기화하지 않음 |
| Redundant/Region/Distributed | capability 또는 응답의 명시 flag. 알 수 없으면 원본 유지 | 원본 유지/활성화/비활성화 선택. 명시 false도 전송. 관련 서비스가 없으면 생략 |
| 컴퓨트 오퍼링 | 응답에 있으면 선택, 없으면 원본 유지 표시 | 선택한 값은 VR 제공자가 있을 때 전달. 응답에 없는 원본 ID를 추측하지 않음 |
| 도메인·Zone | UUID로 초기화. 옵션 배열 인덱스를 요청 UUID로 오인하지 않음 | 기존 제한 범위의 전체 해제는 서버가 생략값을 상속하므로 제공하지 않음. 범위 지정은 유지 |
| AS Number | 응답 boolean. 조건부 편집이 보이는 경우 false 포함 전송 | 숨겨진 분기의 값은 전송하지 않음 |

원본 변경은 폼을 새로 초기화하고 늦게 도착한 이전 서비스 조회를 무시한다. 단순 후보 갱신은 선택/편집값을 유지한다. ROUTED 전환 때 무관한 NAT 서비스와 해당 capability 요청을 제거한다. HTTP 제출 실패 후 동일 값으로 재시도하며, 비동기 job 실패 추적은 #1653 범위로 남긴다.

## 범위와 검증

공통 Add/네트워크 오퍼링 capability helper와 백엔드는 변경하지 않는다. Clone 전용 helper에서 false 보존을 처리한다. 현재 API가 Redundant/컴퓨트 ID를 조회 응답에 주지 않는 조건과 기존 서버의 capability 자동 상속 오류는 별도로 명시하며, 자동 상속 성공으로 발표하지 않는다. 값 미노출을 임의 OFF로 표시하지 않는 것이 이번 UI 계약이다. 명시 capability true/false는 읽기 전용 DB 플래그와 대조한다.

기존 VM 표준 MoldDialog md·고정 하단·본문 스크롤·테마를 유지한다. 양쪽 Enabled/Disabled 원본의 미조작/ON/OFF/ON→OFF, 서비스·모드 전환, 재열기/원본 교체/HTTP 재시도, Disabled 복제본의 신규 VPC 후보 제외, 360/768/1280 light/dark를 집중 검증한다. 기존 LB VM/트래픽 시험이나 전체 Cloud 빌드는 반복하지 않는다. 시험 자원과 기본 보기 주요 자원의 UUID/필드를 대조해 정리·보존을 확인한다. 별도 이슈를 생성하지 않는다.

서버는 capability 배열 전체를 대체한다. 일부 명시값과 다른 기능의 원본 유지를 혼합하면 조회되지 않은 원본 기능이 손실될 수 있어 제출을 차단한다. 변경할 때는 적용 가능한 capability를 모두 명시하거나 모두 원본 유지로 선택한다. 이 가드는 기존 서버 계약에 맞춘 Clone 폼 검증이다.

## 전송 실패 실증에 따른 보완

첫 배포 소스 21da0d5에서 탭 전체 오프라인 시험은 공통 API 전송 오류 처리로 인증 상태가 초기화되어 재시도를 완료하지 못했다. 해당 시도는 성공으로 계산하지 않는다. #1652의 폼 재시도 기준을 충족하기 위해 Clone 요청 한 곳에 기존 postAPI 옵션 preserveOnFailure=true를 사용한다. 공통 API/인증 코드는 수정하지 않으며 실제 401 정책과 비동기 job 처리는 그대로 둔다. 후속 소스 5e1d223을 다시 빌드·배포한 뒤 복제 XHR 한 건에 전송 실패를 주입하여 입력·세션 유지와 동일 값 재시도를 검증한다.
