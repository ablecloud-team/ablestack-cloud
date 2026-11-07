<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# #1566 LB 연결 설정 지원·저장·적용 계약

2026-11-05. Epic #1554 / NET-12. 두 설정의 지원 범위와 오퍼링→실제 HAProxy 설정 전달을 다룬다. LB 전체 CRUD, 분배 성능, SSL, health monitor, AutoScale 완료를 의미하지 않는다.

## 제공자와 유형

| 유형·제공자 | UI 정책 | 확인 수준 |
|---|---|---|
| Isolated + Lb/VirtualRouter | 생성·복제·LB 연결 설정 수정 허용 | 31 GFS2·32 Glue RBD UI/API/DB 및 실제 HAProxy 적용 확인 |
| Isolated + VpcVirtualRouter / InternalLbVm | 같은 HAProxy 계열로 소스상 허용 | 판정 단위 시험·공통 configurator 시험. VPC/Internal LB 실환경은 이번에 구성하지 않아 실행 미검증 |
| Isolated + NetScaler 등 외부 LB | 이 HAProxy 전용 입력 제외, 제공자 미지원 이유 표시 | 판정 시험. 외부 장비 미구성·실행 미검증 |
| 일반 L2, LB 서비스 없음 | 필드 제외·전송 없음·수정 저장 불가 | L2 기존 오퍼링 조회, 생성 유형 전환, 단위 시험 |
| Shared / EIP·ELB 예외 | 이 이슈에서 지원한다고 표시하지 않음 | 기존 Shared NetScaler ELB 오퍼링의 제외 안내·저장 차단과 생성 유형 전환 확인. 실제 ELB 실행 미검증 |
| LB 제공자 미조회·미구성 | 미확인 사유 표시·수정 저장 불가 | 단위 시험. 조회 실패는 UI에서 요청 차단으로 재현·복구 확인 |

서버의 일반 API 호환성은 유지한다. UI가 지원하지 않는 제공자에 새 구현을 추가하거나 운영 망을 강제로 재설정하지 않는다. 이번 UI 실행 역할은 Root 관리자다. 전체 역할/프로젝트 조합은 #1575/#1576의 후속 행렬로 남는다.

Shared ELB 소스에는 `ElasticLoadBalancerManagerImpl:210`의 `_networkOfferingDao.findById(guestNetworkId)` 경로가 있다. 네트워크 ID로 오퍼링을 조회하는 부분은 별도 수정·실환경 검증이 필요하다. 이번 Isolated 결과를 Shared ELB 성공으로 확장하지 않는다. 소유 범위는 #1573 및 #1576이다.

## 필드·기본값·범위

| 항목 | 계약 |
|---|---|
| 최대 연결 수 응답·요청 | `maxconnections`. DB의 `concurrent_connections`는 unsigned int, API transport는 signed Java Integer이므로 실제 공통 저장 범위는 **0~2147483647 정수**다. 음수는 DB 쓰기 전에 명시 거절한다. 소수·transport overflow도 거절한다. |
| Keepalive | 요청·응답 `keepaliveenabled` Boolean. `false`, `true`, 응답 미확인을 구별한다. 조회 응답에 누락됐던 필드를 추가했다. |
| 생성 생략 | LB 서비스가 있으면 최대 연결 수는 `network.loadbalancer.haproxy.max.conn` 설정값(코드 기본4096), Keepalive는 생성 API의 기본 false. LB capability 항목을 별도로 지정하지 않아도 명시한 연결 수를 보존한다. |
| 복제 생략 | 서버 clone 계약에 따라 원본 값을 상속한다. UI는 조회된 숫자·Boolean 값을 동일하게 표시·전송한다. 미확인 Boolean을 false로 새로 만들지 않는다. |
| 수정 생략·빈 입력 | 기존 값을 유지한다. 변경한 필드만 보낸다. 숫자 0과 Boolean false는 유효 값으로 보존한다. 무변경 확인은 쓰기 API를 호출하지 않는다. |
| 0 | 실제 설치된 HAProxy 2.6.12 구문 검사 통과. 해당 버전 원문은 global.maxconn이 0인 경우 자동 산정 경로를 사용한다. 실제 용량은 자원·FD 한도의 영향을 받는다. 상한값을 운영 용량 보장으로 해석하지 않는다. |

정수의 양 끝은 전용 **미사용** 오퍼링에서만 확인했다. 큰 값을 실행 중인 라우터에 적용하지 않았다. 0 의미 근거는 [HAProxy 2.6.12 원문](https://www.haproxy.org/download/2.6/src/haproxy-2.6.12.tar.gz), `src/haproxy.c:2428`, `src/cfgparse-global.c:607` 및 [DB/API 경계 증적](31-api-boundaries.json)이다.

## 저장과 실제 적용

1. 오퍼링 생성/복제/수정 → DB 저장 및 조회 응답 확인.
2. **저장만으로 실행 중인 LB가 즉시 바뀌지 않는다.** 기존 네트워크를 자동 재시작하지 않는다.
3. 이후 LB 규칙을 적용하거나 네트워크를 명시적으로 재시작하는 경로에서 설정을 전달한다. 이번 실환경 검증은 **UI의 LB 규칙 편집·확인** 경로를 사용했다. 명시 네트워크 재시작 경로는 소스 계약만 검토했다.
4. HAProxy 설정의 `maxconn`, HTTP `httpclose` 옵션·구문 검사와 API 값을 별도로 대조한다.

| 단계 | 오퍼링 API | 실제 HAProxy(31·32 공통) |
|---|---|---|
| 초기 적용 | 257 / true | maxconn257 / `no option httpclose` |
| 오퍼링 저장 직후 | 513 / false | 기존257 / true 유지 |
| UI LB 규칙 재적용 | 513 / false | maxconn513 / `option httpclose` |
| Keepalive만 true 저장 직후 | 513 / true | 기존513 / false 유지 |
| UI LB 규칙 재적용 | 513 / true | maxconn513 / `no option httpclose` |

기존 true 분기에서 생성하던 `no option forceclose`는 설치된 HAProxy 2.6.12가 거절했다. 공통 core configurator를 현재 지원 구문으로 바꾸고 관리 서버와 6대 agent의 해당 class를 함께 반영했다. 라우터 설정 파일을 수동 편집하지 않았다.

사용자의 VLAN 스위치 미설정 안내에 따라 백엔드 VM은 라우터와 **동일 호스트**에 배치했다. 초기 HTTP 연결 확인 외에 분배 성능·다중 백엔드 분산 여부를 판정하지 않으며 추가 성능 시험은 하지 않았다.

## UI 데이터 흐름과 실패 처리

- 생성·복제·수정에서 같은 세로형 LB 필드와 지원 판정을 사용한다. 생성·복제의 기존 parent `$refs` 기반 정리 로직은 중첩 컴포넌트 필드를 삭제하므로, 지원 판정 후 LB 값만 원본 form에서 명시 추출한다. 이 경로를 실제 POST와 공통 mixin 회귀 시험으로 검증했다.
- 설정 수정은 대상 ID로 현재값 조회 → 초안 작성 → 변경값만 POST → 결과 재조회 순서다. 늦게 도착한 이전 대상 응답은 무시한다.
- 조회 실패 시 초안 보존·저장 차단, 쓰기 실패 시 입력 보존·성공 표시 금지, 명시 재조회로 복구한다. 새 값 입력 중 외부 기본값으로 덮어쓰지 않는다.
- 생성·복제는 검증 시작 전 loading을 설정하여 중복 제출을 막는다. 유효하지 않은 입력은 자동 반올림/보정하지 않고 그대로 남긴다.
- 일반 L2/Shared 및 지원하지 않는 LB 제공자에는 두 필드를 전송하지 않는다. 소스의 지원과 실환경 PASS를 별도로 기록한다.
