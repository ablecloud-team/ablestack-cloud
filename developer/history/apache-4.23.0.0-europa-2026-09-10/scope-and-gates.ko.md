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

# 지원 범위·실행 게이트

## 확정 검증 출발점

사용자 확인(2026-09-10): **현재 Europa `014895d8f3dc2f062f379b51ee62d36a0adae88a`로 진행**.

필수 DB 시나리오는 (1) 빈 전용 DB 신규 설치, (2) 위 SHA의 데이터가 있는 DB 복제본 → 최종 후보 업그레이드, (3) 업그레이드 후 반복 관리 서버 재시작/멱등성, (4) 부분 실패·복구/재시도다. 기존 테스트 DB를 무조건 정상이라고 가정하지 않고 S2에서 원래 오류와 새 회귀를 분리한다. 운영 데이터/기존 Docker 볼륨을 초기화하지 않는다.

과거 모든 Apache 버전이나 별도 Diplo 운영 DB에서 직접 업그레이드 가능하다고 약속하지 않는다. 추가 출발점은 별도 범위 결정으로 추가한다. source에 있는 과거 upgrade chain 변경은 inventory에서 누락하거나 자동 제외하지 않는다.

## 동일 버전 migration 위험

- 기준 Europa POM은 이미 `4.23.0.0-SNAPSHOT`, `Upgrade42210to42300`의 목표는 `4.23.0.0`이다.
- 기준 `DatabaseUpgradeChecker`는 최신 version 행을 삭제하여 동일 버전 업그레이드를 재실행하고 `schema-Europa-Before/After.sql`을 사용한다. 기본 Apache의 version 비교/upgrade 체인과 다르다.
- 기준 ScriptRunner는 `(conn, false, false)`인데 목표 Apache는 `(conn, false, true)`다. SQL 오류 중단·기존 오류 노출·재실행 안전성을 S4에서 함께 검증한다.
- 새 SQL을 과거 upgrade 파일에 추가하거나 POM 버전을 바꾸기만 하면 기존 DB에도 안전하게 반영된다고 가정하지 않는다. migration 적용/완료 조건·부분 실패 복구·키 중복 방지를 정해야 한다.
- `api_keypair`와 기존 암호화 secret migration, KMS/Usage 빈 의존성, Quota/usage_volume 인덱스, Europa DR 인덱스 #978을 교차 검증한다.

## 제품/환경 범위

| 영역 | 이번 작업 기준 |
|---|---|
| 기본 OS·도구 | Rocky Linux 9.8 linux/amd64, JDK17, Maven3.9.10, Node14.21.3/npm6, Python3.10, MySQL8.0 |
| 변경 반영 | 고정 299개 전부 검토. 기능/보안 수정 자동 제외 금지. 신규 provider도 source/API/DB/UI 영향을 검토한다. |
| Europa 유지 | 브랜딩·한국어/테마, KVM/Ceph RBD/SharedMountPoint qcow2, FTCTL/DR, SharedFS/Storage Service, 기존 noredist/VMware API 계약 |
| 외부 연동 검증 | KMS/PowerDNS/Network Extension/KBOSS/Veeam/ONTAP/LINSTOR 등은 기능 소유 이슈가 지원 조건·시험 환경·증거 확보. 환경 미확보는 PASS가 아니라 Blocked. |
| 추가 플랫폼 | 타 OS/새 하이퍼바이저 전체에 대한 신규 지원 선언은 하지 않는다. 해당 upstream 변경은 적응/기존 지원 영향/제외를 기록한다. |
| 공식 산출물 | 최종 SHA의 Rocky9.8/amd64 Actions RC·설치/업그레이드 증거. 로컬 Docker 테스트를 공식 산출물로 대체하지 않는다. |

## 실행 게이트와 다음 순서

1. S1 추적 문서 PR을 검토·병합하고 #989의 완료 근거를 연결한다. source 행의 최종 판정은 별도로 진행한다.
2. S2(#990)에서 `baseline_ready`: 현재 SHA의 실패 목록·RAT/DB 문제 구분·Rocky9.8 경로·기준 테스트를 확보한다. S2 전체 소스/merge 완료와 다른 게이트다.
3. S3(#991)의 보안/인증을 우선하고, 기능 DB·UI·선행 변경을 dependencies.tsv와 원본 부모 그래프에 맞춰 함께 처리한다.
4. S4(#992)의 공통 DB/자원 규약, S5C(#996)의 KMS 기반을 필요한 시점에 먼저 제공한다. 단계 번호가 API/스키마 의존성보다 우선하지 않는다.
5. S5A(#993), S5B(#994), S6(#997), S7(#998)을 기능 단위 PR로 통합한다. DNS는 최초 기능과 tenant/URL 보안 후속을, API key pair는 동기 API 복귀와 ACL 후속을 함께 검증한다.
6. S8(#999)는 모든 원본 행 최종 판정과 최종 SHA 통합 게이트를 마감한다. 원본에 없는 모든-Zone 업로드 가용 Zone 선택 보완은 #994에 포함하고 별도 적응 변경으로 기록한다.

다른 이슈가 닫히지 않았다는 이유만으로 착수를 막지 않고 구체적 선행 **게이트/커밋/계약**을 확인한다. 최종 DONE에는 담당 소스의 최종 판정·병합·검증이 모두 필요하다.

## 현재 완료와 미완료

- S1: PR #1000 병합과 #989 종료. 고정299개 집합/부모, 280개 근거, merge19개 분석, 단계·의존성·출발점 확정 완료.
- S2: PR #1002 병합으로 baseline_ready 완료. 기준/후보 전체 빌드·테스트, RAT269개 헤더 수정, 실제 DB 오류 비교, Rocky9.8 Actions RPM/환경/의존성/체크섬/실행 classpath 검증 완료. 상세 결과와 skip/실패는 [S2 보고](s2-verification.ko.md)를 참조한다.
- S3: 구현 PR #1009에서 직접 배정66개와 S2 연계6개를 최종 판정했다. [S3 보고](s3-verification.ko.md)의 제품 SHA/Actions/DB·인증 검증 범위를 인수한다.
- 원본 최종 판정: S2 47개 확정/30개 Pending, 전체299개 중113개 확정/186개 Pending. S2 전체 DONE 및 Epic 완료가 아니다.
- 다음 작업: S4 #992. 공통 DB 실패는 S4 #992, 기능·merge 공동 검증은 각 소유 단계, 실제 서비스/최종 RC 설치·업그레이드·복구는 S8 #999에서 완료한다.
- S8의 최종 version stamping은 S2의 공동 마감 항목이다. S2 전체 DONE을 stamping 착수의 선행 조건으로 두지 않는다.
