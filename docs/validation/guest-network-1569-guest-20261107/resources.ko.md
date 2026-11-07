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

기존 #1569 시험망은 각 클러스터에 네 개씩 유지했다. 통신 상대 VM을 31번 Shared에 한 대, 32번 Shared와 no-SourceNat에 한 대씩 추가했다. 각 시험 쌍은 가상 라우터와 같은 호스트에 있다. 기존 VM의 UUID·NIC MAC·IP와 네트워크 설정을 대조했다.

## 31번 GFS2

시험 네트워크 4개, 실행 중인 시험 VM 4대.

- [qa-gn1569-31-shared](http://10.10.31.10:8080/client/#/guestnetwork/0346f16d-8a02-4fb6-860a-cc380def63f4?tab=virtual.routers)
- [qa-gn1569-31-shared-vm](http://10.10.31.10:8080/client/#/vm/e0ab98ea-2495-4f86-871f-ebd61786dbd3?tab=nics) — 10.178.31.25, ablecube31-3
- [qa-gn1569-31-shared-peer](http://10.10.31.10:8080/client/#/vm/ff81dfc9-7acb-46f7-828a-ceb1577a16cf?tab=nics) — 10.178.31.16, ablecube31-3

## 32번 Glue RBD

시험 네트워크 4개, 실행 중인 시험 VM 5대.

- [qa-gn1569-32-shared](http://10.10.32.10:8080/client/#/guestnetwork/eb1c8ca5-d19b-49a7-9de5-5575549a2b6e?tab=virtual.routers)
- [qa-gn1569-32-shared-vm](http://10.10.32.10:8080/client/#/vm/c0ae91e3-1538-49c7-94cb-a513c50e08ee?tab=nics) — 10.178.32.20, ablecube32-2
- [qa-gn1569-32-shared-peer](http://10.10.32.10:8080/client/#/vm/f309cc72-72dc-4f43-b18e-723e87098f22?tab=nics) — 10.178.32.21, ablecube32-2
- [qa-gn1569-32-nosnat](http://10.10.32.10:8080/client/#/guestnetwork/28a009ed-333e-4968-8a8e-3f5b2f9ca667?tab=virtual.routers)
- [qa-gn1569-32-nosnat-vm](http://10.10.32.10:8080/client/#/vm/23dbc3f4-4378-4fef-9420-959d724ae98e?tab=nics) — 10.177.32.20, ablecube32-2
- [qa-gn1569-32-nosnat-peer](http://10.10.32.10:8080/client/#/vm/ef8ecf84-8508-4bda-b69a-eb1e2914969f?tab=nics) — 10.177.32.21, ablecube32-2


[최종 API·libvirt 상태](evidence/final-state.json) · [이전 통합 검증의 전체 자원 목록](../guest-network-1569-integrated/resources.ko.md)
