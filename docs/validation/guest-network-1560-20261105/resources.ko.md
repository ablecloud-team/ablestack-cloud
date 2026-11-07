<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may
obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
-->

# #1560 사용자 검토 자원

2026-11-05 검증 완료 후 **유지**했다. 별도 삭제 지시까지 삭제하지 않는다. 양쪽 관리자 기본 보기에서 `qa-gn1560-`로 검색하면 된다. 이전 #1564의 14개 네트워크·사용자 VM 2대·자동 라우터 2대와 QA 계정/프로젝트/도메인은 정리했다.

| 클러스터 | 유형 | 이름 | UUID | 바로 열기 |
|---|---|---|---|---|
| 31 | isolated | qa-gn1560-31-isolated | `dc162011-420a-4207-aced-c7a2a77bdd56` | [UI](http://10.10.31.10:8080/client/#/guestnetwork/dc162011-420a-4207-aced-c7a2a77bdd56?tab=public.ip.addresses) |
| 31 | shared | qa-gn1560-31-shared | `8d94e791-e423-4089-9348-f8763e5b3f5f` | [UI](http://10.10.31.10:8080/client/#/guestnetwork/8d94e791-e423-4089-9348-f8763e5b3f5f?tab=public.ip.addresses) |
| 31 | l2 | qa-gn1560-31-l2 | `bfcfb954-8ee3-49ae-979f-cbccf69c316d` | [UI](http://10.10.31.10:8080/client/#/guestnetwork/bfcfb954-8ee3-49ae-979f-cbccf69c316d?tab=details) |

## 31번 VM·주소

- VM **qa-gn1560-31-link-review**, `4e6e8d47-779b-41f6-a4de-6f9914a92c69`, Running, Isolated 기본 NIC + Shared 추가 NIC. [VM UI](http://10.10.31.10:8080/client/#/vm/4e6e8d47-779b-41f6-a4de-6f9914a92c69)
- Source NAT **10.10.31.142**, `25328461-bb0a-4b4e-8852-258ad4227d7f`: VM 셀 `—`, 클릭/포커스 대상 없음.
- Static NAT **10.10.31.141**, `b2f14a08-e535-49d6-b3a7-8b545cf9e469`: QA VM으로 연결.
- 미연결 Public IP **10.10.31.143**, `21a6e954-afa8-4bee-89c4-25c2745713b0`: VM 셀 `—`.
- Shared `10.61.31.17–22`, VLAN 3660: User/DomainRouter 각 1개 링크, 미할당 4개는 `—`. L2는 VLAN 3661이며 VM/IP 탭을 만들지 않았다.
- 자동 라우터: r-868-VM (`3f58ede4-71ca-4fc4-978b-b74c6d4d5dbb`, Running), r-871-VM (`403c4eea-91a2-4202-9aa0-9162a009edc1`, Running).
- Shared 초기 조회 오류 조사 중 시험망을 공식 API로 한 번 재구성했다. 최종 UUID는 위 표의 `8d94e791-...`이다. 예약 placeholder NIC는 정상 동작이므로 삭제하지 않는다.

| 클러스터 | 유형 | 이름 | UUID | 바로 열기 |
|---|---|---|---|---|
| 32 | isolated | qa-gn1560-32-isolated | `742cc1ad-e7e3-49e3-b4a9-49379d1edac6` | [UI](http://10.10.32.10:8080/client/#/guestnetwork/742cc1ad-e7e3-49e3-b4a9-49379d1edac6?tab=public.ip.addresses) |
| 32 | shared | qa-gn1560-32-shared | `74da0389-6849-4e0d-acaf-229740f0c149` | [UI](http://10.10.32.10:8080/client/#/guestnetwork/74da0389-6849-4e0d-acaf-229740f0c149?tab=public.ip.addresses) |
| 32 | l2 | qa-gn1560-32-l2 | `aafc454b-8caf-496a-865a-25c919ae827d` | [UI](http://10.10.32.10:8080/client/#/guestnetwork/aafc454b-8caf-496a-865a-25c919ae827d?tab=details) |

## 32번 VM·주소

- VM **qa-gn1560-32-link-review**, `ab70f2d0-d1db-4980-9a48-637fa9aa5fcd`, Running, Isolated 기본 NIC + Shared 추가 NIC. [VM UI](http://10.10.32.10:8080/client/#/vm/ab70f2d0-d1db-4980-9a48-637fa9aa5fcd)
- Source NAT **10.10.32.166**, `04de4618-4265-4bfc-9b4c-95bc88ac1c2c`: VM 셀 `—`, 클릭/포커스 대상 없음.
- Static NAT **10.10.32.164**, `5b6fd789-f97f-4d64-bbc4-1b465488a45b`: QA VM으로 연결.
- 미연결 Public IP **10.10.32.165**, `3718e606-1ab1-48db-a660-7486d8dfc172`: VM 셀 `—`.
- Shared `10.61.32.17–22`, VLAN 3660: User/DomainRouter 각 1개 링크, 미할당 4개는 `—`. L2는 VLAN 3661이며 VM/IP 탭을 만들지 않았다.
- 자동 라우터: r-358-VM (`77e8926c-eba1-4b94-b491-c34bd0e71f9b`, Running), r-359-VM (`7f62e312-c9df-4763-bdcf-ae0cbce24a2b`, Running).


검토 순서: Isolated → Public IP 주소에서 Static NAT/Source NAT/미연결 행을 비교한다. VM 링크와 Tab 키를 확인한 뒤 Shared → IP Addresses에서 사용자 VM·라우터 이동과 미할당 표시를 확인한다. 표가 좁으면 내부 가로 스크롤을 사용한다. L2의 직접 IP 탭 URL은 상세로 복귀한다.

총 **네트워크 6개·사용자 VM 2대·자동 라우터 4대**를 유지한다. 사용자 VM의 Root 볼륨은 31번 GFS2 `/mnt/glue-gfs`, 32번 Glue RBD `rbd`의 Primary 스토리지에 있다. 오브젝트 스토리지 작업은 필요하지 않았다.
