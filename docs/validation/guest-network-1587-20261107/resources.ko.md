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

# 확인용 자원

삭제 지시 전까지 모두 유지한다. 관리자 계정으로 아래 프로젝트와 VM NIC를 확인할 수 있다. 인증 정보는 게시하지 않는다. 기존 자원은 [이전 프로젝트 자원표](../guest-network-1569-project-20261107/resources.ko.md)와 [게스트 통신 자원표](../guest-network-1569-guest-20261107/resources.ko.md)에 있다.

## 31번

[qa-gn1569-31-project](http://10.10.31.10:8080/client/#/project/4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · 프로젝트망 16개

새 L2 [qa-gn1569-31-prj-1587-l2](http://10.10.31.10:8080/client/#/guestnetwork/728d66ef-7c24-4708-b5e3-33f687528456) · 728d66ef-7c24-4708-b5e3-33f687528456 · 설명 UI1587 saved 31

VM [qa-gn1569-31-prj-vm](http://10.10.31.10:8080/client/#/vm/625141be-7145-4557-9d8f-731500c61cb0?tab=nics) · 625141be-7145-4557-9d8f-731500c61cb0 · Running · ablecube31-3

| 네트워크 | IP | MAC | 기본 |
| --- | --- | --- | --- |
| qa-gn1569-31-prj-base | 10.180.31.25 | 1e:1f:3d:00:02:ee | True |
| qa-gn1569-31-prj-l2 | Cloud IP 관리 안 함 | 02:1f:02:16:00:07 | False |
| qa-gn1569-31-prj-1587-l2 | Cloud IP 관리 안 함 | 02:1f:02:24:00:01 | False |

[최종 API 상태](evidence/31-after.json)

## 32번

[qa-gn1569-32-project](http://10.10.32.10:8080/client/#/project/0db180fc-4a19-4f9a-b387-00599ffa86c0) · 프로젝트망 17개

새 L2 [qa-gn1569-32-prj-1587-l2](http://10.10.32.10:8080/client/#/guestnetwork/1f1f7c2d-58df-466d-9b7b-090ee67d3dfc) · 1f1f7c2d-58df-466d-9b7b-090ee67d3dfc · 설명 UI1587 saved 32

VM [qa-gn1569-32-prj-vm](http://10.10.32.10:8080/client/#/vm/da7f69ed-1948-4927-b377-55beb9c4620d?tab=nics) · da7f69ed-1948-4927-b377-55beb9c4620d · Running · ablecube32-2

| 네트워크 | IP | MAC | 기본 |
| --- | --- | --- | --- |
| qa-gn1569-32-prj-base | 10.180.32.20 | 1e:01:6e:00:02:e7 | True |
| qa-gn1569-32-prj-l2 | Cloud IP 관리 안 함 | 02:01:01:a3:00:07 | False |
| qa-gn1569-32-prj-1587-l2 | Cloud IP 관리 안 함 | 02:01:01:b2:00:01 | False |

[최종 API 상태](evidence/32-after.json)
