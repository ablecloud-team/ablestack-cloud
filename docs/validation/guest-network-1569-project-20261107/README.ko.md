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

# #1569 특정 프로젝트의 역할별 네트워크 권한 검증

후속 #1587·#1588·#1589의 수정·31/32 배포·UI 검증을 완료했다. 아래는 결함 발견 당시 기록이며 현재 결과는 [#1587 완료 보고](../guest-network-1587-20261107/README.ko.md)와 [#1588 완료 보고](../guest-network-1588-20261107/README.ko.md)를 따른다. [#1589 완료 보고](../guest-network-1589-20261107/README.ko.md)를 추가했다. 다음은 #1569의 남은 역할 통합 회귀다.

2026-11-07, 31 GFS2와 32 Glue RBD의 전용 프로젝트에서 네트워크 생성·수정·VM NIC 연결을 실제 API와 UI로 실행했다. 소유자와 일반 멤버는 양쪽에서 L2 생성·수정·NIC 연결을 완료했다. 소유자는 Isolated NAT와 자동 VLAN Shared의 UI 수정·NIC 연결도 완료했다. 관리자·도메인 관리자·사용자 정의 프로젝트 역할에서 UI 진입 또는 API 조회 결함 세 건을 발견했다. #1569는 Open으로 유지한다.

[캡처](capture-matrix.ko.md) · [실행 행렬](coverage.ko.md) · [확인용 자원](resources.ko.md) · [집계 JSON](evidence/suite-summary.json) · [Epic 63개 행](../guest-network-1569-integrated/coverage.ko.md)

## 역할별 실제 결과

| 역할 | API 생성·수정·NIC 연결 | 31 UI | 32 UI |
| --- | --- | --- | --- |
| 루트 관리자 | 10/10 허용 | 특정 프로젝트 전환 중 메타데이터 저장 용량 초과 | 전환·L2 생성/수정/NIC 연결 및 지정 VLAN Shared 수정 완료 |
| 도메인 관리자, 프로젝트 직접 비멤버 | 9/10 허용, 지정 VLAN Shared 수정 531 제한 | 프로젝트 API 탐색 531로 전환 차단 | 동일 |
| 프로젝트 소유자 Admin | 9/10 허용, 지정 VLAN Shared 수정 531 제한 | L2 생성, NAT/L2/자동 VLAN Shared 수정·NIC 연결 완료 | 동일 |
| 일반 프로젝트 멤버 Regular | 9/10 허용, 지정 VLAN Shared 수정 531 제한 | L2 생성·수정·NIC 연결 완료 | 동일 |
| 제한 프로젝트 역할 Regular | 쓰기 10/10 거부 401 | 명시적으로 허용한 조회도 401, 프로젝트 전환 실패 | 동일 |
| 같은 도메인 일반 비멤버 | 10/10 거부 432 | 해당 프로젝트 선택 불가, 직접 상세 접근 거부 | 동일 |
| 다른 도메인 일반 비멤버 | 10/10 거부 432 | 프로젝트 선택 목록에 없음 | 동일 |

숫자는 클러스터별 역할당 API 10건이다. 두 생성은 L2와 자동 VLAN Shared이며, 수정과 NIC 연결은 NAT·L2·지정 VLAN Shared·자동 VLAN Shared 각 네 건이다. Isolated 생성의 역할별 조합, 일반 멤버의 NAT/Shared UI 쓰기, 비멤버의 생성 대화상자 강제 제출은 이번 실행에 포함하지 않았다. API 거부와 UI 프로젝트 가시성을 별도로 판정했다.

지정 VLAN Shared의 specifyvlan=true 수정은 루트 전용이라는 서버 정책을 따랐다. 소유자의 UI 수정 버튼도 비활성이다. 자동 VLAN Shared specifyvlan=false에서는 관리자·도메인 관리자·소유자·일반 멤버의 생성·수정·NIC 연결이 허용됐다. 모든 Shared 수정이 관리자 전용이라는 결론으로 일반화하지 않았다.

## 발견한 결함과 해결 순서

1. [#1587 관리자 프로젝트 API 메타데이터 저장 용량 초과](https://github.com/ablecloud-team/ablestack-cloud/issues/1587)
   31의 listApis는 1110개를 반환했으나 primate__APIS 저장이 QuotaExceededError로 실패했다. 프로젝트 전환 성공을 보장할 수 없고 요청 실패가 표시됐다. 32는 1070개이며 동일 경로가 성공한 대조군이다. ProjectView와 GetInfo의 저장 형식을 통일하고 저장 실패 시 프로젝트·API 권한·라우트 상태 정합성을 보장해야 한다. 프로젝트 전환용 직렬화의 UTF-16 크기는 31의 9,211,162바이트, 32의 8,564,188바이트다. 이 크기를 브라우저 저장 한도 자체로 해석하지 않는다.
2. [#1588 도메인 관리자 API 탐색과 작업 권한 불일치](https://github.com/ablecloud-team/ablestack-cloud/issues/1588)
   같은 계정과 프로젝트 ID로 생성·수정·연결은 허용되지만 listApis(projectid)는 양쪽에서 직접 멤버가 아니라는 531을 반환했다. UI 전환의 API 탐색 권한을 실제 도메인 접근 권한과 맞춰야 한다.
3. [#1589 사용자 정의 프로젝트 역할의 조회 허용 미반영](https://github.com/ablecloud-team/ablestack-cloud/issues/1589)
   쓰기 네 API deny, list*/get*/queryAsyncJobResult allow에 정확한 listApis/listNetworks/listVirtualMachines allow까지 저장했다. 새 로그인 후에도 세 조회가 모두 401이다. 같은 계정의 프로젝트 외 listApis는 465개를 반환한다. 배포 JAR의 역할 가용성·규칙 순서·캐시 경로를 추적해 조회 허용과 쓰기 제한을 함께 복원해야 한다.

세 건을 해결한 뒤 #1569 역할 회귀를 재실행한다. 별도 [#1586 Glue 라우터 볼륨 정리](https://github.com/ablecloud-team/ablestack-cloud/issues/1586)와 L2/PVLAN 패킷·Static NAT·VPC ACL·VPN 등 기존 미완료 범위도 유지한다.

## 실행과 판정 근거

API 권한 사례는 각 70건, 합계 140건이다. 각 클러스터에서 37건 허용·33건 거부를 확인했다. 허용된 수정은 저장값, 연결은 실제 NIC와 기본 NIC 유지, 비동기 작업은 job 완료로 확인했다. NIC 연결 후 역할 간 중복을 피하도록 보조 NIC만 해제했고, 확인용으로 마지막에는 기본 Shared NIC와 프로젝트 L2 NIC를 남겼다.

프로젝트 소유자·일반 멤버가 개인 계정 소유 L2를 프로젝트 VM에 연결하도록 강제 요청한 네 사례는 530으로 거부됐고 NIC가 생기지 않았다. 마지막으로 조회 allow 규칙을 갖춘 제한 역할의 생성·수정·연결 및 세 조회를 양쪽에서 다시 검사한 12개 진단 요청도 401이었다. 이 진단과 NIC 해제는 140건 집계에 중복 포함하지 않는다.

UI는 실제 브라우저의 프로젝트 선택, 네트워크 목록 생성, 상세 수정, VM NIC 탭의 기존 네트워크 연결을 사용했다. 브라우저 작업 기록과 API 저장 상태를 연결했다. 진행 대화상자의 완료와 실제 NIC 행을 확인했으며 버튼 클릭만으로 성공 처리하지 않았다. 31 라이트·32 다크의 1440×1000 뷰포트에서 캡처했다. 이번은 권한 검증으로 UI 소스·스타일 변경이나 새 배포·빌드를 수행하지 않았다. 모바일·테마 즉시 전환 전체 회귀는 이번에 새로 실행하지 않았으며 기존 UI 검증 기록과 구분한다.

31 소유자의 첫 자동 VLAN Shared 수정은 API 시험의 동시 설명 변경과 겹쳤다. 해당 시도는 시험 간섭으로 기록하고 API 시험이 끝난 뒤 순차 재실행해 UI 저장값을 확인했다. 초기 NIC 진행 중의 중간 관찰은 최종 완료 기록으로 보완했다. 이 두 항목을 제품 결함으로 집계하지 않았다.

## 자원과 기존 운영 상태 보존

확인용 프로젝트에는 31 네트워크 15개·VM 1대, 32 네트워크 16개·VM 1대가 남아 있다. 기존 #1569 확인 자원까지 포함하면 31 네트워크 19개·VM 5대, 32 네트워크 20개·VM 6대다. 운영 네트워크 28/11개, VM 121/58대, 오퍼링 30/21개의 식별자·주요 상태 해시가 기준선과 일치했다. VPC 각 1개와 Kubernetes Public IP 규칙 집합 8/1개의 해시도 일치했다. 권한 시험에서 생성한 프로젝트 계정·역할·오퍼링과 네트워크·VM을 삭제하지 않았다. 자격 증명·세션·사용자 데이터는 게시 자료에서 제외했다.

소스 기준은 Epic 브랜치 dhslove/epic-1554-guest-networks의 0985c15bb30e81ca4aae2945eb8e68117bd9cf22이다. 이번 커밋은 검증 문서와 캡처만 추가한다. Epic #1554와 Draft PR #1577을 유지한다.
