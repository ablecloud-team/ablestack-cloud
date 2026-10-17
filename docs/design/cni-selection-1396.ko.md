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

# Kubernetes CNI 선택 계약 복구

## 기준과 범위

Epic #1395의 #1396을 구현한다. 최신 upstream/ablestack-europa dc31262567f2afe5f6278fae61c1cef7e307fe3e에서 dhslove/epic-1395-cni 브랜치를 생성했다. API와 서버 저장 형식은 유지하고 UI 선택 계약을 수정한다. 등록 검증·삭제 보호·본문/스타일 개선은 다른 하위 이슈의 범위다.

## 원인과 변경

Kubernetes 생성기는 CNI 목록을 items로 넘기지만 현재 공용 선택기는 scope를 받고 listUserData를 자체 호출한다. 따라서 소유 범위가 비어 선택이 막힌다.

공용 선택기에 userdata/cni 두 종류만 허용하는 resourceKind를 추가한다. 기본 userdata는 기존 계약을 그대로 사용하고 cni는 listCniConfiguration/listcniconfigurationresponse/cniconfig 및 해당 API 권한을 사용한다. 임의 API 이름을 외부에서 주입하지 않는다. CNI의 계정/프로젝트는 ignoreproject와 명시적 소유 파라미터로 조회하며 일반 사용자에게는 로그인 소유 범위를 사용한다.

Kubernetes 생성기는 선택기가 반환한 현재 행을 그대로 소비하여 중복 상세 조회를 제거한다. 소유 범위·Zone·고급 설정 변경과 선택 변경/해제 때 UUID·변수 목록·값을 함께 초기화한다. params가 없거나 응답이 비어 있어도 순회 예외가 발생하지 않는다.

조회 세대와 소유 범위 키로 늦은 응답을 무시한다. 선택한 CNI가 조회 중·실패·없음·권한 상실 상태라면 제출을 막는다. 필수 CNI가 없는 기본 Kubernetes 생성은 계속 사용할 수 있다. 비동기 폼 검증 전후의 소유 범위와 선택 ID도 대조한다.

최종 요청에는 고급 설정에서 선택한 실제 UUID와 현재 선언한 변수의 값만 담는다. 이전 구성의 여분 값과 자동 AS_NUMBER 값은 전송하지 않는다. 검토 요약에도 CNI 이름/설정 안함을 표시한다.

## 검증 계획

- 실제 Kubernetes 소비 컴포넌트와 공용 선택기를 결합해 API·역할·소유 범위, 페이지, params 없는 구성, 변경/해제, 늦은 응답, 실패/재시도, 제출 차단·요청 필터링을 검증한다.
- 기존 UserData 선택·생성·재설정 소비자 테스트와 Kubernetes 생성 테스트를 회귀 검증하고 변경 파일 lint와 UI production build를 수행한다.
- 31번에 정적 UI만 배포한다. WEB-INF/META-INF/config.json과 서비스 PID를 보존하고 디스크 공간·자산 해시·/client/ 200을 확인한다.
- 기본 계정과 전용 프로젝트의 실제 UI에서 CNI 선택을 확인한다. 전용 Kubernetes 클러스터를 UI로 생성하여 API/DB의 CNI UUID/변수와 게스트 적용·부트스트랩 보존·노드 Ready를 확인한다.
- 전용 자원을 정리하고 기존 CNI와 Kubernetes의 UUID/상태를 대조한다. 집중 테스트·정적 UI 빌드/배포를 전체 Cloud/Release CI와 구분한다.
