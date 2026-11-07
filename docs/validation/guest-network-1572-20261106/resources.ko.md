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

31번에는 Shared 8개, 32번에는 Shared 6개와 각 VM 2대를 남겼다. 연관 L2 네트워크, 시험 계정·도메인·프로젝트·오퍼링은 의존 자원이다. 링크는 시험망에서 열린다. 프로젝트 리소스는 해당 프로젝트 컨텍스트에서 확인할 수 있다.

## 31번 GFS2

| 범위 / 기능 | 네트워크 | 브로드캐스트 |
| --- | --- | --- |
| 모두 | [qa-gn1572-31-all](http://10.10.31.10:8080/client/#/guestnetwork/6dde9ac4-bb32-40b7-9e5b-7e63b0f52b4f?tab=details) | `vlan://781` |
| 도메인 및 하위 도메인 | [qa-gn1572-31-domain](http://10.10.31.10:8080/client/#/guestnetwork/c323222c-62b0-46fd-8ac1-bc4ff571a321?tab=details) | `vlan://782` |
| 도메인 한정 | [qa-gn1572-31-nosub](http://10.10.31.10:8080/client/#/guestnetwork/2938dfbf-4f8c-4fc8-8f8e-62cd4da5103c?tab=details) | `vlan://787` |
| 계정 | [qa-gn1572-31-account](http://10.10.31.10:8080/client/#/guestnetwork/ab30a829-8936-4058-836f-d66ed7d4db90?tab=guest.ip.range) | `vlan://783` |
| 프로젝트 | [qa-gn1572-31-project](http://10.10.31.10:8080/client/#/guestnetwork/972c9bbf-6d05-4d15-b79a-f1d99647738a?tab=details&projectid=80135e7e-9da0-4fe5-b54a-185374c35411) | `vlan://784` |
| 연관 L2 | [qa-gn1572-31-related](http://10.10.31.10:8080/client/#/guestnetwork/b8ecb370-9264-454c-b34d-f7b182490a99?tab=details) | `vlan://785` |
| IPv4 + IPv6 | [qa-gn1572-31-dual](http://10.10.31.10:8080/client/#/guestnetwork/6e7050da-355d-41a1-af38-9012ed8d369a?tab=guest.ip.range) | `vlan://788` |
| PVLAN 커뮤니티 | [qa-gn1572-31-pvlan](http://10.10.31.10:8080/client/#/guestnetwork/372f0238-0648-4a0e-b6fa-2e81d364acbb?tab=details) | `pvlan://789-c790` |
| 연관망의 L2 부모 | [qa-gn1572-31-parent](http://10.10.31.10:8080/client/#/guestnetwork/efdbf862-f91f-4be8-9cc5-22560e560c7b) | `vlan://785` |


| VM NIC 탭 | 상태 |
| --- | --- |
| [qa-gn1572-31-vm1](http://10.10.31.10:8080/client/#/vm/51adf683-9400-4824-94bb-202f16054d51?tab=nics) | Running |
| [qa-gn1572-31-vm2](http://10.10.31.10:8080/client/#/vm/e7890dcb-f8b9-4c84-855c-07be5d3d2d51?tab=nics) | Running |

## 32번 Glue RBD

| 범위 / 기능 | 네트워크 | 브로드캐스트 |
| --- | --- | --- |
| 모두 | [qa-gn1572-32-all](http://10.10.32.10:8080/client/#/guestnetwork/a06138fb-771e-4a3a-b6aa-6679cd3b7b04?tab=details) | `vlan://781` |
| 도메인 및 하위 도메인 | [qa-gn1572-32-domain](http://10.10.32.10:8080/client/#/guestnetwork/0487ce98-3406-4e34-9b3e-031af209c66f?tab=details) | `vlan://782` |
| 계정 | [qa-gn1572-32-account](http://10.10.32.10:8080/client/#/guestnetwork/edcc66a7-ad24-4252-a96b-b202833845c4?tab=guest.ip.range) | `vlan://783` |
| 프로젝트 | [qa-gn1572-32-project](http://10.10.32.10:8080/client/#/guestnetwork/934acfd3-98b9-43dd-8d46-5ea1c0b60036?tab=details&projectid=aa681497-faaa-49ad-8429-dafd611a2a36) | `vlan://784` |
| 연관 L2 | [qa-gn1572-32-related](http://10.10.32.10:8080/client/#/guestnetwork/57d0c55b-c7c1-4510-a3f1-fe17c098fc5d?tab=details) | `vlan://785` |
| IPv4 + IPv6 | [qa-gn1572-32-dual](http://10.10.32.10:8080/client/#/guestnetwork/e6aa59e1-4f2a-4d41-82aa-26bd082b8548?tab=guest.ip.range) | `vlan://788` |
| 연관망의 L2 부모 | [qa-gn1572-32-parent](http://10.10.32.10:8080/client/#/guestnetwork/588d5fbd-f574-4c7a-800e-3254c21a95fb) | `vlan://785` |


| VM NIC 탭 | 상태 |
| --- | --- |
| [qa-gn1572-32-vm1](http://10.10.32.10:8080/client/#/vm/f1852d0d-7c88-46ec-b86b-9e3aa4dcb0cf?tab=nics) | Running |
| [qa-gn1572-32-vm2](http://10.10.32.10:8080/client/#/vm/f28b97da-4a6c-4a1e-b3ce-9a966a4e86ef?tab=nics) | Running |
