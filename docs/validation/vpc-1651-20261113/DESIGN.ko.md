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

# #1651 VPC 오퍼링 복수 LB 제공자 보존 설계

기준 소스: Europa `0edd20dcc9218312b3da533582f03e11535c5eb8`.
Epic 브랜치: `dhslove/epic-1650-vpc`.

31 GFS2·32 RBD의 실제 clone API에 각 6개 조건을 적용했다. 원본 상속, 두 제공자 명시, Public 전용, Internal 전용, Redundant 원본의 제공자 상속·명시가 모두 원본/의도한 서비스 및 capability와 일치했다. 각 조건에서 만든 Disabled 오퍼링은 삭제했다. 증적은 `evidence/31-design-contract.json`, `32-design-contract.json` 및 cleanup 및 preservation 파일이다.

API는 CSV 제공자 문자열이 아니라 같은 서비스명을 반복한 `serviceProviderList[n].service/provider` 쌍으로 복수 제공자를 받는다. 제공자 매핑 일부만 전송하면 서버는 전체 매핑으로 해석한다. 따라서 변경 시에는 선택한 모든 서비스·제공자 쌍을 전송한다.

- 원본과 선택 모델은 서비스별 전체 제공자 배열을 각각 보관한다. 순서를 제외한 집합이 같으면 supportedservices/serviceProviderList를 생략하여 원본의 전체 매핑을 상속한다.
- LB는 기존 Ant Select의 복수 선택으로 Public/Internal 제공자를 함께 표시한다. 다른 서비스는 단일 선택을 유지한다. 현재 UI 목록에 없는 원본 서비스·제공자도 모델/옵션에 포함해 조용히 제거하지 않는다. 표현할 수 없는 비LB 복수 제공자는 읽기 전용으로 유지한다.
- 체크 해제 후 재선택은 마지막 선택을 복구한다. 명시적 제공자 변경을 원본 값으로 되돌리던 로직을 제거한다. 제공자를 모두 지운 활성 서비스는 제출 전 검증한다.
- VPC 복제 폼에만 적용한다. 공통 CheckBoxSelectPair, 신규 VPC/네트워크 오퍼링 및 capability helper의 동작은 변경하지 않는다. 기존 Ant 컴포넌트/테마를 사용하며 좁은 화면에서는 서비스명과 제공자 필드를 세로 배치한다.
- #1652 활성화 기본값, #1653 비동기 추적, #1658 전체 화면 스타일 통합은 별도 범위다. 서버/Agent/JAR/호스트를 변경하지 않는다.

Redundant 실증에서는 시험자가 폼의 Redundant 스위치를 명시적으로 선택하여 `SourceNat.RedundantRouter=true`를 전송했다. 현재 listVPCOfferings 응답에는 이 capability가 노출되지 않는다. 스위치를 선택하지 않은 자동 상속 API/UI 대조에서는 기존 서버의 String/HashMap 변환 오류와 기존 UI 성공 오표시를 관찰했다. 이를 Redundant 자동 상속 성공으로 계산하지 않는다. 최종 성공 복제본의 Redundant/Distributed/Region 플래그는 관리 서버의 읽기 전용 DB 조회로 원본과 대조했다. 이번 제공자 수정은 기존 capability helper를 유지하며 이 별도 서버 동작이나 #1653 비동기 처리를 변경하거나 신규 이슈를 생성하지 않는다.

완료 검증: 제공자 모델/실제 폼 payload 회귀, UI lint·production build, 양쪽 관리 서버 정적 자산 배포·보호 파일 해시 대조, 실제 UI의 기본/단일/Redundant 복제·체크 토글·제공자 변경, 복제 VPC의 Public/Internal LB 티어 후보/선택·생성, light/dark 및 360/768/1280px 표시, 생성 자원 정리와 기존 자원 보존 대조.

복수 선택 입력은 서비스별 고유 ID와 네이티브 label을 사용한다. 실제 Ant Form 렌더링 회귀로 그룹 자동 연결에 의한 중복 ID를 발견하여 그룹 autoLink를 끄고 각 입력의 독립 연결을 검증했다. 최종 대화상자는 공통 md(720px) 크기와 MoldDialogActions를 사용하며, 360px에서는 336px 폭과 세로 필드 배치를 적용한다.
