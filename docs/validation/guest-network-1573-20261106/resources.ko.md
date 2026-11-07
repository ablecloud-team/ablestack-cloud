<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# 확인용 시험 자원

각 클러스터에 전용 Isolated 네트워크와 시험 VM을 유지한다. VPN 공유 키는 기록하지 않았다.

## 31번 GFS2

| 화면 | 바로가기 |
| --- | --- |
| Source NAT · Remote Access VPN | [10.10.31.142](http://10.10.31.10:8080/client/#/publicip/25328461-bb0a-4b4e-8852-258ad4227d7f?tab=vpn) |
| 방화벽·포트 포워딩·LB | [10.10.31.143](http://10.10.31.10:8080/client/#/publicip/21a6e954-afa8-4bee-89c4-25c2745713b0?tab=firewall) |
| Static NAT | [10.10.31.141](http://10.10.31.10:8080/client/#/publicip/b2f14a08-e535-49d6-b3a7-8b545cf9e469?tab=details) |
| 격리망 | [qa-gn1573-31-isolated](http://10.10.31.10:8080/client/#/guestnetwork/ab0ffe86-80b4-4db6-a352-4234a8e5c84c) |
| 시험 VM | [qa-gn1573-31-vm](http://10.10.31.10:8080/client/#/vm/1ef4af6f-635e-41fc-b006-41e9b5e527f2) |

## 32번 Glue RBD

| 화면 | 바로가기 |
| --- | --- |
| Source NAT · Remote Access VPN | [10.10.32.166](http://10.10.32.10:8080/client/#/publicip/04de4618-4265-4bfc-9b4c-95bc88ac1c2c?tab=vpn) |
| 방화벽·포트 포워딩·LB | [10.10.32.165](http://10.10.32.10:8080/client/#/publicip/3718e606-1ab1-48db-a660-7486d8dfc172?tab=firewall) |
| Static NAT | [10.10.32.164](http://10.10.32.10:8080/client/#/publicip/5b6fd789-f97f-4d64-bbc4-1b465488a45b?tab=details) |
| 일반 IP · VPN 차단 확인 | [10.10.32.163](http://10.10.32.10:8080/client/#/publicip/a224e269-d6eb-4b4a-8130-bf2f8927f1c9?tab=details) |
| 격리망 | [qa-gn1573-32-isolated](http://10.10.32.10:8080/client/#/guestnetwork/c2609e60-027e-4611-96a2-854e8e02c601) |
| 시험 VM | [qa-gn1573-32-vm](http://10.10.32.10:8080/client/#/vm/d1d6c031-2365-496f-9777-04d60d838ade) |
