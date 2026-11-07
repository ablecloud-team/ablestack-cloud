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

현재 브라우저에는 제한 계정의 프로젝트 VM NIC 화면을 남겼다. 기존 관리자 로그인으로도 아래 자원을 확인할 수 있다. 인증 정보는 게시하지 않는다. 기존 자원은 [#1588 자원표](../guest-network-1588-20261107/resources.ko.md)를 참고한다. 삭제는 별도 지시를 기다린다.

## 31번

프로젝트 qa-gn1569-31-project · 4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe · 망 23개·멤버십 4개

확인용 [qa-gn1569-31-prj-1589-ui-l2](http://10.10.31.10:8080/client/#/guestnetwork/2f7a0562-3b1d-4fb5-91b0-55472d731e06?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · 2f7a0562-3b1d-4fb5-91b0-55472d731e06 · UI1589 owner saved 31

VM [qa-gn1569-31-prj-vm](http://10.10.31.10:8080/client/#/vm/625141be-7145-4557-9d8f-731500c61cb0?tab=nics&projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · Running · ablecube31-3 · NIC 6개

| 네트워크 | IP | MAC | 기본 |
| --- | --- | --- | --- |
| qa-gn1569-31-prj-base | 10.180.31.25 | 1e:1f:3d:00:02:ee | True |
| qa-gn1569-31-prj-l2 | Cloud IP 관리 안 함 | 02:1f:02:16:00:07 | False |
| qa-gn1569-31-prj-1587-l2 | Cloud IP 관리 안 함 | 02:1f:02:24:00:01 | False |
| qa-gn1569-31-prj-1588-l2 | Cloud IP 관리 안 함 | 02:1f:02:28:00:01 | False |
| qa-gn1569-31-prj-1589-root-l2 | Cloud IP 관리 안 함 | 02:1f:02:29:00:02 | False |
| qa-gn1569-31-prj-1589-ui-l2 | Cloud IP 관리 안 함 | 02:1f:02:2d:00:01 | False |

| API 대조용 L2 | UUID |
| --- | --- |
| qa-gn1569-31-prj-1589-root-l2 | 3705d624-3634-4c19-b83a-6843db009a9c |
| qa-gn1569-31-prj-1589-owner-l2 | ab9e1b96-a36c-4e7a-9c6b-8cdd802fa675 |
| qa-gn1569-31-prj-1589-member-l2 | c83f9673-1da8-4f0e-a4fa-cdcb181caaf6 |
| qa-gn1569-31-prj-1589-domainadmin-l2 | 9b2dc601-e116-487f-95cb-de5ac784de90 |

사용자 단위 제한 멤버 계정: qa-gn1569-31-1589-user · 1eadcc13-55aa-4f35-bb49-34116aca7997 · 도메인 f3772036-4b8f-41fd-83f2-b71751950364

제한 역할 UUID: c715284e-63e6-40dc-9ef1-70cfd33ab521 · 원래 10개 규칙 내용/순서 복원 완료. 네트워크 한도는 확인용 자원 유지를 위해 30이며 정리 후 20으로 복구한다.

[최종 API·NIC·job 증적](evidence/31-final.json)

## 32번

프로젝트 qa-gn1569-32-project · 0db180fc-4a19-4f9a-b387-00599ffa86c0 · 망 24개·멤버십 4개

확인용 [qa-gn1569-32-prj-1589-ui-l2](http://10.10.32.10:8080/client/#/guestnetwork/7bbb5e0f-c387-45de-bd88-703f9e37fd71?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) · 7bbb5e0f-c387-45de-bd88-703f9e37fd71 · UI1589 owner saved 32

VM [qa-gn1569-32-prj-vm](http://10.10.32.10:8080/client/#/vm/da7f69ed-1948-4927-b377-55beb9c4620d?tab=nics&projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) · Running · ablecube32-2 · NIC 6개

| 네트워크 | IP | MAC | 기본 |
| --- | --- | --- | --- |
| qa-gn1569-32-prj-base | 10.180.32.20 | 1e:01:6e:00:02:e7 | True |
| qa-gn1569-32-prj-l2 | Cloud IP 관리 안 함 | 02:01:01:a3:00:07 | False |
| qa-gn1569-32-prj-1587-l2 | Cloud IP 관리 안 함 | 02:01:01:b2:00:01 | False |
| qa-gn1569-32-prj-1588-l2 | Cloud IP 관리 안 함 | 02:01:01:b6:00:01 | False |
| qa-gn1569-32-prj-1589-root-l2 | Cloud IP 관리 안 함 | 02:01:01:b7:00:02 | False |
| qa-gn1569-32-prj-1589-ui-l2 | Cloud IP 관리 안 함 | 02:01:01:bb:00:01 | False |

| API 대조용 L2 | UUID |
| --- | --- |
| qa-gn1569-32-prj-1589-root-l2 | 265930da-18eb-4131-8b94-860e18d80a4e |
| qa-gn1569-32-prj-1589-owner-l2 | c6419cc0-62de-4b38-9673-e9a1f71ef47c |
| qa-gn1569-32-prj-1589-member-l2 | 0474d9ec-196b-417b-b9dc-7b1ba81554f0 |
| qa-gn1569-32-prj-1589-domainadmin-l2 | 34d7589a-ee42-4064-891c-8d1bae4b41b3 |

사용자 단위 제한 멤버 계정: qa-gn1569-32-1589-user · 8558ce11-58f2-4339-a2c9-e6a32bd43c2a · 도메인 a051d20d-2c05-43d5-833e-e231a80b12f7

제한 역할 UUID: a3d3ed1e-1479-48f2-afbc-c2e0e9c96a81 · 원래 10개 규칙 내용/순서 복원 완료. 네트워크 한도는 확인용 자원 유지를 위해 30이며 정리 후 20으로 복구한다.

[최종 API·NIC·job 증적](evidence/32-final.json)

