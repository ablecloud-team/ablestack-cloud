<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# 확인용 시험 자원

각 클러스터의 전용 QA 도메인에 아래 자원을 남겼다. Isolated 망에는 Skudonet을, Static Routed 망에는 별도 일반 VM과 가상 라우터를 연결했다.

## 31번 GFS2

| 화면 | 바로가기 |
| --- | --- |
| isolated | [qa-gn1574-31-isolated](http://10.10.31.10:8080/client/#/guestnetwork/20d91adc-7e3d-49b1-91a7-26a85c008770?tab=vnf.appliances) |
| l2 | [qa-gn1574-31-l2](http://10.10.31.10:8080/client/#/guestnetwork/f9c5467e-2b27-4dc8-a69d-5a9dfd22fd7c?tab=vnf.appliances) |
| shared | [qa-gn1574-31-shared](http://10.10.31.10:8080/client/#/guestnetwork/47551606-0ec9-4f45-8689-d5d597ed3791?tab=vnf.appliances) |
| static | [qa-gn1574-31-static](http://10.10.31.10:8080/client/#/guestnetwork/763f1092-f1eb-4372-a965-0c844eaa1862?tab=routing.firewall) |
| VNF 장비 | [Skudonet](http://10.10.31.10:8080/client/#/vnfapp/f21e54bf-f6dc-4e3a-b631-68de7ffdcb78) |
| VNF 템플릿 | [Skudonet 7.2.1](http://10.10.31.10:8080/client/#/template/b6170cce-bc78-4403-a525-666409dc8c51) |
| Routed 시험 VM | [VM 상세](http://10.10.31.10:8080/client/#/vm/f1491635-3f57-4e83-b9c2-c08d5ed3698c) |

## 32번 Glue RBD

| 화면 | 바로가기 |
| --- | --- |
| isolated | [qa-gn1574-32-isolated](http://10.10.32.10:8080/client/#/guestnetwork/fffda682-bdb8-4046-a0d6-53b6f74a9fe8?tab=vnf.appliances) |
| l2 | [qa-gn1574-32-l2](http://10.10.32.10:8080/client/#/guestnetwork/faca87e8-d73d-43ba-921e-47610cbfde49?tab=vnf.appliances) |
| shared | [qa-gn1574-32-shared](http://10.10.32.10:8080/client/#/guestnetwork/bd0289a6-1f12-4295-b475-3e4313c0e20b?tab=vnf.appliances) |
| static | [qa-gn1574-32-static](http://10.10.32.10:8080/client/#/guestnetwork/e1b5ecd9-f260-46bd-b26f-5692fa5033d2?tab=routing.firewall) |
| VNF 장비 | [Skudonet](http://10.10.32.10:8080/client/#/vnfapp/24349aef-a1ec-4543-a0f4-4786bcac4d00) |
| VNF 템플릿 | [Skudonet 7.2.1](http://10.10.32.10:8080/client/#/template/988540bc-63e7-469e-936d-a985b6a23b62) |
| Routed 시험 VM | [VM 상세](http://10.10.32.10:8080/client/#/vm/5bb745fd-1d65-44f8-aba2-0864a852e542) |

Static Routed 망의 확인용 규칙은 TCP 49175 수신이다.
