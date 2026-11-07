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

# 확인용 시험 자원

삭제 지시 전까지 유지한다. 인증 정보는 게시하지 않는다. 기존 개인 계정 시험 자원은 [앞선 게스트 통신 자원표](../guest-network-1569-guest-20261107/resources.ko.md)를 따른다.

## 31번

프로젝트 qa-gn1569-31-project · 4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe · [프로젝트 UI](http://10.10.31.10:8080/client/#/project/4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe)

VM qa-gn1569-31-prj-vm · 625141be-7145-4557-9d8f-731500c61cb0 · Running · ablecube31-3 · [NIC UI](http://10.10.31.10:8080/client/#/vm/625141be-7145-4557-9d8f-731500c61cb0?tab=nics)

최종 NIC는 기본 Shared base와 프로젝트 L2다. 다른 유형 연결은 시험 중 성공 후 해제했다. VM과 네트워크는 보존했다.

| NIC | 네트워크 | IP | 기본 |
| --- | --- | --- | --- |
| 0 | qa-gn1569-31-prj-base | 10.180.31.25 | True |
| 1 | qa-gn1569-31-prj-l2 | Cloud IP 관리 안 함 | False |

| 네트워크 | 유형 | UUID |
| --- | --- | --- |
| [qa-gn1569-31-prj-api-admin-l2](http://10.10.31.10:8080/client/#/guestnetwork/7f0f6875-3031-4dec-8f2f-f5a1954ca86f) | L2 | 7f0f6875-3031-4dec-8f2f-f5a1954ca86f |
| [qa-gn1569-31-prj-api-admin-shared-auto](http://10.10.31.10:8080/client/#/guestnetwork/d3060d03-3628-4a0c-af76-396a02933303) | Shared | d3060d03-3628-4a0c-af76-396a02933303 |
| [qa-gn1569-31-prj-api-domainadmin-l2](http://10.10.31.10:8080/client/#/guestnetwork/fe81ba7d-e09d-43dd-b79f-92e2aa86e396) | L2 | fe81ba7d-e09d-43dd-b79f-92e2aa86e396 |
| [qa-gn1569-31-prj-api-domainadmin-shared-auto](http://10.10.31.10:8080/client/#/guestnetwork/18ba1a94-154a-4c68-851f-5bad2a29d6c2) | Shared | 18ba1a94-154a-4c68-851f-5bad2a29d6c2 |
| [qa-gn1569-31-prj-api-member-l2](http://10.10.31.10:8080/client/#/guestnetwork/af09c1b5-544c-41a0-a309-40ab097185b4) | L2 | af09c1b5-544c-41a0-a309-40ab097185b4 |
| [qa-gn1569-31-prj-api-member-shared-auto](http://10.10.31.10:8080/client/#/guestnetwork/f7145035-588f-4ccc-b249-07187376bd12) | Shared | f7145035-588f-4ccc-b249-07187376bd12 |
| [qa-gn1569-31-prj-api-owner-l2](http://10.10.31.10:8080/client/#/guestnetwork/ba2403f9-b4b7-43c3-8a69-ca68dec59390) | L2 | ba2403f9-b4b7-43c3-8a69-ca68dec59390 |
| [qa-gn1569-31-prj-api-owner-shared-auto](http://10.10.31.10:8080/client/#/guestnetwork/5c033c7a-1bdd-444e-b643-b01ac68135f9) | Shared | 5c033c7a-1bdd-444e-b643-b01ac68135f9 |
| [qa-gn1569-31-prj-base](http://10.10.31.10:8080/client/#/guestnetwork/424a28d7-1a3a-4b1f-974a-d09126fe7e46) | Shared | 424a28d7-1a3a-4b1f-974a-d09126fe7e46 |
| [qa-gn1569-31-prj-l2](http://10.10.31.10:8080/client/#/guestnetwork/d3669d88-bfdd-4053-8ef7-a58ea34808ad) | L2 | d3669d88-bfdd-4053-8ef7-a58ea34808ad |
| [qa-gn1569-31-prj-nat](http://10.10.31.10:8080/client/#/guestnetwork/fe5b298f-f78e-49ea-b9e2-36e22c0ebdc1) | Isolated | fe5b298f-f78e-49ea-b9e2-36e22c0ebdc1 |
| [qa-gn1569-31-prj-shared](http://10.10.31.10:8080/client/#/guestnetwork/a758c862-e03e-4df1-81b9-4443b76b74ab) | Shared | a758c862-e03e-4df1-81b9-4443b76b74ab |
| [qa-gn1569-31-prj-shared-auto](http://10.10.31.10:8080/client/#/guestnetwork/9894fbc3-27aa-49ac-8a60-cd6095331181) | Shared | 9894fbc3-27aa-49ac-8a60-cd6095331181 |
| [qa-gn1569-31-prj-ui-member-l2](http://10.10.31.10:8080/client/#/guestnetwork/75fa4086-e446-485e-b4d9-991f01760e72) | L2 | 75fa4086-e446-485e-b4d9-991f01760e72 |
| [qa-gn1569-31-prj-ui-owner-l2](http://10.10.31.10:8080/client/#/guestnetwork/f44335b0-d824-4693-bd02-c2995a7afe06) | L2 | f44335b0-d824-4693-bd02-c2995a7afe06 |

| 시험 계정 | 프로젝트 역할 |
| --- | --- |
| qa-gn1569-31-owner | Admin |
| qa-gn1569-31-member | Regular |
| qa-gn1569-31-readonly | Regular + 사용자 정의 제한 역할 |
| qa-gn1569-31-domainadmin | 도메인 관리자, 직접 비멤버 |
| qa-gn1569-31-peer | 같은 도메인 비멤버 |
| qa-gn1569-31-outsider | 다른 도메인 비멤버 |

[최종 API 인벤토리](evidence/31-final-state.json)

## 32번

프로젝트 qa-gn1569-32-project · 0db180fc-4a19-4f9a-b387-00599ffa86c0 · [프로젝트 UI](http://10.10.32.10:8080/client/#/project/0db180fc-4a19-4f9a-b387-00599ffa86c0)

VM qa-gn1569-32-prj-vm · da7f69ed-1948-4927-b377-55beb9c4620d · Running · ablecube32-2 · [NIC UI](http://10.10.32.10:8080/client/#/vm/da7f69ed-1948-4927-b377-55beb9c4620d?tab=nics)

최종 NIC는 기본 Shared base와 프로젝트 L2다. 다른 유형 연결은 시험 중 성공 후 해제했다. VM과 네트워크는 보존했다.

| NIC | 네트워크 | IP | 기본 |
| --- | --- | --- | --- |
| 0 | qa-gn1569-32-prj-base | 10.180.32.20 | True |
| 1 | qa-gn1569-32-prj-l2 | Cloud IP 관리 안 함 | False |

| 네트워크 | 유형 | UUID |
| --- | --- | --- |
| [qa-gn1569-32-prj-api-admin-l2](http://10.10.32.10:8080/client/#/guestnetwork/bae5c362-d038-40fa-9123-52e8acc7374d) | L2 | bae5c362-d038-40fa-9123-52e8acc7374d |
| [qa-gn1569-32-prj-api-admin-shared-auto](http://10.10.32.10:8080/client/#/guestnetwork/1b2f159e-f516-40ce-9395-2dc64b9d0b95) | Shared | 1b2f159e-f516-40ce-9395-2dc64b9d0b95 |
| [qa-gn1569-32-prj-api-domainadmin-l2](http://10.10.32.10:8080/client/#/guestnetwork/bea39c70-88c0-40a5-8ce8-5cc2395c6e2d) | L2 | bea39c70-88c0-40a5-8ce8-5cc2395c6e2d |
| [qa-gn1569-32-prj-api-domainadmin-shared-auto](http://10.10.32.10:8080/client/#/guestnetwork/43299012-72da-42e4-9c1f-e80d4135a4e0) | Shared | 43299012-72da-42e4-9c1f-e80d4135a4e0 |
| [qa-gn1569-32-prj-api-member-l2](http://10.10.32.10:8080/client/#/guestnetwork/af3b901c-e446-470f-ab9b-9460d804c735) | L2 | af3b901c-e446-470f-ab9b-9460d804c735 |
| [qa-gn1569-32-prj-api-member-shared-auto](http://10.10.32.10:8080/client/#/guestnetwork/ce6ab5de-f61c-4ed9-9569-81c663c86fdf) | Shared | ce6ab5de-f61c-4ed9-9569-81c663c86fdf |
| [qa-gn1569-32-prj-api-owner-l2](http://10.10.32.10:8080/client/#/guestnetwork/c75aec2a-fb86-43af-a69a-995e537ed299) | L2 | c75aec2a-fb86-43af-a69a-995e537ed299 |
| [qa-gn1569-32-prj-api-owner-shared-auto](http://10.10.32.10:8080/client/#/guestnetwork/5a28ec96-f935-41f4-b962-54514d665601) | Shared | 5a28ec96-f935-41f4-b962-54514d665601 |
| [qa-gn1569-32-prj-base](http://10.10.32.10:8080/client/#/guestnetwork/e251eb02-f215-48f7-b956-0576233d97a2) | Shared | e251eb02-f215-48f7-b956-0576233d97a2 |
| [qa-gn1569-32-prj-l2](http://10.10.32.10:8080/client/#/guestnetwork/e95802c5-94a0-48a6-a810-16f0a4ae7bb3) | L2 | e95802c5-94a0-48a6-a810-16f0a4ae7bb3 |
| [qa-gn1569-32-prj-nat](http://10.10.32.10:8080/client/#/guestnetwork/d58e60cb-b88f-48ed-b5e7-eee0fccf8aec) | Isolated | d58e60cb-b88f-48ed-b5e7-eee0fccf8aec |
| [qa-gn1569-32-prj-shared](http://10.10.32.10:8080/client/#/guestnetwork/a8a685fe-41b1-448a-891e-2e45ab65a8ce) | Shared | a8a685fe-41b1-448a-891e-2e45ab65a8ce |
| [qa-gn1569-32-prj-shared-auto](http://10.10.32.10:8080/client/#/guestnetwork/94205e36-52bc-4bb8-ae37-a1f9bddc5d3c) | Shared | 94205e36-52bc-4bb8-ae37-a1f9bddc5d3c |
| [qa-gn1569-32-prj-ui-admin-l2](http://10.10.32.10:8080/client/#/guestnetwork/7af904b4-ae8f-45c2-82c5-fa1fd85c331c) | L2 | 7af904b4-ae8f-45c2-82c5-fa1fd85c331c |
| [qa-gn1569-32-prj-ui-member-l2](http://10.10.32.10:8080/client/#/guestnetwork/d2446c57-3634-4f4a-83c4-538d47865b0f) | L2 | d2446c57-3634-4f4a-83c4-538d47865b0f |
| [qa-gn1569-32-prj-ui-owner-l2](http://10.10.32.10:8080/client/#/guestnetwork/c8fb1f5b-dd71-4a10-9cb9-6cfd66009990) | L2 | c8fb1f5b-dd71-4a10-9cb9-6cfd66009990 |

| 시험 계정 | 프로젝트 역할 |
| --- | --- |
| qa-gn1569-32-owner | Admin |
| qa-gn1569-32-member | Regular |
| qa-gn1569-32-readonly | Regular + 사용자 정의 제한 역할 |
| qa-gn1569-32-domainadmin | 도메인 관리자, 직접 비멤버 |
| qa-gn1569-32-peer | 같은 도메인 비멤버 |
| qa-gn1569-32-outsider | 다른 도메인 비멤버 |

[최종 API 인벤토리](evidence/32-final-state.json)

31 관리자의 프로젝트 선택은 #1587 저장 오류가 남아 있다. 직접 VM 링크에서 프로젝트명·NIC를 확인할 수 있어도 정상적인 프로젝트 전환 성공으로 판정하지 않는다. 도메인 관리자와 제한 역할의 전환 실패는 #1588·#1589로 추적한다.
