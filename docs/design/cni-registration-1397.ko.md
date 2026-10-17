<!-- Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. -->

# CNI 등록 계약 정합성 (#1397)

Epic #1395의 두 번째 구현 단위다. 등록 API와 공용 등록 대화상자의 입력 계약을 맞추며 삭제, 사용처, 목록/상세 디자인은 별도 하위 이슈로 유지한다.

## 입력과 호환성

- 이름: Unicode 공백과 BOM을 양끝에서 제거한 뒤 1~255 UTF-16 코드 단위. 일반 사용자 데이터와 CNI가 기존 소유자별 이름 공간을 공유한다.
- 본문: API는 Base64를 받는다. UI 일반 텍스트는 명시적으로 UTF-8 인코딩하며 Base64처럼 보이는 텍스트도 자동 해석하지 않는다. Base64 모드에서는 선택적 패딩, URL-safe 문자, URI 인코딩, ASCII 줄바꿈을 정규화한다.
- 빈 본문, 디코딩 후 Unicode 공백/BOM만 남는 본문은 거부한다. 임의 텍스트와 비어 있지 않은 바이너리는 계속 허용한다. YAML 또는 cloud-config 전용 제약을 추가하지 않는다. 본문 바이트와 개행을 보존한다.
- 정규화된 Base64 길이가 `vm.userdata.max.length` 및 HTTP 메서드 상한(GET 4 KiB, POST 1 MiB) 이하여야 한다. 패딩도 길이에 포함한다. UI는 `listCapabilities.userdatamaxlength`를 읽고 표시한다. 조회 실패 시 서버가 현재 설정값을 최종 판정한다.
- 변수: 양끝 공백 제거 후 `[A-Za-z_][A-Za-z0-9_.-]*`, 대소문자를 구별하는 중복 금지. `NetworkModel.metadataFileNames` 12개 예약어를 서버/UI에서 거부한다. CNI 자동 치환 변수 `AS_NUMBER`는 기존 호환성을 위해 허용한다.
- 동일 본문의 다른 이름 등록은 허용한다. 일반 사용자 데이터의 본문 중복 정책은 변경하지 않는다.

## 저장과 응답

소유 계정·도메인을 확정한 뒤 일반 사용자 데이터와 동일한 소유자별 GlobalLock 안에서 정규화 이름 중복 조회와 저장을 실행한다. 잠금 실패와 저장 예외에서도 참조와 잠금을 정리한다. 등록 결과는 기존처럼 `registercniconfigurationresponse.cniconfig`의 `UserDataResponse`이며 API discovery의 응답 타입과 필수 본문 정의를 실제 동작에 맞춘다.

## UI 상태

등록 검증 시작부터 POST 완료까지 동기 잠금으로 중복 제출과 대화상자 닫기를 막는다. API 오류는 인라인으로 표시하며 이름, 본문, 변수, 인코딩, 소유 범위, 포커스와 스크롤을 보존한다. 프로젝트에서 등록하면 명시적 `projectid`를 보내고 계정/도메인 입력을 숨긴다. 모든 프로젝트 보기는 등록을 차단한다. 검증 중 소유 범위가 바뀌면 POST를 보내지 않는다. 요청 이후 범위가 바뀌거나 성공 응답이 불완전하면 대화상자를 닫지 않고 원래 범위의 목록을 확인하도록 안내한다.

## 검증과 배포

변경된 API/server Maven 모듈만 WSL ext4 작업 트리에서 빌드한다. 공용 사용자 데이터 등록/복제 회귀와 CNI 단위 테스트, UI lint/build를 실행한다. 클러스터 31에서 실제 API 경계값·응답 discovery·계정/프로젝트 소유권과 실제 UI 등록/오류 복구를 검증한다. 기존 CNI/Kubernetes/소유자 데이터 해시를 전후 비교하고 전용 테스트 자원은 정리한다. 전체 Cloud 빌드나 전체 CI 성공으로 보고하지 않는다.
