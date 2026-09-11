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

# S8 최종 코드 통합 검증

이슈 #999 / S2 마감 #990 / Epic #987 / PR #1041.

## 범위와 판정

Apache 체크포인트 3166e64891fc75d4d32b66d874cff3f613b09b52부터
목표 463f8d0294702a920e8020b62ae3b67f52ae1473까지 고정299개를 유지한다.
최종 원본 판정은 Applied101 / Adapted108 / Already Satisfied62 / Excluded28 / Pending0이다.
S2 77개 전체 최종 근거는 [s2-final-review.tsv](s2-final-review.tsv)에 통합했다.
이전 s2-review.tsv 및 배치 보고서는 당시의 기록이며 현재 판정은 inventory.tsv를 따른다.

## 잔여 원본 검토

| 원본 | 판정 | Europa 적응과 근거 |
| --- | --- | --- |
| a7f9756d6267869b96a9209af58c0335835a773d | Adapted | 원본19개 변경 경로 검토. 완료된 schema-42210to42300.sql 대신 독립 S8 단계. 두 폐기 도메인만 NULL 처리. 인증서4개 내용 동일, 이름과 모든 소비 경로 변경. HTTP/HTTPS 사용자 도메인/IP fallback 회귀3 tests. |
| 463f8d0294702a920e8020b62ae3b67f52ae1473 | Adapted | 원본172개 버전 변경 검토. Europa 추가 모듈을 포함한 Maven/Docker185개 파일의 SNAPSHOT 제거와 공식 changelog 반영. UI package 4.22.0 및 제품 표시 V4.0-4.0.15는 기존 계약 유지. |

realhostip 적용 fa426a86b3, 회귀 테스트/검토 b594ba4c40, 최종 버전 파일 대조8db43943a7. 버전 확정은 정식 태그/릴리즈 게시가 아니다.
실행 코드의 4.23.0.0-SNAPSHOT 잔여0개. 개발 Dockerfile 두 곳에 남아 있던4.22 SNAPSHOT 라벨/Marvin 패키지명도4.23.0.0으로 맞췄다.
SystemVM template.version4.22.0.0은 별도 배포 템플릿 계약이며 이번 원본 version-stamping이 수정한 값이 아니므로 보존한다. realhostip 문자열은 과거 DB 이력과 S8 정리 조건에만 남는다.
인증서4개는 이전 blob과 바이트 단위 동일하고 셸5개 bash -n 통과했다.
전체 원본 집합/의존성/적용 SHA/최종 상태 검증은 아래 최종 결과에서 구분한다.

## 자동 검증 결과

- Docker Rocky9.8/JDK17에서 179개 reactor install PASS. Java source/target11 유지.
- 실제 DatabaseCreator/DatabaseUpgradeChecker로 신규·014895d8f3·S7 전체 DB 경로 PASS.
  세 경로 모두473 table/view이고 반복 전체 DDL/데이터/journal snapshot 동일.
  S7 경로는 journal의 S8 완료행1개만 추가됐으며 기존 모든 행과 시각이 보존됐다.
- [DB 결과](s8-db-results.tsv)는 전체 schema와 합성 데이터의 검증이다. 운영 DB dump 또는 실물 검증이 아니다.
- 생산 EuropaSystemVmSchemaUpgrade를 두 번씩 실행해 폐기 도메인2종·사용자 도메인2종·빈 값·NULL과 무관한 설정을 포함한18개 설정 기대값 PASS.
  fixture 원상 복원 후 전체 snapshot도 동일. S8은 DML 단계이며 새 DDL을 추가하지 않는다.
- SystemVM agent.zip에서 새 cert/key/keystore byte 일치, realhostip 파일명 잔여0개 PASS.
- 원본 validator --final: 299개 최종 상태/37의존성/19merge/적용SHA HEAD 도달성 PASS.
- 공식 backend/UI/License/Rocky9.8·9.7 RPM 결과 및 산출물 digest는 PR #1041의 최종 검증 코멘트와 체크에서 최종 HEAD로 고정한다.
  해당 필수 검증 성공과 merge-tree 일치를 확인한 뒤 병합한다. 이 문서의 로컬 성공으로 공식 CI를 대체하지 않는다.
- 코드 b594ba4c40의 Lint는 이전과 같은13개 실패 범주, 변경 경로 추가 진단0개. 문서 마감 HEAD도 동일 기준으로 확인한다.
  skipped Simulator/Coverage/Sonar를 PASS로 합산하지 않는다.

## 실물과 복구 인계

[복구 및 인수 문서](s8-recovery-and-handoff.ko.md)의 항목을 #1025에 NOT_RUN으로 남긴다.
실물 환경 미제공은 코드 PR의 Draft 또는 병합 보류 조건이 아니다.
전체 Lint의 기존 실패는 새 진단과 구분하며 skipped workflow를 PASS로 집계하지 않는다.
