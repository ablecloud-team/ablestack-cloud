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

# #1567 사용자 검토 자원

2026-11-05 검증 후 **별도 삭제 지시까지 유지**한다. 이전 #1560 자원과 이번 빈 망 삭제 시험 자원은 정리했다. 비밀번호·세션/API 키는 이 문서에 기록하지 않는다.

| 클러스터 | 유형/이름 | UUID | 검토 화면 |
|---|---|---|---|
| 31 | qa-gn1567-31-isolated | `c5867039-56f1-453b-afe8-9790f6e2f749` | [상세](http://10.10.31.10:8080/client/#/guestnetwork/c5867039-56f1-453b-afe8-9790f6e2f749) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 31 | qa-gn1567-31-shared | `2b7b778e-3ad5-4d6f-8d32-b362c539f3a8` | [상세](http://10.10.31.10:8080/client/#/guestnetwork/2b7b778e-3ad5-4d6f-8d32-b362c539f3a8) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 31 | qa-gn1567-31-l2 | `e6ed550b-c561-4372-93ad-421fdcda20a1` | [상세](http://10.10.31.10:8080/client/#/guestnetwork/e6ed550b-c561-4372-93ad-421fdcda20a1) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 31 | qa-gn1567-31-user-isolated | `f7d3cc74-79b9-4c1c-a494-82af95e2bbb4` | [상세](http://10.10.31.10:8080/client/#/guestnetwork/f7d3cc74-79b9-4c1c-a494-82af95e2bbb4) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 32 | qa-gn1567-32-isolated | `7f279b12-d465-486a-8cb8-2779db98f509` | [상세](http://10.10.32.10:8080/client/#/guestnetwork/7f279b12-d465-486a-8cb8-2779db98f509) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 32 | qa-gn1567-32-shared | `91c1c365-1c9e-4d7b-adc2-1a637b997787` | [상세](http://10.10.32.10:8080/client/#/guestnetwork/91c1c365-1c9e-4d7b-adc2-1a637b997787) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 32 | qa-gn1567-32-l2 | `c209a465-1926-4669-bcd6-09f64baa285e` | [상세](http://10.10.32.10:8080/client/#/guestnetwork/c209a465-1926-4669-bcd6-09f64baa285e) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |
| 32 | qa-gn1567-32-user-isolated | `55bf7bcd-d4bb-4589-9080-5955ae95d5ce` | [상세](http://10.10.32.10:8080/client/#/guestnetwork/55bf7bcd-d4bb-4589-9080-5955ae95d5ce) → 작업 → 네트워크 업데이트/삭제 → 고급 작업 안내 |

네트워크는 클러스터별 Root Isolated/Shared/L2와 User 소유 Isolated 각 1개, 합계 8개다. Root 소유 VM은 Isolated 기본 NIC + Shared/L2 추가 NIC, 합계 3 NIC로 Running이다. User 소유 망은 역할 검토용 빈 망이다. 자동 라우터는 클러스터별 2개다.

| 클러스터 | VM / UUID | 저장소 |
|---|---|---|
| 31 | [qa-gn1567-31-review](http://10.10.31.10:8080/client/#/vm/08267153-fd94-475f-bc86-7754ead32e40) / `08267153-fd94-475f-bc86-7754ead32e40` | GFS2 |
| 32 | [qa-gn1567-32-review](http://10.10.32.10:8080/client/#/vm/0b9bc0cc-348f-4b82-bc57-8b72074a47ac) / `0b9bc0cc-348f-4b82-bc57-8b72074a47ac` | Glue(Ceph) RBD |

역할 검토 계정은 `qa-gn1567-31-user`, `qa-gn1567-31-domainadmin` 및 32번의 같은 접두어 계정이다. 도메인은 `/qa-gn1567-31/`, `/qa-gn1567-32/`다. 총 4계정·2도메인도 유지한다. Root Admin은 네트워크 목록에서 `qa-gn1567-31`, `qa-gn1567-32`로 검색하면 각 4개를 볼 수 있다.

사용 중 네트워크의 일반 삭제를 실제 시험해 6건 모두 거절되고 망/VM이 유지됨을 확인했다. 확인창을 열고 **취소**하면 변경이 없다. 강제 API는 UI에서 실행할 수 없다.
