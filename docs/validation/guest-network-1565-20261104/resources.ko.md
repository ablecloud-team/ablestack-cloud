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

# 유지 중인 검토 자원

별도 삭제 지시까지 유지한다. 새 사용자 VM은 만들지 않았다. 로그인 비밀번호·세션·API 키는 게시하지 않는다. 각 서버 관리자로 로그인한 후 전체 프로젝트 범위를 선택하면 8개 망을 함께 볼 수 있다. 기본 범위는 프로젝트 망을 제외한 7개다.

## 31번

[전체 프로젝트의 시험망 목록](http://10.10.31.10:8080/client/#/guestnetwork?projectid=-1&keyword=qa-gn1565-31&q=qa-gn1565-31&page=1&pagesize=20) · [사용 중 Disabled 오퍼링](http://10.10.31.10:8080/client/#/networkoffering/2c643246-508a-4446-a730-ca9214cc3281?projectid=-1&tab=network.offering.used.networks)

| 이름 | 유형 | UUID·상세 |
|---|---|---|
| qa-gn1565-31-l2-01 | L2 | [e2bb5595-29f3-40dd-9131-b312ad8d3646](http://10.10.31.10:8080/client/#/guestnetwork/e2bb5595-29f3-40dd-9131-b312ad8d3646?projectid=-1&tab=details) |
| qa-gn1565-31-isolated | Isolated | [21aed6eb-a54d-4b5f-9eed-45e60fc2b7fb](http://10.10.31.10:8080/client/#/guestnetwork/21aed6eb-a54d-4b5f-9eed-45e60fc2b7fb?projectid=-1&tab=details) |
| qa-gn1565-31-shared | Shared | [023fb085-8626-410e-bf78-d178f9de0b4e](http://10.10.31.10:8080/client/#/guestnetwork/023fb085-8626-410e-bf78-d178f9de0b4e?projectid=-1&tab=details) |
| qa-gn1565-31-user-a | L2 | [00049f7f-9088-4cf3-904d-1de64430ab15](http://10.10.31.10:8080/client/#/guestnetwork/00049f7f-9088-4cf3-904d-1de64430ab15?projectid=-1&tab=details) |
| qa-gn1565-31-admin-a | L2 | [a7a61adf-d31d-4d81-a0e6-66e1ab4fa8db](http://10.10.31.10:8080/client/#/guestnetwork/a7a61adf-d31d-4d81-a0e6-66e1ab4fa8db?projectid=-1&tab=details) |
| qa-gn1565-31-user-b | L2 | [5934cab6-4251-4c3f-a490-c48d916bcc31](http://10.10.31.10:8080/client/#/guestnetwork/5934cab6-4251-4c3f-a490-c48d916bcc31?projectid=-1&tab=details) |
| qa-gn1565-31-project | L2 | [7d0c9d5b-dfe9-44d5-bc5f-ecd96167dac0](http://10.10.31.10:8080/client/#/guestnetwork/7d0c9d5b-dfe9-44d5-bc5f-ecd96167dac0?projectid=-1&tab=details) |
| qa-gn1565-31-vpc-tier | Isolated | [d5147259-90f9-4663-aff4-0261558ae629](http://10.10.31.10:8080/client/#/guestnetwork/d5147259-90f9-4663-aff4-0261558ae629?projectid=-1&tab=details) |

전용 오퍼링 4개: `used`(Disabled), `unused`(Disabled), `private-a`, `private-b`. 도메인 a/b·User a/b·DomainAdmin a·프로젝트·VPC·Running VPC 라우터 1개도 유지한다. [전체 UUID/계정명](31-retained.json) · [라우터](31-retained-routers.json).

## 32번

[전체 프로젝트의 시험망 목록](http://10.10.32.10:8080/client/#/guestnetwork?projectid=-1&keyword=qa-gn1565-32&q=qa-gn1565-32&page=1&pagesize=20) · [사용 중 Disabled 오퍼링](http://10.10.32.10:8080/client/#/networkoffering/324d6a00-b081-46db-9533-72a323c0d734?projectid=-1&tab=network.offering.used.networks)

| 이름 | 유형 | UUID·상세 |
|---|---|---|
| qa-gn1565-32-l2-01 | L2 | [9acaeec3-3586-4456-ab13-c6453f28f3d2](http://10.10.32.10:8080/client/#/guestnetwork/9acaeec3-3586-4456-ab13-c6453f28f3d2?projectid=-1&tab=details) |
| qa-gn1565-32-isolated | Isolated | [5ab2ad8a-2165-4f57-acd3-022d260e5239](http://10.10.32.10:8080/client/#/guestnetwork/5ab2ad8a-2165-4f57-acd3-022d260e5239?projectid=-1&tab=details) |
| qa-gn1565-32-shared | Shared | [b7104c76-a9b4-48b4-a00c-edd469f2d110](http://10.10.32.10:8080/client/#/guestnetwork/b7104c76-a9b4-48b4-a00c-edd469f2d110?projectid=-1&tab=details) |
| qa-gn1565-32-user-a | L2 | [a5a04339-1e59-476a-820a-7625d2128b70](http://10.10.32.10:8080/client/#/guestnetwork/a5a04339-1e59-476a-820a-7625d2128b70?projectid=-1&tab=details) |
| qa-gn1565-32-admin-a | L2 | [eaec7ac9-19b4-4f1b-88e7-f360c884bd60](http://10.10.32.10:8080/client/#/guestnetwork/eaec7ac9-19b4-4f1b-88e7-f360c884bd60?projectid=-1&tab=details) |
| qa-gn1565-32-user-b | L2 | [b7aa8f81-330c-4770-9f52-ebb896df60e3](http://10.10.32.10:8080/client/#/guestnetwork/b7aa8f81-330c-4770-9f52-ebb896df60e3?projectid=-1&tab=details) |
| qa-gn1565-32-project | L2 | [976ab2c6-467d-4480-83dc-9588ad09468d](http://10.10.32.10:8080/client/#/guestnetwork/976ab2c6-467d-4480-83dc-9588ad09468d?projectid=-1&tab=details) |
| qa-gn1565-32-vpc-tier | Isolated | [6b01a3a6-7d58-4663-bda7-4619f6983f9a](http://10.10.32.10:8080/client/#/guestnetwork/6b01a3a6-7d58-4663-bda7-4619f6983f9a?projectid=-1&tab=details) |

전용 오퍼링 4개: `used`(Disabled), `unused`(Disabled), `private-a`, `private-b`. 도메인 a/b·User a/b·DomainAdmin a·프로젝트·VPC·Running VPC 라우터 1개도 유지한다. [전체 UUID/계정명](32-retained.json) · [라우터](32-retained-routers.json).
