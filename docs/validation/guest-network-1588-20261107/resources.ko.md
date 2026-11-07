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

삭제 지시 전까지 유지한다. 기존 자원은 [이전 프로젝트 자원표](../guest-network-1569-project-20261107/resources.ko.md)와 [게스트 통신 자원표](../guest-network-1569-guest-20261107/resources.ko.md)를 참고한다. 현재 열어 둔 브라우저는 DomainAdmin이며 기존 관리자 로그인으로도 아래 자원을 확인할 수 있다. 인증 정보는 게시하지 않는다.

## 31번

본인 프로젝트 qa-gn1569-31-project · 4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe · 네트워크 18개

확인용 L2 [qa-gn1569-31-prj-1588-l2](http://10.10.31.10:8080/client/#/guestnetwork/2e36c615-ded0-4006-84ca-d210708b6250?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · 2e36c615-ded0-4006-84ca-d210708b6250 · 설명 UI1588 saved 31

VM [qa-gn1569-31-prj-vm](http://10.10.31.10:8080/client/#/vm/625141be-7145-4557-9d8f-731500c61cb0?tab=nics&projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · 625141be-7145-4557-9d8f-731500c61cb0 · Running · ablecube31-3

| NIC 네트워크 UUID | IP | MAC | 기본 |
| --- | --- | --- | --- |
| 424a28d7-1a3a-4b1f-974a-d09126fe7e46 | 10.180.31.25 | 1e:1f:3d:00:02:ee | True |
| d3669d88-bfdd-4053-8ef7-a58ea34808ad | Cloud IP 관리 안 함 | 02:1f:02:16:00:07 | False |
| 728d66ef-7c24-4708-b5e3-33f687528456 | Cloud IP 관리 안 함 | 02:1f:02:24:00:01 | False |
| 2e36c615-ded0-4006-84ca-d210708b6250 | Cloud IP 관리 안 함 | 02:1f:02:28:00:01 | False |

| 범위 시험 자원 | 이름 | UUID |
| --- | --- | --- |
| apiNetwork | qa-gn1569-31-prj-1588-api-l2 | 39e5b141-b9e0-499e-862d-9b4d29f6a734 |
| childDomain | qa-gn1569-31-1588-child | d4f7b77a-b7ae-4b1b-9644-09611dfb66e0 |
| childAccount | qa-gn1569-31-1588-child-owner | 99ac5e9b-279c-48f7-81f5-1bddc1ab6e2f |
| childProject | qa-gn1569-31-1588-child-project | a9119ae8-35f7-4524-a208-0cdb3b1d590f |
| childOffering | qa-gn1569-31-1588-child-l2-auto | b84ffe4a-bb50-49d3-ad5e-822c85b0faa1 |
| childNetwork | qa-gn1569-31-1588-child-l2 | 7717d494-7e80-49fb-a445-c814642ccde1 |
| outsideProject | qa-gn1569-31-1588-outside-project | 115290de-b12e-4c13-b227-fd02c33fdd76 |
| outsideOffering | qa-gn1569-31-1588-outside-l2-auto | 6cf0abeb-ae2d-418f-9bf3-3cfe440960ff |
| outsideNetwork | qa-gn1569-31-1588-outside-l2 | a65bbe05-b430-4279-a55a-ce2fae3db0f6 |

DomainAdmin은 하위 프로젝트에도 직접 멤버로 추가하지 않았다. 외부 프로젝트는 해당 도메인의 기존 외부 사용자 소유이며 관리자는 범위 밖 접근이 거부되는 것을 확인하는 용도다.

## 32번

본인 프로젝트 qa-gn1569-32-project · 0db180fc-4a19-4f9a-b387-00599ffa86c0 · 네트워크 19개

확인용 L2 [qa-gn1569-32-prj-1588-l2](http://10.10.32.10:8080/client/#/guestnetwork/5f01ed79-0dc9-4ccf-adfa-43a76571ee8f?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) · 5f01ed79-0dc9-4ccf-adfa-43a76571ee8f · 설명 UI1588 saved 32

VM [qa-gn1569-32-prj-vm](http://10.10.32.10:8080/client/#/vm/da7f69ed-1948-4927-b377-55beb9c4620d?tab=nics&projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) · da7f69ed-1948-4927-b377-55beb9c4620d · Running · ablecube32-2

| NIC 네트워크 UUID | IP | MAC | 기본 |
| --- | --- | --- | --- |
| e251eb02-f215-48f7-b956-0576233d97a2 | 10.180.32.20 | 1e:01:6e:00:02:e7 | True |
| e95802c5-94a0-48a6-a810-16f0a4ae7bb3 | Cloud IP 관리 안 함 | 02:01:01:a3:00:07 | False |
| 1f1f7c2d-58df-466d-9b7b-090ee67d3dfc | Cloud IP 관리 안 함 | 02:01:01:b2:00:01 | False |
| 5f01ed79-0dc9-4ccf-adfa-43a76571ee8f | Cloud IP 관리 안 함 | 02:01:01:b6:00:01 | False |

| 범위 시험 자원 | 이름 | UUID |
| --- | --- | --- |
| apiNetwork | qa-gn1569-32-prj-1588-api-l2 | d8b89c8a-89b0-41b6-8c56-76ee509da5cb |
| childDomain | qa-gn1569-32-1588-child | 33a87791-c280-4039-9c84-2720f0978dad |
| childAccount | qa-gn1569-32-1588-child-owner | 16610749-7fd8-4ca9-b513-3b0b03db74c9 |
| childProject | qa-gn1569-32-1588-child-project | d9a9327e-94d3-45ea-9b44-b8961692272e |
| childOffering | qa-gn1569-32-1588-child-l2-auto | 28d3c973-7921-4fa0-8006-918bea1a6ebc |
| childNetwork | qa-gn1569-32-1588-child-l2 | 7bdf4d67-aaa4-43b9-a180-9c3031307d29 |
| outsideProject | qa-gn1569-32-1588-outside-project | f91ac846-b80f-4835-8535-f461d27afcef |
| outsideOffering | qa-gn1569-32-1588-outside-l2-auto | 3823229a-5bd6-4bf3-9e76-f3efa55dc26f |
| outsideNetwork | qa-gn1569-32-1588-outside-l2 | c499e1b4-ac89-45d8-b7e8-e67c1af9fa9c |

DomainAdmin은 하위 프로젝트에도 직접 멤버로 추가하지 않았다. 외부 프로젝트는 해당 도메인의 기존 외부 사용자 소유이며 관리자는 범위 밖 접근이 거부되는 것을 확인하는 용도다.

