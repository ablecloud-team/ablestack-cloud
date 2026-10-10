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

# 오토스케일 VM 그룹 검토·테스트·프로덕션 보완 계획

검토일: 2026-10-10. 기준 소스: `b274443dadefd9d802f42d136ef04fc85b1c1a25` (최신 upstream/ablestack-europa). 현재 단계는 **현황 검토 및 Epic/하위 이슈 계획 등록**이며 구현·배포·운영 인증은 아직 수행하지 않았다.

## Epic 및 하위 이슈 추적

Epic: [#1346](https://github.com/ablecloud-team/ablestack-cloud/issues/1346). 하위 이슈 9개는 모두 Open이며 GitHub의 실제 부모/하위 이슈 및 blocked-by 관계를 연결했다. 이 문서는 현재 이슈 범위를 기준으로 구현·검증 결과를 갱신하는 계획이다.

| 순서/키 | 이슈 | 우선순위 | 범위 | 직접 선행 이슈 | 상태 |
|---|---|---|---|---|---|
| AS-01 | [#1347](https://github.com/ablecloud-team/ablestack-cloud/issues/1347) | P0 | 생성 사전 검증·입력·권한 | 없음 | Open / 계획 |
| AS-02 | [#1348](https://github.com/ablecloud-team/ablestack-cloud/issues/1348) | P0 | 다단계 생성·비동기 종료·실패 보상 | [#1347](https://github.com/ablecloud-team/ablestack-cloud/issues/1347) | Open / 계획 |
| AS-04 | [#1350](https://github.com/ablecloud-team/ablestack-cloud/issues/1350) | P0 | 동시성·중지 경쟁·재시작 복구 | [#1348](https://github.com/ablecloud-team/ablestack-cloud/issues/1348) | Open / 계획 |
| AS-03 | [#1349](https://github.com/ablecloud-team/ablestack-cloud/issues/1349) | P1 | 카운터·기간·쿨다운·정책 평가 | [#1347](https://github.com/ablecloud-team/ablestack-cloud/issues/1347) | Open / 계획 |
| AS-05 | [#1351](https://github.com/ablecloud-team/ablestack-cloud/issues/1351) | P0 | VM/LB 수명주기·드레인·삭제 보상 | [#1349](https://github.com/ablecloud-team/ablestack-cloud/issues/1349), [#1350](https://github.com/ablecloud-team/ablestack-cloud/issues/1350) | Open / 계획 |
| AS-06 | [#1352](https://github.com/ablecloud-team/ablestack-cloud/issues/1352) | P1 | 정책·프로필·예약 변경 계약 | [#1349](https://github.com/ablecloud-team/ablestack-cloud/issues/1349), [#1350](https://github.com/ablecloud-team/ablestack-cloud/issues/1350) | Open / 계획 |
| AS-07 | [#1353](https://github.com/ablecloud-team/ablestack-cloud/issues/1353) | P1 | 멤버·최근 평가·실패 운영 가시성 | [#1348](https://github.com/ablecloud-team/ablestack-cloud/issues/1348), [#1349](https://github.com/ablecloud-team/ablestack-cloud/issues/1349), [#1351](https://github.com/ablecloud-team/ablestack-cloud/issues/1351) | Open / 계획 |
| AS-09 | [#1355](https://github.com/ablecloud-team/ablestack-cloud/issues/1355) | P1 | 마지막 구현 단계: 현재 Mold VM UI 표준 통합 | [#1352](https://github.com/ablecloud-team/ablestack-cloud/issues/1352), [#1353](https://github.com/ablecloud-team/ablestack-cloud/issues/1353) | Open / 계획 |
| AS-08 | [#1354](https://github.com/ablecloud-team/ablestack-cloud/issues/1354) | P1 | GFS2/krbd 최종 UI E2E·프로덕션 판정 | [#1355](https://github.com/ablecloud-team/ablestack-cloud/issues/1355) | Open / 계획 |

직접 선행 관계 13개로 전이 선행 작업을 포함한 전체 순서를 관리한다. 실제 작업은 아래 단일 이슈 절차를 따르며 AS-08 최종 기능 검증은 AS-09 UI 통합 이후, 장시간 운영 확인은 모든 기능 검증 통과 이후 수행한다. 각 이슈에 문제 근거·개선 방향·검증 범위·완료 체크리스트를 기록했다.

## 진행 절차 변경 (2026-10-10 사용자 지시)

- 한 번에 이슈 하나만 구현·빌드·배포·해당 범위 UI 검증을 진행한다. 다음 이슈의 구현이나 검증을 병행 착수하지 않는다.
- 이슈 완료 보고에는 완료 범위, 빌드/배포/실제 UI 증거, 남은 제한, 다음 진행 대상 이슈를 기록한다. 완료 보고 후 사용자 지시를 기다리고 다음 이슈를 자동 착수하지 않는다.
- 장시간 운영 확인은 모든 기능 구현·기능 검증·최종 UI 통합 및 통합 기능 검증을 완료한 뒤 #1354의 마지막 단계에서만 수행한다. 앞선 개별 이슈의 완료 조건에 장시간 운영을 요구하지 않는다.
- 장시간 단계는 이미 검증한 기능의 안정성 관찰 및 잔여 자원·인원 범위·반복 오류 확인을 수행한다. 장애 주입·경쟁·재시작·반복 확장/축소 기능 테스트는 그 전에 완료한다. 제안 24시간의 실제 관찰 기간은 최종 실행 계획에 명시한다.
- 우선순위와 선행 관계에 따른 권장 단일 진행 순서는 **#1347 → #1348 → #1350 → #1349 → #1351 → #1352 → #1353 → #1355 → #1354**다. #1351(P0)은 #1349의 정책 평가 검증이 필요하므로 이를 먼저 완료한다.
- 현재 첫 진행 대상은 #1347이며, 해당 이슈 완료 후 다음 후보는 #1348이다. 이번 절차 반영 단계에서는 기능 구현을 시작하지 않았다.

## 실제 확인 범위

- 31번 실제 Mold UI에서 한국어/다크 모드 목록, 저장하지 않은 생성 폼, 네트워크·카운터 조회, 임계값 0/-1 입력을 검토했다. 그룹 생성·기존 VM/LB 변경·삭제·호스트 재시작은 수행하지 않았다.
- 31/32 관리자 기본 API 조회에서 그룹 0개, 각각 라우팅 호스트 3/3 Up, CLUSTER 풀 Up. 31 SharedMountPoint, 32 RBD다. 기존 승인 테스트 환경은 각각 GFS2/krbd이며 다음 실검증에서 실제 디스크 경로/드라이버와 pool 선택을 다시 대조한다.
- 31 API 전체 목록의 카운터는 20개이고 선택한 VirtualRouter 네트워크에서는 대표 카운터 5개가 보인다. API 응답 wrapper 차이를 정상화해서 집계했다. VM·네트워크·프로젝트 목록의 조회 범위를 클러스터 전체 자원 수로 확대하지 않는다.
- 관리자 일반 UI 보기에서 네트워크 5개 중 1개가 오토스케일을 지원하지만 해당 선택 네트워크의 LB 후보는 0개다. listall API에는 다른 소유자/프로젝트 범위의 네트워크/LB가 존재하므로 이를 바로 전용 테스트 자원으로 사용하지 않는다.
- 그룹이 없어 상세 탭 변경/자동 확장·축소/삭제/스케줄의 실제 동작은 미검증이다. VM 상세 화면과 소스에서 현재 ResourceLayout/요약/탭/작업 표준을 확인했다.
- 기존 서버/스키마/API/KVM의 오토스케일 테스트는 존재한다. `ui/tests`의 autoscale/AutoScale 검색 결과는 0건이다. 기존 테스트를 실행하여 통과했다고 보고하지 않는다.

## 기능 경로와 소유권

Mold UI → Cloud API → AutoScaleManagerImpl → 상태/프로필/정책/통계/멤버 DAO → VM 수명주기 및 LB 관리 → KVM Agent·VirtualRouter. UI가 호스트나 VR에 직접 명령하거나 통계를 수집하는 설계는 사용하지 않는다.

| 영역 | 현재 기능 | 프로덕션 검증 포인트 |
|---|---|---|
| 그룹 | 생성/조회/이름·min/max·interval 수정/활성·비활성/cleanup 삭제 | 상태·동시성·용량·작업 실패·자원 보상 |
| VM 프로필 | 템플릿/고정 오퍼링/Zone/삭제 유예/UserData/SSH/네트워크/디스크/affinity | 기존/향후 VM 적용 범위·권한·한글·단위 |
| 정책/조건 | 확장·축소/기간/쿨다운/카운터/연산자/임계값 | AND/복수 정책·확장 우선·데이터 신선도·5개 연산자 |
| 수집/실행 | Host CPU/Memory, VR 네트워크/LB 연결, 주기적 확장·축소 | KVM 수집 설정·결측·단위·min/max·LB 서비스 |
| 예약 | resource schedule의 min/max 변경 | 시간대·DST·중복 시각·권한·중지/삭제·경쟁 |
| 운영 UI | 목록/상세/프로필/LB/정책/스케줄/이벤트/코멘트·VM 관련 링크 | 멤버 상태·최근 평가·실패·복구·공통 표준 |

## 확인된 문제와 결함 후보

`UI 재현`과 `소스 확인`을 분리한다. 소스에서 확인한 위험을 실제 운영 장애로 기록하지 않는다.

| ID | 증거 수준 | 내용 | 담당 | 소스 |
|---|---|---|---|---|
| OBS-01 | UI 재현 | 생성 화면 초기화 TypeError: 빈 listUserData 목록의 첫 항목 params 접근 | AS-01 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/ui/src/views/compute/CreateAutoScaleVmGroup.vue#L2277) |
| OBS-02 | UI + 서버 계약 확인 | 비활성 동적 오퍼링이 기본 선택됨. 서버는 동적 오퍼링을 거부 | AS-01 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/server/src/main/java/com/cloud/network/as/AutoScaleManagerImpl.java#L467) |
| OBS-03 | UI 재현 | 음수 -1을 오류 표시 후에도 조건 표에 추가. 서버는 음수 거부. 문자열 0은 이번 UI에서 정상 추가됨 | AS-01 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/ui/src/views/compute/CreateAutoScaleVmGroup.vue#L2170) |
| OBS-04 | 소스 확인 / 장애 미재현 | 프로필·조건·정책 생성 실패에서 Promise 미종료, poll 통신 실패/기한 처리 누락 | AS-02 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/ui/src/views/compute/CreateAutoScaleVmGroup.vue#L2300) |
| OBS-05 | 소스 확인 / 경쟁 미재현 | 상태 이전값 조회와 갱신이 별도 작업. 분산 동시성/중지 경쟁 검증 필요 | AS-04 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/engine/schema/src/main/java/com/cloud/network/as/dao/AutoScaleVmGroupDaoImpl.java#L81) |
| OBS-06 | 소스 확인 / 실동작 영향 미재현 | duration 초→ms에 1024배 사용, interval 초/ms 혼용 | AS-03 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/server/src/main/java/com/cloud/network/as/AutoScaleManagerImpl.java#L2896) |
| OBS-07 | 소스 확인 / 실동작 영향 미재현 | 축소 후 확장 정책만 쿨다운 갱신하고 첫 정책에서 종료 | AS-03 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/server/src/main/java/com/cloud/network/as/AutoScaleManagerImpl.java#L2201) |
| OBS-08 | 소스 확인 / 장애 미재현 | 축소는 VM 삭제 전에 매핑 제거, cleanup 그룹 삭제는 destroy 결과 미확인 | AS-05 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/server/src/main/java/com/cloud/network/as/AutoScaleManagerImpl.java#L2195) |
| OBS-09 | 소스 확인 / 상세 UI 미검증 | 상세 정책 연산자는 확장 GT/축소 LT만 노출. 생성/API 5개와 불일치 | AS-06 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/ui/src/views/compute/AutoScaleUpPolicyTab.vue#L174) |
| OBS-10 | UI + 소스 확인 | 상태 추가/Quiet/사용가능한 VMs/Yes·No 및 오타. 기본 902px 폭에서 컬럼 과도한 줄바꿈 | AS-09 | [근거](https://github.com/ablecloud-team/ablestack-cloud/blob/b274443dadefd9d802f42d136ef04fc85b1c1a25/ui/public/locales/ko_KR.json#L389) |

## 실행 순서와 이슈 관리

1. AS-01 사전 조건과 입력/권한을 보완하고 AS-02에서 생성 실패를 종료·추적한다.
2. AS-04 경쟁/재시작, AS-03 수집/정책 정확성, AS-05 VM/LB 실패 보상을 순서대로 각각 구현·장애 재현한다.
3. AS-06 프로필/정책/예약 계약, AS-07 운영 조회/상태를 정리한다.
4. 기능 검증 결과를 바탕으로 **마지막 구현 단계 AS-09에서 현재 Mold VM UI 표준을 통합**한다.
5. AS-08은 모든 기능 및 AS-09 완료 후 최종 UI 통합 기능 E2E를 수행한다. 그 결과가 모두 통과한 뒤 마지막으로 장시간 운영 상태를 확인하고 프로덕션 판정을 수행한다.

각 이슈는 준비 → 구현 → 집중 테스트 → 변경 모듈 빌드 → 테스트 배포 → UI 검증 → PR/완료 보고 순으로 기존 이슈 본문/댓글을 갱신한다. 미재현 항목은 먼저 재현하거나 계약 테스트로 위험을 입증하고, 확인된 문제를 보완한다. 범위 밖 기능이나 미보유 provider를 검증 완료로 확대하지 않는다.

## 검증 환경과 준비

- 필수 대표: 31 KVM + GFS2 + VirtualRouter 격리 네트워크. 스토리지 회귀: 32 KVM + krbd. VPC VirtualRouter는 별도 전용 계층/LB로 기능 검증한다.
- NetScaler/Netris/VMware/XenServer는 이번 KVM 환경의 실제 E2E PASS 범위에 넣지 않는다. API/provider 적격성·지원 노출과 제한을 확인하고 다른 환경 검증 필요 여부를 명시한다.
- 전용 `AS-PROD-<cluster>-<case>` 애플리케이션 템플릿, 고정 오퍼링, 네트워크, 빈 LB, 테스트 계정/프로젝트를 준비한다. 기존 네트워크/LB 규칙은 검증용으로 전용 여부가 확인되지 않으면 사용하지 않는다.
- 대표 그룹은 min=1/max=3, 기본 평가 간격 30초·정책 duration 60초로 시작한다. 쿨다운/삭제 유예는 제품 기본값 및 축소한 검증값을 각각 시험한다. 실제 CPU/RAM/ROOT/DATA 전체 quota와 풀 용량을 먼저 확인한다.
- 템플릿은 HTTP 서비스가 자동 시작되고 응답에 VM 식별자를 제공하도록 준비한다. Cloud VM Running, 멤버/LB 매핑 일치, 실제 LB HTTP 응답을 별도 판정한다. 기본 OS 템플릿의 설치/애플리케이션 준비를 시스템이 보장하는 것으로 표현하지 않는다.
- CPU/메모리 수집의 Agent 설정·virtio/balloon 조건, VR 상태/버전 및 카운터 지원을 확인한다. 수집 불능을 0%로 처리하지 않는다.
- 일반 사용자/제한 역할/프로젝트 컨텍스트를 준비하되 기존 승인과 대상 범위를 확인하고 역할별 API/UI를 검증한다. 기존 계정·VM·서비스의 기준 상태와 사용량을 보존한다.

## 테스트 계획 (60개)

모든 행은 다음 구현 단계의 **계획/미수행**이다. 위 UI 관찰은 현황 증거이며 이 전체 테스트의 PASS를 뜻하지 않는다. 각 사례는 UI 입력/명령 또는 자동 조정 결과를 UI에서 확인하고 API/DB/Agent/VR/libvirt·애플리케이션 트래픽으로 입증한다.

| ID | 실행/조건 | 합격 기준 |
|---|---|---|
| CRE-01 | 정상 고정 오퍼링+앱 템플릿+전용 빈 LB로 생성 | 그룹/프로필/정책·min VM·LB 서비스가 일치 |
| CRE-02 | UserData 없음/삭제/권한 없음/빈 API 목록 | 콘솔 TypeError 없이 빈 상태 또는 설명·재시도 제공 |
| CRE-03 | 동적/삭제/권한 없는 오퍼링·미준비 템플릿 | 부적격 기본 선택 없음, UI/API 일관된 차단 |
| CRE-04 | 임계값 숫자/문자열 0, -1, 소수, 빈 값, 상한 | 0 허용, 잘못된 값은 조건/자원 생성 전 거부 |
| CRE-05 | min/max 0·음수·min>max·정수 상한 | 1<=min<=max 계약과 API/UI 경계 일치 |
| CRE-06 | L2/Basic/비지원 provider/다중 NIC·기본망 변경 | 지원 조합만 허용하고 명확한 한국어 사유 표시 |
| CRE-07 | 기존 VM이 붙은 LB/이미 그룹 연결/동시 동일 LB 생성 | 기존 자원 보존 및 단일 그룹 연결 |
| CRE-08 | 다른 계정/도메인/프로젝트의 프로필·정책·LB 조합 | 부당 조회/생성/변경 차단 |
| CRE-09 | 일반 사용자·제한 역할·API 일부 미허용 | UI 제어와 직접 API 권한 결과 일치 |
| CRE-10 | 전용 VPC 계층+VPC VR 및 프로젝트 생성 | 그룹/VM/네트워크/LB 소유자와 기본 NIC 일치 |
| ASY-01 | 프로필 생성 API/job 실패 | 단계 실패 종료, UI 조작 복귀, 불필요한 다음 단계 없음 |
| ASY-02 | 조건 생성 단계 실패 | 앞 단계 자원 추적 및 안전한 보상 |
| ASY-03 | 확장/축소 정책 생성 단계 각각 실패 | 부분 생성 및 공유 자원 구분, 보상 가능 |
| ASY-04 | 그룹 생성/VR 설정 실패 | 프로필/정책/조건·LB 상태 일관된 보존/정리 |
| ASY-05 | jobstatus 0→1/2 및 동기 응답 | 올바른 종료·결과·한국어 오류 |
| ASY-06 | poll 통신 실패/시간 초과/세션 만료 | 무한 대기·타이머 누수 없음, 안전한 조회 재시도 |
| ASY-07 | 연속 클릭/뒤로가기/다른 메뉴 이동 | 중복 생성 및 화면 종료 뒤 잘못된 갱신 없음 |
| ASY-08 | 생성 응답 유실 후 재조회/새로고침 | 한 작업·한 그룹·한 세트의 자원, 맹목적 POST 재전송 없음 |
| ASY-09 | 부분 생성 후 복구/정리 실패 | 식별 가능한 잔여 자원과 후속 동작 제공 |
| ASY-10 | 공유 정책·조건·기존 LB 포함 보상 | 이번 작업 소유 자원만 보상, 다른 그룹 무변경 |
| MET-01 | 단일/복수 VM CPU 부하 상승·하락 | 실측값과 그룹 평가값/수집 시각 일치 |
| MET-02 | 메모리 수집 가능/불가/부분 VM 누락 | 측정 조건과 미수집 표시, 잘못된 0 축소 없음 |
| MET-03 | VR 네트워크 송/수신과 그룹 외 트래픽 | 단위·평균 분모·공유 트래픽 범위 설명 및 값 일치 |
| MET-04 | LB 연결 수·VR 재시작·카운터 감소 | 카운터 초기화/결측이 잘못된 확장·축소를 유발하지 않음 |
| MET-05 | GT/GE/LT/LE/EQ 및 임계값 정확한 경계 | 5개 연산자의 서버·생성·상세 UI 일치 |
| MET-06 | 다중 조건 AND·여러 정책·동시 확장/축소 만족 | 정해진 우선순위대로 단일 동작 |
| MET-07 | duration 60초 전/정각/후 및 interval 경계 | 초/ms 1000 단위 일관성, 조기 평가/확장 없음 |
| MET-08 | 확장·축소 쿨다운과 다중 정책 | 각 정책 및 그룹 계약에 따른 진동 방지 |
| MET-09 | 오래된/비활성/더미/부족한 샘플 | 불확실한 값으로 작업하지 않고 UI에 원인 표시 |
| MET-10 | 시간 상한/오버플로·poll보다 짧은 duration | 범위 거부 또는 명시적 동작, 음수 기간 없음 |
| LIF-01 |min=1/max=3 최초 수렴·최대 확장 · GFS2/krbd 각각 10회 왕복 반복| 실제/준비 중 VM 포함 계약대로 인원 제한 |
| LIF-02 | CPU 상승으로 1→2→3, 추가 부하 | max 초과 VM/ROOT/LB 생성 없음 |
| LIF-03 | 부하 하락으로 3→2→1 | min 미만 축소 없음 |
| LIF-04 | VM 시작 실패·quota·스토리지 부족 | 오류 멤버 추적, 자원 누수 없음, 실패 가시화 |
| LIF-05 | LB 등록 실패/VR 불능 | VM/그룹/LB 부분 상태 추적 및 안전한 보상 |
| LIF-06 | 장기 HTTP 연결 상태에서 축소/유예 | LB 제외·드레인·유예·서비스 지속과 삭제 결과 확인 |
| LIF-07 | LB 제외 또는 VM destroy 실패 | 미삭제 VM의 그룹 추적 보존, 재시도 가능 |
| LIF-08 | cleanup=false/true 그룹 삭제 및 destroy 실패 | 보존/정리 계약 일치, 실패를 완전 성공으로 표시하지 않음 |
| LIF-09 | 공유 프로필/조건/정책 및 다른 그룹 존재 | 관련 없는 그룹/VM/LB/스케줄 보존 |
| LIF-10 | 관리 중인 VM 수동 중지/재시작/삭제 보호 | UI/API 상태 제한 일치, 운영 중지 절차 명확 |
| OPS-01 | 활성/비활성 반복 및 멱등 요청 | 중지 상태에서 새로운 자동 확장·축소 없음 |
| OPS-02 | SCALING 중 disable/변경/삭제 경쟁 | 정의한 진행 작업 처리, 오래된 finally가 사용자 상태를 덮어쓰지 않음 |
| OPS-03 | 동일 그룹 동시 scale-up/down·스케줄·주기 평가 | 한 작업 소유권, max/min 및 LB 매핑 일관 |
| OPS-04 | 관리 프로세스 재시작/가능 시 다중 관리 노드 | SCALING·모니터 작업 재조정, 중복 VM 없음 |
| OPS-05 | 예약 시간대/DST/시작·종료·동시 시각/min-max | 정확한 실행/중복 처리와 UI 이벤트, 비활성/삭제 계약 일치 |
| OPS-06 | 프로필 템플릿/오퍼링/SSH/UserData/디스크/네트워크/affinity 변경 | 허용 상태·권한·향후 VM 적용 범위 일치 |
| OPS-07 | 정책·조건 추가/편집/삭제와 모든 연산자 | 검증·공유 의존성·기존 값 보존 |
| OPS-08 | 프로젝트/사용자 컨텍스트 전환 및 quota 경계 | 프로젝트 id·소유권·한도 일관, 이전 응답 혼입 없음 |
| OPS-09 | 최근 평가/실패/이벤트/작업 id·실제 LB HTTP 응답 | UI·API·런타임·서비스의 서로 다른 성공 기준 명확 |
| OPS-10 | 모든 구현·기능/UI 검증 통과 후 마지막 운영 상태 관찰 (제안 24시간) | 추가 기능 시험 없이 인원 범위·잔여 자원·반복 오류·메모리/스레드/통계 누수 확인. 최종 운영 확인 결과 기록 |
| UI-01 | 공통 목록·검색·필터·정렬·페이지·프로젝트 | 현재 Mold VM 패턴 유지 및 값/집계 의미 정확 |
| UI-02 | ResourceLayout 요약·멤버/정책/프로필/LB/스케줄 탭 | 표준 공통 컴포넌트, 정보 출처 및 링크 정확 |
| UI-03 | 주 버튼/아이콘 순서·상태/권한/작업 중 제어 | 주 동작 앞, 보조 아이콘 오른쪽 뒤, 비활성 사유 표시 |
| UI-04 | 탭/검색 유지·부분 새로고침·응답 순서 역전 | 깜빡임/화면 초기화/이전 데이터 덮어쓰기 없음 |
| UI-05 | 빈 목록/목록 실패/지연·부분 실패·복구 안내 | 서로 다른 상태와 안전한 동작 제공 |
| UI-06 | 생성 최종 요약 및 단계별 진행/실패/응답 유실 | 다단계 상태·자원 식별과 결과가 실제 작업과 일치 |
| UI-07 | 한국어 라벨·숫자 단위·이름/설명·오류·긴 문구 | 깨짐/오타/오역 없음, ASCII 그룹명 제한 안내 |
| UI-08 | 라이트/다크 텍스트·오류·선택·비활성·표·모달 | 일반 텍스트 4.5:1 목표, 컨트롤/큰 글자 3:1 및 비색상 단서 |
| UI-09 | 1440/1024/768/390px·키보드·초점·스크롤 | 잘림/겹침/주 동작 유실 없음, 표 접근 가능 |
| UI-10 | 최종 배포 자산/서비스/HTTP200·VM 메뉴 회귀 | 실제 배포본에서 위 UI 검증 완료, 공통 VM 동작 보존 |

## 현재 Mold UI에 적용할 최종 구조

공통 ResourceView/ResourceLayout/ListResourceTable/DetailsTab/EventsTab/AnnotationsTab/ResourceSchedules를 우선 재사용한다. 새 API가 필요한 관측 정보는 먼저 AS-07에서 권한과 출처를 정의한다. 제공되지 않는 최근 평가/앱 준비 상태를 프론트엔드가 만들어 표시하지 않는다.

| 화면 | 최종 설계 |
|---|---|
| 목록 | 이름 · 상태 · 멤버(현재/최소/최대) · 네트워크/LB · 최근 평가/작업 · 계정/프로젝트. 포트/공인 IP는 묶음 보조 정보. ‘그룹 추가’ 주 동작 및 오른쪽 보조 아이콘 |
| 상세 왼쪽 | 그룹 이름/상태/아이디, 인원과 전환 중·오류 구분, 네트워크/LB, 템플릿/고정 오퍼링, 평가 간격/쿨다운, 계정/프로젝트, 마지막 갱신 |
| 상세 오른쪽 | 개요 · 멤버 VM · 확장/축소 정책 · VM 프로필 · LB · 스케줄 · 이벤트 · 코멘트. 관련 VM 링크와 동일 id 기반 값 |
| 생성 | 기존 Mold 마법사에서 적격성 이유/유효한 기본 선택/정책 단위/최종 확인. 생성 단계·실패 단계·기존/신규 자원·재시도/정리 안내 |
| 상태 | 활성 · 조정 중 · 비활성 · 생성 중 · 삭제 중을 실제 API 상태와 매핑. 추정하는 ‘정상’ 배지 사용 금지. 수집 없음/오래됨/실패는 별도 표시 |

## 완료/프로덕션 판정 및 배포

- P0 전부 및 필수 기능/최종 UI 테스트 통과, 고아 자원/권한 우회/인원 범위 이탈/실패 은폐 0건. 지원 환경·미검증 provider·관찰 기간을 명시한다.
- 수정한 Maven 모듈은 WSL ext4 소스에서 집중 테스트·패키징·필수 Checkstyle/RAT를 수행한다. UI 집중 테스트/린트/프로덕션 번들을 검증한다. 전체 Cloud 빌드/RPM은 명시 요청이 있을 때만 Actions로 수행하고 그 전에는 전체 CI 성공으로 보고하지 않는다.
- 관리 JAR/필요 Agent 모듈과 UI는 검증한 동일 소스로 빌드한다. UI는 `/usr/share/cloudstack-management/webapp` 정적 자산만 갱신하며 WEB-INF/META-INF/config와 rollback 백업을 보존한다. 전후 서비스/HTTP200·hash·실제 브라우저 경로를 확인한다.
- Epic은 UI 통합 및 최종 E2E까지 완료한 뒤 닫는다. 이번 단계는 계획 완료이며 Epic/하위 이슈는 Open으로 관리한다.

## 참고 근거

[Apache CloudStack 4.23 공식 오토스케일 문서](https://docs.cloudstack.apache.org/en/latest/adminguide/autoscale_with_virtual_router.html)는 앱이 준비된 템플릿·지원 네트워크·빈 LB 규칙, 정책의 기간·쿨다운·연산자 및 예약 동작을 설명한다. 문서의 동작을 이 fork의 실제 코드/환경에 그대로 보장된 것으로 간주하지 않고 위 테스트로 대조한다.

접근성 검증 목표는 [W3C WCAG 2.2 텍스트 대비 설명](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)과 [비텍스트 대비 설명](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)을 참고한다. 비활성 컴포넌트는 WCAG 대비 의무의 예외이나 본 제품에서는 값과 비활성 사유의 읽기 가능성을 별도 검토한다.

권한은 프로젝트의 SECURITY.md/THREAT_MODEL.md를 참고하여 회귀 검증 항목으로 다룬다. 이번 검토에서 권한 우회가 재현되었다는 의미는 아니다.
