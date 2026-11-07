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

# #1566 최종 검토 자원

2026-11-05. 아래 자원은 별도 삭제 지시까지 유지한다. 이전 #1567 자원은 정리했다.

## 확인 순서

1. Mold 로그인 후 서비스 오퍼링 > 네트워크 오퍼링에서 `qa-gn1566-` 검색.
2. seed 상세 > 작업 > **LB 연결 설정**: 연결 수513·Keepalive 활성화, 적용 시점 안내 확인.
3. 작업 > 네트워크 오퍼링 복제: 원본 값 미리 채움 확인 후 취소. ui-clone은513/true, ui-create는321/false로 저장돼 있다.
4. 목록의 네트워크 오퍼링 추가에서 Lb/VirtualRouter 선택 시 LB 필드 표시, L2/Shared 또는 LB 서비스 없음으로 전환 시 제외 안내를 확인하고 취소.
5. seed 상세의 사용 네트워크 탭 또는 아래 링크로 전용 망·Public IP LB 탭 확인. LB 규칙80→8080, HTTP, Round-robin, Active, 연결 VM1대다.

오퍼링 저장만으로 실행 중인 라우터가 즉시 변경되지 않는다. 이 검토 중 큰 연결 수를 실제 망에 적용하지 않는다. 테마는 프로필 > 페이지 스타일 설정에서 즉시 바꿀 수 있다.

## 31번 — GFS2

- [네트워크 qa-gn1566-31-lb](http://10.10.31.10:8080/client/#/guestnetwork/4b9726af-b1cc-4d22-89da-b700e57d14e4), 10.76.31.0/24, Implemented.
- [오퍼링 qa-gn1566-31-seed](http://10.10.31.10:8080/client/#/networkoffering/d5f8bded-2ead-4d13-addc-17fe7ae8c7d7).
- [Public IP 10.10.31.142 / LB 탭](http://10.10.31.10:8080/client/#/publicip/25328461-bb0a-4b4e-8852-258ad4227d7f?tab=loadbalancing).
- VM `qa-gn1566-31-backend` (`5b4ac993-a565-4f8e-b4b4-0c4d10975877`), 라우터 `r-876-VM`: 둘 다 Running, 같은 host `3fb147f3-aea4-4f65-b8c6-e7192ea26138`. VLAN 스위치가 구성되지 않은 조건에서 같은 호스트 배치를 사용했다.
- LB `qa-gn1566-31-http` (`b31d0c16-10d2-4d63-851f-a94a27a46a00`), 전용 방화벽 TCP80·10.10.0.0/16. 분배 성능·다중 백엔드 분산은 시험 대상이 아니다.

| 오퍼링 | UUID | 연결 수 | Keepalive | 상태 |
|---|---|---:|---|---|
| qa-gn1566-31-api-contract | eaf0ae4c-7d96-44bb-9da8-2a51a807d055 | 321 | true | Enabled |
| qa-gn1566-31-api-create-verified | b6550d2e-bf19-4cc0-80b7-beb3a062beb9 | 321 | true | Enabled |
| qa-gn1566-31-seed | d5f8bded-2ead-4d13-addc-17fe7ae8c7d7 | 513 | true | Enabled |
| qa-gn1566-31-ui-clone | ccb87110-6ac8-46cc-80c4-c7ddf935f480 | 513 | true | Disabled |
| qa-gn1566-31-ui-create | 916d8258-0911-4b78-b588-f0d6c8205362 | 321 | false | Disabled |

## 32번 — Glue(Ceph) RBD

- [네트워크 qa-gn1566-32-lb](http://10.10.32.10:8080/client/#/guestnetwork/9bf0afda-a536-4356-a4d9-27cfdaffc916), 10.76.32.0/24, Implemented.
- [오퍼링 qa-gn1566-32-seed](http://10.10.32.10:8080/client/#/networkoffering/b567eab0-6a63-455d-9b00-279d5e122d33).
- [Public IP 10.10.32.166 / LB 탭](http://10.10.32.10:8080/client/#/publicip/04de4618-4265-4bfc-9b4c-95bc88ac1c2c?tab=loadbalancing).
- VM `qa-gn1566-32-backend` (`4e17f7fc-b2a5-431a-af42-a5cee88734c8`), 라우터 `r-364-VM`: 둘 다 Running, 같은 host `05bc1189-abf8-4ec3-9ef5-02558863b628`. VLAN 스위치가 구성되지 않은 조건에서 같은 호스트 배치를 사용했다.
- LB `qa-gn1566-32-http` (`1d59766c-2002-4f4b-81ff-8e44b6f4ef2e`), 전용 방화벽 TCP80·10.10.0.0/16. 분배 성능·다중 백엔드 분산은 시험 대상이 아니다.

| 오퍼링 | UUID | 연결 수 | Keepalive | 상태 |
|---|---|---:|---|---|
| qa-gn1566-32-api-contract | 59b8b401-d7e8-4ca4-ac13-afa33a2e2229 | 321 | true | Enabled |
| qa-gn1566-32-api-create-verified | 3712e69e-0234-44af-9feb-44c48bae94ab | 321 | true | Enabled |
| qa-gn1566-32-seed | b567eab0-6a63-455d-9b00-279d5e122d33 | 513 | true | Enabled |
| qa-gn1566-32-ui-clone | e7b92926-2ac9-4d41-9c2d-e84a33e0339f | 513 | true | Disabled |
| qa-gn1566-32-ui-create | 23e262cc-9763-4ac7-b74a-f546f12ad9fa | 321 | false | Disabled |

총 2망·사용자 VM2대·라우터2대·LB2개·방화벽 규칙2개·전용 오퍼링10개다. api-contract/api-create-verified 오퍼링은 경계값 계약 확인에만 사용했고 연결된 네트워크가 없다. [기계 판독용 자원 목록](resources.json).
