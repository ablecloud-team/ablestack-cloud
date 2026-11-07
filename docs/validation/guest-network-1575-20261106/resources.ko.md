<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

# 확인용 시험 자원

이전 #1568 자원과 전체 도메인 공유 시험 망은 정리했다. 아래 전용 QA 자원은 사용자 확인을 위해 유지한다. 계정 비밀번호는 기록하지 않는다.

## 31번 GFS2

| 유형 | 자원 | UUID |
| --- | --- | --- |
| Isolated | [qa-gn1575-31-isolated](http://10.10.31.10:8080/client/#/guestnetwork/34015e1d-ca40-4d05-a4de-ba26172009e1) | `34015e1d-ca40-4d05-a4de-ba26172009e1` |
| L2 | [qa-gn1575-31-l2](http://10.10.31.10:8080/client/#/guestnetwork/1589449c-d56e-4087-af12-e25fdbe39349) | `1589449c-d56e-4087-af12-e25fdbe39349` |
| Shared | [qa-gn1575-31-shared](http://10.10.31.10:8080/client/#/guestnetwork/45d3e8ae-925d-497b-963f-f99ea587d419) | `45d3e8ae-925d-497b-963f-f99ea587d419` |
| L2 | [qa-gn1575-31-project](http://10.10.31.10:8080/client/#/guestnetwork/613f205a-7e49-41a5-81a7-9c600791f68b) | `613f205a-7e49-41a5-81a7-9c600791f68b` |
| Shared | [qa-gn1575-31-shared-domain-only](http://10.10.31.10:8080/client/#/guestnetwork/d1179d24-5c84-4280-9469-985ee9f7b339) | `d1179d24-5c84-4280-9469-985ee9f7b339` |
| Shared | [qa-gn1575-31-shared-account](http://10.10.31.10:8080/client/#/guestnetwork/9a3d0f49-1357-4082-a473-6ec9c77edda0) | `9a3d0f49-1357-4082-a473-6ec9c77edda0` |
| Shared | [qa-gn1575-31-shared-project](http://10.10.31.10:8080/client/#/guestnetwork/4192b423-7e47-4270-bd43-85694ba786fd) | `4192b423-7e47-4270-bd43-85694ba786fd` |
| Isolated | [qa-gn1575-31-child-isolated](http://10.10.31.10:8080/client/#/guestnetwork/7363462e-e0ad-47ba-9edb-25eed6e085a8) | `7363462e-e0ad-47ba-9edb-25eed6e085a8` |
| VM | [qa-gn1575-31-owner-vm](http://10.10.31.10:8080/client/#/vm/f587e18a-573d-417f-8563-a1f6b5fc0cd7) | `f587e18a-573d-417f-8563-a1f6b5fc0cd7` |
| VM | [qa-gn1575-31-peer-vm](http://10.10.31.10:8080/client/#/vm/aca6699e-3ccf-4788-9b14-ad04e1bc8723) | `aca6699e-3ccf-4788-9b14-ad04e1bc8723` |
| Public IP | [시험 IP 상세](http://10.10.31.10:8080/client/#/publicip/e6978afb-a4ee-4512-8fb5-a06fe6477a83) | `e6978afb-a4ee-4512-8fb5-a06fe6477a83` |

프로젝트 `qa-gn1575-31-project`와 전용 계정·오퍼링·라우터가 연결되어 있다. 규칙은 방화벽 TCP 14575, 포트포워딩 TCP 14576, 부하분산 TCP 14577을 확인할 수 있다.

## 32번 Glue RBD

| 유형 | 자원 | UUID |
| --- | --- | --- |
| Isolated | [qa-gn1575-32-isolated](http://10.10.32.10:8080/client/#/guestnetwork/a8671ac8-edb2-403e-9950-1ce49af7597e) | `a8671ac8-edb2-403e-9950-1ce49af7597e` |
| L2 | [qa-gn1575-32-l2](http://10.10.32.10:8080/client/#/guestnetwork/d31f4831-dd41-42da-9d12-d9fabe903897) | `d31f4831-dd41-42da-9d12-d9fabe903897` |
| Shared | [qa-gn1575-32-shared](http://10.10.32.10:8080/client/#/guestnetwork/6c01f969-51ef-47fc-8ff7-443540dcb3be) | `6c01f969-51ef-47fc-8ff7-443540dcb3be` |
| L2 | [qa-gn1575-32-project](http://10.10.32.10:8080/client/#/guestnetwork/6346a006-f2f5-4b3d-9d3c-63d9c1d4cf67) | `6346a006-f2f5-4b3d-9d3c-63d9c1d4cf67` |
| Shared | [qa-gn1575-32-shared-domain-only](http://10.10.32.10:8080/client/#/guestnetwork/5b52150f-559c-4df4-8359-2e45ab878ba6) | `5b52150f-559c-4df4-8359-2e45ab878ba6` |
| Shared | [qa-gn1575-32-shared-account](http://10.10.32.10:8080/client/#/guestnetwork/9f6e6a73-a5df-41ed-91b2-19dd5a2ccebe) | `9f6e6a73-a5df-41ed-91b2-19dd5a2ccebe` |
| Shared | [qa-gn1575-32-shared-project](http://10.10.32.10:8080/client/#/guestnetwork/c2b9b132-ca97-4357-a67b-618e4718561d) | `c2b9b132-ca97-4357-a67b-618e4718561d` |
| Isolated | [qa-gn1575-32-child-isolated](http://10.10.32.10:8080/client/#/guestnetwork/14bfeb55-bb96-42f6-a1ea-e6317e9b52fd) | `14bfeb55-bb96-42f6-a1ea-e6317e9b52fd` |
| VM | [qa-gn1575-32-owner-vm](http://10.10.32.10:8080/client/#/vm/fa1eb8b5-958d-480b-a51f-ca96924bbefe) | `fa1eb8b5-958d-480b-a51f-ca96924bbefe` |
| VM | [qa-gn1575-32-peer-vm](http://10.10.32.10:8080/client/#/vm/d8f6f7e2-007d-4ee9-a7ac-28be9469d288) | `d8f6f7e2-007d-4ee9-a7ac-28be9469d288` |
| Public IP | [시험 IP 상세](http://10.10.32.10:8080/client/#/publicip/3718e606-1ab1-48db-a660-7486d8dfc172) | `3718e606-1ab1-48db-a660-7486d8dfc172` |

프로젝트 `qa-gn1575-32-project`와 전용 계정·오퍼링·라우터가 연결되어 있다. 규칙은 방화벽 TCP 14575, 포트포워딩 TCP 14576, 부하분산 TCP 14577을 확인할 수 있다.

31번 수신자 VM에는 Shared 기본 NIC와 L2 NIC를 남겼다. 32번에는 Shared 기본 NIC, L2 NIC 및 권한 재설정 뒤 남은 Isolated NIC를 보존했다. 두 곳 모두 일반 수신자에게 L2 공유가 남아 있다. 31번 Isolated에는 프로젝트 매핑이 남아 있고, 32번 Isolated는 빈 권한 목록으로 확인할 수 있다.
