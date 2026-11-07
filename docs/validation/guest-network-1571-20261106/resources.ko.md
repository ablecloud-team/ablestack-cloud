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

각 클러스터에 L2 시험망 5개와 VM 2개를 남겼다. 오퍼링 2개와 전용 계정·도메인은 이 시험망의 의존 자원이다. 링크는 시험 클러스터 내부망에서 열 수 있다.

## 31번 GFS2

| 망 | 식별자 | 네트워크 |
| --- | --- | --- |
| 기본 L2 | `vlan://772` | [qa-gn1571-31-plain](http://10.10.31.10:8080/client/#/guestnetwork/86576f1d-a931-4f17-a18a-ff81d2e6ad16) |
| ConfigDrive | `vlan://773` | [qa-gn1571-31-configdrive](http://10.10.31.10:8080/client/#/guestnetwork/a5fee678-3cbc-4143-9f52-aaa5a2e1b30c) |
| PVLAN 커뮤니티 | `pvlan://774-c775` | [qa-gn1571-31-community](http://10.10.31.10:8080/client/#/guestnetwork/610dc86e-32ed-41ed-afb4-4616c37dbf80) |
| PVLAN 격리 | `pvlan://776-i777` | [qa-gn1571-31-isolated](http://10.10.31.10:8080/client/#/guestnetwork/84c767b3-5667-40b1-90a6-6aa85010edd8) |
| PVLAN 무차별 | `pvlan://778-p778` | [qa-gn1571-31-promiscuous](http://10.10.31.10:8080/client/#/guestnetwork/7e3f0c2a-b656-4294-8398-ac3d7a7c274f) |


| 가상머신 NIC 탭 | 기본 NIC | 보조 NIC |
| --- | --- | --- |
| [qa-gn1571-31-cd-vm1](http://10.10.31.10:8080/client/#/vm/0730b55d-b48f-4006-ba07-defaa30d5117?tab=nics) | ConfigDrive VLAN 773 | 기본 L2 VLAN 772 |
| [qa-gn1571-31-cd-vm2](http://10.10.31.10:8080/client/#/vm/8a76ca7f-ea3a-435b-b263-ff9dfabfd54c?tab=nics) | ConfigDrive VLAN 773 | — |

## 32번 Glue RBD

| 망 | 식별자 | 네트워크 |
| --- | --- | --- |
| 기본 L2 | `vlan://772` | [qa-gn1571-32-plain](http://10.10.32.10:8080/client/#/guestnetwork/838c260d-dfca-4589-8568-7db4dd292229) |
| ConfigDrive | `vlan://773` | [qa-gn1571-32-configdrive](http://10.10.32.10:8080/client/#/guestnetwork/ff71bb9c-3113-4edb-9c89-44a855ac451a) |
| PVLAN 커뮤니티 | `pvlan://774-c775` | [qa-gn1571-32-community](http://10.10.32.10:8080/client/#/guestnetwork/5ca442a3-e49e-42f9-87a1-56016435718b) |
| PVLAN 격리 | `pvlan://776-i777` | [qa-gn1571-32-isolated](http://10.10.32.10:8080/client/#/guestnetwork/db79bbb9-f0a7-4f6e-a21d-440e4ca8f4f1) |
| PVLAN 무차별 | `pvlan://778-p778` | [qa-gn1571-32-promiscuous](http://10.10.32.10:8080/client/#/guestnetwork/b8cc2a58-5f15-4b1e-8f2e-310f9dbda568) |


| 가상머신 NIC 탭 | 기본 NIC | 보조 NIC |
| --- | --- | --- |
| [qa-gn1571-32-cd-vm1](http://10.10.32.10:8080/client/#/vm/a3f6dc52-5f46-476a-aa70-253683154f52?tab=nics) | ConfigDrive VLAN 773 | 기본 L2 VLAN 772 |
| [qa-gn1571-32-cd-vm2](http://10.10.32.10:8080/client/#/vm/7348e207-891f-420a-8aa3-4549fd7ae900?tab=nics) | ConfigDrive VLAN 773 | — |
