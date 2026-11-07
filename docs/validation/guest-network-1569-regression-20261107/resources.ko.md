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

기존 자원을 일괄 삭제하지 않았다. 이번 생성 프로브 중 VM/NIC가 없는 임시 생성망과 넓은 Shared 노출 범위의 빈 임시망만 정리했다. 최종 시험망·VM·NIC·IP·규칙·VPC·ACL·PVLAN은 사용자 확인용으로 유지한다.

| 클러스터 | 프로젝트 | 목록 포함 네트워크 | VM | NIC |
| --- | --- | --- | --- | --- |
| 31 | [qa-gn1569-31-project](http://10.10.31.10:8080/client/#/project/4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) | 27 | [qa-gn1569-31-prj-vm](http://10.10.31.10:8080/client/#/vm/625141be-7145-4557-9d8f-731500c61cb0?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) · Running · ablecube31-3 | 7 (원래 6개 보존 + 공유 확인용 1개) |
| 32 | [qa-gn1569-32-project](http://10.10.32.10:8080/client/#/project/0db180fc-4a19-4f9a-b387-00599ffa86c0) | 28 | [qa-gn1569-32-prj-vm](http://10.10.32.10:8080/client/#/vm/da7f69ed-1948-4927-b377-55beb9c4620d?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) · Running · ablecube32-2 | 7 (원래 6개 보존 + 공유 확인용 1개) |

클러스터별 목록 수는 외부에서 공유한 개인 L2 1개를 포함한다. QA 프로젝트의 네트워크 한도는 직전 #1589의 30을 유지했다. 사용자 확인 후 정리할 때 20으로 복원할 대상이다. 제한 역할은 createNetwork/updateNetwork/addNicToVirtualMachine/removeNicFromVirtualMachine 네 API만 거부한다. `readonly`는 시험 계정명이며 모든 쓰기가 차단된 역할을 뜻하지 않는다.

| 클러스터 | 자원 | 화면 | UUID / 상태 |
| --- | --- | --- | --- |
| 31 | Shared · qa-gn1569-31-reg-scope-account | [상세](http://10.10.31.10:8080/client/#/guestnetwork/fa9fbc32-7c19-403d-a7ff-cc2f145ba363) | fa9fbc32-7c19-403d-a7ff-cc2f145ba363 · Setup |
| 31 | L2 · qa-gn1569-31-reg-pvlan | [상세](http://10.10.31.10:8080/client/#/guestnetwork/189f2d99-13db-4719-a774-69aeac2bac8f) | 189f2d99-13db-4719-a774-69aeac2bac8f · Setup |
| 31 | Isolated · qa-gn1569-31-reg-tier | [상세](http://10.10.31.10:8080/client/#/guestnetwork/4a5ac294-90e7-418c-9755-b17f4a9b78f3) | 4a5ac294-90e7-418c-9755-b17f4a9b78f3 · Allocated |
| 31 | L2 · qa-gn1569-31-reg-personal-share | [상세](http://10.10.31.10:8080/client/#/guestnetwork/0bb7a305-e2c0-4fd6-af30-5f8aec0b720d) | 0bb7a305-e2c0-4fd6-af30-5f8aec0b720d · Implemented |
| 31 | Shared · qa-gn1569-31-reg-shared | [상세](http://10.10.31.10:8080/client/#/guestnetwork/776362a9-9943-48d7-aaf1-ca1455a9ae1e?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) | 776362a9-9943-48d7-aaf1-ca1455a9ae1e · Setup |
| 31 | Isolated · qa-gn1569-31-reg-nat | [상세](http://10.10.31.10:8080/client/#/guestnetwork/728ab627-0aff-4d39-a63f-3f0eae3777a2?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) | 728ab627-0aff-4d39-a63f-3f0eae3777a2 · Allocated |
| 31 | L2 · qa-gn1569-31-reg-l2 | [상세](http://10.10.31.10:8080/client/#/guestnetwork/1a7c7bc5-b487-4acb-92d7-391c0198c0dc?projectid=4dff5213-ca2c-4ee7-8b2b-16c6a0baa6fe) | 1a7c7bc5-b487-4acb-92d7-391c0198c0dc · Implemented |
| 31 | VPC · qa-gn1569-31-reg-vpc | [VPC](http://10.10.31.10:8080/client/#/vpc/876d40cb-64e6-42a8-a5d4-1122c7c0f1b8) | 876d40cb-64e6-42a8-a5d4-1122c7c0f1b8 · start=false · VM 미배포 |
| 31 | ACL · qa-gn1569-31-reg-acl | [규칙](http://10.10.31.10:8080/client/#/acllist/08e2e719-bc1b-4071-b967-6eb815707e37?tab=rules) | 08e2e719-bc1b-4071-b967-6eb815707e37 · TCP/22 Allow, Ingress, 192.0.2.156/32 |
| 31 | 추가 Public IP · 10.10.31.110 | [LB](http://10.10.31.10:8080/client/#/publicip/f9eda3b4-2655-453b-837a-28e46eb9c3f5?tab=loadbalancing) | f9eda3b4-2655-453b-837a-28e46eb9c3f5 · Static NAT 해제 상태 |
| 31 | #1590 실패 재현 LB | 위 IP의 18569 규칙 | 739c314f-a5a9-418c-bc6c-6040a77f9046 · protocol 누락 · roundrobin · Add |
| 31 | 정상 TCP 비교 LB | 위 IP의 18572 규칙 | 46b0048d-e3e9-48df-8df9-eebbcf465404 · leastconn/TCP · Active · 기존 NAT VM 1대 연결 · LbCookie 유지 |
| 31 | FW / PF | 위 IP의 방화벽·포트 포워딩 탭 | FW 18570 / PF 18571, 시험 CIDR 192.0.2.156/32 및 openfirewall=false |
| 32 | Shared · qa-gn1569-32-reg-scope-account | [상세](http://10.10.32.10:8080/client/#/guestnetwork/72d12468-4cdf-4a72-87ca-d99a5353120e) | 72d12468-4cdf-4a72-87ca-d99a5353120e · Setup |
| 32 | L2 · qa-gn1569-32-reg-pvlan | [상세](http://10.10.32.10:8080/client/#/guestnetwork/a988b4b0-dd71-4d1a-a493-0ecb5acdfa72) | a988b4b0-dd71-4d1a-a493-0ecb5acdfa72 · Setup |
| 32 | Isolated · qa-gn1569-32-reg-tier | [상세](http://10.10.32.10:8080/client/#/guestnetwork/5758eb68-7d2e-4c8f-a9be-6699990d07fa) | 5758eb68-7d2e-4c8f-a9be-6699990d07fa · Allocated |
| 32 | L2 · qa-gn1569-32-reg-personal-share | [상세](http://10.10.32.10:8080/client/#/guestnetwork/d5ebfabd-d845-4411-9266-d6d16efe7587) | d5ebfabd-d845-4411-9266-d6d16efe7587 · Implemented |
| 32 | Shared · qa-gn1569-32-reg-shared | [상세](http://10.10.32.10:8080/client/#/guestnetwork/92102e11-abf4-464f-9592-b04671c9946a?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) | 92102e11-abf4-464f-9592-b04671c9946a · Setup |
| 32 | Isolated · qa-gn1569-32-reg-nat | [상세](http://10.10.32.10:8080/client/#/guestnetwork/520b13f5-966b-4ee9-af3a-e5e6339e11ca?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) | 520b13f5-966b-4ee9-af3a-e5e6339e11ca · Allocated |
| 32 | L2 · qa-gn1569-32-reg-l2 | [상세](http://10.10.32.10:8080/client/#/guestnetwork/8e90bb7a-8fca-469b-accc-8a33c349b611?projectid=0db180fc-4a19-4f9a-b387-00599ffa86c0) | 8e90bb7a-8fca-469b-accc-8a33c349b611 · Implemented |
| 32 | VPC · qa-gn1569-32-reg-vpc | [VPC](http://10.10.32.10:8080/client/#/vpc/8cd96255-8109-41c8-812e-4f9b7107480b) | 8cd96255-8109-41c8-812e-4f9b7107480b · start=false · VM 미배포 |
| 32 | ACL · qa-gn1569-32-reg-acl | [규칙](http://10.10.32.10:8080/client/#/acllist/e67085ee-50f3-41a0-be35-181147f3dcdf?tab=rules) | e67085ee-50f3-41a0-be35-181147f3dcdf · TCP/22 Allow, Ingress, 192.0.2.156/32 |
| 32 | 추가 Public IP · 10.10.32.170 | [LB](http://10.10.32.10:8080/client/#/publicip/d670d49c-497f-46fe-940a-45c83202a1d7?tab=loadbalancing) | d670d49c-497f-46fe-940a-45c83202a1d7 · Static NAT 해제 상태 |
| 32 | #1590 실패 재현 LB | 위 IP의 18569 규칙 | 3cc2a1c8-4362-44fd-9bd7-aeddd77c6f5e · protocol 누락 · roundrobin · Add |
| 32 | 정상 TCP 비교 LB | 위 IP의 18572 규칙 | 4b4dda8c-78e2-4dc8-b241-9362f92d02ce · leastconn/TCP · Active · 기존 NAT VM 1대 연결 · LbCookie 유지 |
| 32 | FW / PF | 위 IP의 방화벽·포트 포워딩 탭 | FW 18570 / PF 18571, 시험 CIDR 192.0.2.156/32 및 openfirewall=false |

개인 L2의 프로젝트·동일 도메인 peer 공유 2행을 복원했고 VM NIC를 남겼다. 공유 회수 상태의 UI 오류는 캡처와 API 증적으로 보존했으며, 다시 재현하려면 이 전용 L2의 프로젝트 공유만 회수하면 된다. 운영 공유에는 적용하지 않는다.

기존 Shared·32 no-SourceNat의 SSH/DHCP/DNS/동일 호스트 통신 검증 자원도 유지했다. 이번에는 해당 게스트의 인터페이스 변경 권한이 없어 L2 패킷 설정을 수행하지 않았다. sudo나 QGA 정책을 완화하지 않았고 게스트/NIC 변경도 하지 않았다. 다음 패킷 검증은 권한을 갖춘 전용 VM을 라우터와 같은 호스트에 새로 준비하는 순서로 진행할 수 있다.

[최종 API/DB 대조](evidence/final-state.json) · [공유 권한 수명주기](evidence/permissions.json) · [직전 게스트 검증](../guest-network-1569-guest-20261107/README.ko.md)
