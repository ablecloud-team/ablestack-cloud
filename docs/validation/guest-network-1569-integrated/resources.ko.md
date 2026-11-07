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

# 사용자 확인용 시험 자원

이전 #1572 자원은 정리했다. 이번에는 각 클러스터에 시험망 네 개와 실행 중인 VM 세 대를 남겼다. NAT VM에는 기존 Isolated NIC와 추가한 L2 NIC가 함께 있다. 연결된 시험 라우터, 주소와 범위, 전용 오퍼링 다섯 개, 역할 검증 계정 네 개·도메인 두 개·프로젝트 한 개도 남겼다.

수동 IP는 각 관리 서버 주소의 TCP 18069만 허용한다. 최종 포워딩은 공인 18069→게스트 18069이며 시험 HTTP 리스너는 종료했다. 송신 TCP 8080 규칙은 복구했다.

## 31번 GFS2

- [qa-gn1569-31-nat](http://10.10.31.10:8080/client/#/guestnetwork/9c79eaa6-e3de-4892-9cb9-fecb6b6f15a0?tab=details)
- [qa-gn1569-31-nosnat](http://10.10.31.10:8080/client/#/guestnetwork/b056b626-fe5e-4b75-ba17-78e421162dd7?tab=guest.ip.range)
- [qa-gn1569-31-l2](http://10.10.31.10:8080/client/#/guestnetwork/9103d235-6ff9-42b7-ad1f-0225d21f507a?tab=details)
- [qa-gn1569-31-shared](http://10.10.31.10:8080/client/#/guestnetwork/0346f16d-8a02-4fb6-860a-cc380def63f4?tab=guest.ip.range)

- [qa-gn1569-31-shared-vm](http://10.10.31.10:8080/client/#/vm/e0ab98ea-2495-4f86-871f-ebd61786dbd3?tab=nics)
- [qa-gn1569-31-nosnat-vm](http://10.10.31.10:8080/client/#/vm/cae707e9-1851-4ce1-a63c-ecd5173a64f7?tab=nics)
- [qa-gn1569-31-nat-vm](http://10.10.31.10:8080/client/#/vm/ee5d34e7-fab2-4159-af9d-a28a55f87ebb?tab=nics)

- [10.10.31.106 (수동 Public IP)](http://10.10.31.10:8080/client/#/publicip/e6978afb-a4ee-4512-8fb5-a06fe6477a83?tab=details)
- [10.10.31.143 (Source NAT)](http://10.10.31.10:8080/client/#/publicip/21a6e954-afa8-4bee-89c4-25c2745713b0?tab=details)

## 32번 Glue RBD

- [qa-gn1569-32-nat](http://10.10.32.10:8080/client/#/guestnetwork/75ccea08-34d0-48b6-80a9-006202b76e39?tab=details)
- [qa-gn1569-32-nosnat](http://10.10.32.10:8080/client/#/guestnetwork/28a009ed-333e-4968-8a8e-3f5b2f9ca667?tab=guest.ip.range)
- [qa-gn1569-32-l2](http://10.10.32.10:8080/client/#/guestnetwork/aefaf6fa-4255-4b64-9f28-2da2a25ea711?tab=details)
- [qa-gn1569-32-shared](http://10.10.32.10:8080/client/#/guestnetwork/eb1c8ca5-d19b-49a7-9de5-5575549a2b6e?tab=guest.ip.range)

- [qa-gn1569-32-nat-vm](http://10.10.32.10:8080/client/#/vm/d67429be-281f-443d-8ee6-ed7b7683e09d?tab=nics)
- [qa-gn1569-32-shared-vm](http://10.10.32.10:8080/client/#/vm/c0ae91e3-1538-49c7-94cb-a513c50e08ee?tab=nics)
- [qa-gn1569-32-nosnat-vm](http://10.10.32.10:8080/client/#/vm/23dbc3f4-4378-4fef-9420-959d724ae98e?tab=nics)

- [10.10.32.165 (수동 Public IP)](http://10.10.32.10:8080/client/#/publicip/3718e606-1ab1-48db-a660-7486d8dfc172?tab=details)
- [10.10.32.166 (Source NAT)](http://10.10.32.10:8080/client/#/publicip/04de4618-4265-4bfc-9b4c-95bc88ac1c2c?tab=details)


[자원 API/DB 증적](evidence/deployment-contract.json) · [VM 및 NIC 증적](evidence/vm-jobs.json) · [이전 정리와 보존 증적](evidence/cleanup-preservation.json)

## 2026-11-07 추가 자원

통신 상대 VM 세 대를 추가해 현재 31번은 시험망 4개·VM 4대, 32번은 시험망 4개·VM 5대다. [현재 확인용 자원](../guest-network-1569-guest-20261107/resources.ko.md)
