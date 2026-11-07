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

# 검토용으로 유지한 #1564 자원
새 자원은 별도 삭제 지시까지 유지한다. 기존 #1565 자원은 정리했다. 아래 QA 계정의 비밀번호는 게시하지 않는다.

## 31번 GFS2

| 이름 | 유형 / 범위 | 사용량 제외 | VLAN | 직접 열기 |
|---|---|---|---|---|
| qa-gn1564-31-shared-false | Shared / Account | false | 3600 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/00cb5c3a-198a-449f-915d-e3b9d6212f66) |
| qa-gn1564-31-shared-true | Shared / Account | true | 3601 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/8fbf5ecf-6b69-4b19-aad2-a64fdbcd80a2) |
| qa-gn1564-31-shared-domain | Shared / Domain | true | 3602 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/dd811939-2194-4563-9296-3bed06ebe85d) |
| qa-gn1564-31-shared-all | Shared / Domain | false | 3603 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/7414ae66-bdd6-40d1-b550-cb788c37661c) |
| qa-gn1564-31-shared-project | Shared / project | false | 3604 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/264eecfb-fd96-452d-950b-5b39356c626e) |
| qa-gn1564-31-l2 | L2 / Account | 비적용 | 3605 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/6c6223b6-8d8e-4fd3-b6e2-bab27272abf1) |
| qa-gn1564-31-isolated | Isolated / Account | 비적용 | 자동 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/2586eb7b-5633-43bf-942c-a7bfdadd6f7c) |

VM: [qa-gn1564-31-usage-review](http://10.10.31.10:8080/client/#/vm/c88978be-7e03-4451-ae80-c932ab4022fb) (`c88978be-7e03-4451-ae80-c932ab4022fb`), Running, i-2-865-VM, ablecube31-3. L2 기본 NIC와 Shared `10.46.31.18` 보조 NIC를 유지한다.

자동 라우터: r-866-VM / Running / 4724188b-9e85-4467-bf0b-ad8c96d84390.
도메인 `d5e03c07-6865-42eb-a8cb-92bb9c316270`, 프로젝트 `3042d636-88c0-4f41-8c1b-e8b4a05b18ed`. QA User/DomainAdmin: qa-gn1564-31-domainadmin, qa-gn1564-31-user.

[UUID·NIC·볼륨·부속 자원 JSON](31-retained.json)

## 32번 Glue(Ceph) RBD

| 이름 | 유형 / 범위 | 사용량 제외 | VLAN | 직접 열기 |
|---|---|---|---|---|
| qa-gn1564-32-shared-false | Shared / Account | false | 3600 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/3f5c2ba2-a9a7-44cd-a2de-e7a3fd326f58) |
| qa-gn1564-32-shared-true | Shared / Account | true | 3601 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/18e421b7-1c53-4744-9088-4e3a02a8da6b) |
| qa-gn1564-32-shared-domain | Shared / Domain | true | 3602 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/5f48ef29-5b0f-4c77-bb32-10db998bd85b) |
| qa-gn1564-32-shared-all | Shared / Domain | false | 3603 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/cf2a3736-472e-4cb5-877d-d6dc61cf2619) |
| qa-gn1564-32-shared-project | Shared / project | false | 3604 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/7e9cd42c-00c0-4696-81f5-f796e3cd1286) |
| qa-gn1564-32-l2 | L2 / Account | 비적용 | 3605 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/701c6021-4bd0-4953-b936-3dbb52bff02e) |
| qa-gn1564-32-isolated | Isolated / Account | 비적용 | 자동 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/abcd944a-dcf8-4d7d-86e6-f15504518329) |

VM: [qa-gn1564-32-usage-review](http://10.10.32.10:8080/client/#/vm/006b2ece-6313-44e6-a248-db517aa69a7d) (`006b2ece-6313-44e6-a248-db517aa69a7d`), Running, i-2-355-VM, ablecube32-1. L2 기본 NIC와 Shared `10.46.32.18` 보조 NIC를 유지한다.

자동 라우터: r-356-VM / Running / a4e6b79f-ed19-4d7d-8562-215e4907edc0.
도메인 `14308a02-a543-4ebc-b788-c1c6647aecd9`, 프로젝트 `f1813810-b851-46ce-b9ab-9e6cba6f5062`. QA User/DomainAdmin: qa-gn1564-32-domainadmin, qa-gn1564-32-user.

[UUID·NIC·볼륨·부속 자원 JSON](32-retained.json)

## 확인 경로

Root Admin 로그인 → 네트워크 > 가상머신용 네트워크에서 `qa-gn1564-31` / `qa-gn1564-32` 검색 → Shared 상세 > 사용량 값 확인 → 작업 > 네트워크 업데이트.

`shared-false`는 현재 포함, `shared-true`와 `shared-domain`은 제외다. all/project는 키 부재 기본 포함이다. 제외를 켜도 IP/VM 표시나 접근 권한을 바꾸지 않는다. 현재 붙어 있는 NIC의 과거 할당은 토글만으로 소급 변경되지 않는다. 프로젝트 망은 상단 프로젝트 선택 후 확인한다.

정리 시 이번 UUID에 속한 VM/NIC/라우터 의존성을 먼저 확인하고 VM·네트워크·프로젝트·계정·도메인 순으로 공식 API를 사용한다. 현재는 삭제하지 않는다.
