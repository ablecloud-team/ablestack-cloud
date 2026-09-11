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

# Europa 4.23 최종 코드 인수와 복구 절차

부모 Epic #987 / 코드 통합 #999 / 실물 실행 및 릴리즈 승인 #1025.

## 적용 범위

Apache 고정 299개를 Europa에 통합한다. Maven/Docker 소프트웨어 버전은 4.23.0.0이다.
Europa 제품 표시 V4.0-4.0.15와 UI package 4.22.0은 기존 제품 계약을 보존한다.
Apache 버전 확정은 ABLESTACK 정식 릴리즈 태그, 배포 또는 실물 검증 승인을 뜻하지 않는다.
Rocky 9.8 amd64/JDK17이 기본이며 Java source/target 11을 유지한다.
Rocky 9.7은 별도 Actions 호환 패키징만 수행한다.

## 실물 실행 인계

아래 항목은 모두 **NOT_RUN**이다. #1025의 S3~S7 상세 시나리오를 그대로 이어서 실행한다.

| 항목 | 검증 내용 |
| --- | --- |
| 최종 Actions RPM 설치/업그레이드 | 검증 SHA와 artifact digest 고정, 신규 설치 및 014895d8f3 운영 DB의 복사본에서 업그레이드, 서비스 재시작 |
| SystemVM 인증서 전환 | 기존/재생성 CPVM·SSVM에서 systemvm.keystore 참조, CA 신뢰, 사용자 인증서 재전달, 콘솔과 HTTPS 업로드/다운로드 |
| 도메인 기본값 제거 | 폐기된 두 기본값만 NULL, 사용자 도메인 보존, 인증서 SAN과 DNS 일치, 미설정 HTTPS의 IP SAN 신뢰 |
| FTCTL/DR/SharedFS/KMS | #1025의 장애/복구, 키 수명주기, 암호화 디스크, 파일시스템 및 데이터 보존 시나리오 |
| 외부 제품 연동 | Wall 토큰/알림, Keycloak·Glue·Wall 계정 삭제와 재시도, Veeam 백업/복원 |
| UI/IdP | 실제 TLS·OAuth/IdP redirect·SAML·테마 도메인, 실물 리소스의 삭제와 배포 |
| 복구 실행 | 아래 절차의 실제 백업 복원 및 전체 서비스/에이전트 정합성 검증 |

저장소의 기본 인증서 4개는 업스트림처럼 이름만 변경했다. 인증서 발급·갱신 또는 실환경 TLS 신뢰 검증을 수행한 것은 아니다.
S7 소프트웨어 브라우저에서 프로젝트 삭제 요청은 접수되었으나 외부 계정 정리 Connection refused로 완료되지 않았다.
Wall 토큰 미설정 530과 일반 사용자 관리자 API 탐색 432도 알려진 제약이다.
권한 상승은 관측되지 않았으며 불필요한 탐색/콘솔 Promise 정리는 후속 코드 이슈 #1042로 추적한다.

## 실행 전 백업

1. 최종 병합 SHA, 모든 설치 패키지 버전·Actions run·artifact digest, SystemVM 템플릿, 설정 변경 목록을 기록한다.
2. 관리/Usage 서비스와 외부 쓰기 작업을 중지하고 진행 중 VM·스토리지 작업을 확인한다.
3. cloud/cloud_usage 전체 DB와 schema migration journal을 일관된 시점으로 백업한다. 실제 자격증명은 비밀 저장소에서 공급한다.
4. 관리 설정, CA/키스토어, KMS 키/외부 HSM 연결, 사용자 인증서, 에이전트 설정 및 FTCTL/DR 메타데이터를 보안 백업한다.
5. 운영과 분리한 환경에서 백업을 복원하여 DB·키·암호화 볼륨 연결과 서비스 기동을 검증한다. 완료 전 운영 업그레이드를 승인하지 않는다.

## 실패와 재시도

Europa는 S3 및 S4/S5A/S5B/S5C/S6/S7/S8 독립 단계와 journal로 동일 버전 업그레이드를 추적한다.
S8은 폐기된 도메인 기본값만 정리하며 이미 완료된 과거 단계는 수정하지 않는다.
오류 발생 시 서비스를 중지하고 로그·DB 버전·journal 상태·문제 SQL을 보존한다.
원인을 수정한 동일/후속 호환 코드로 미완료 단계를 재시도한다. journal을 수동 삭제하거나 Complete로 조작하지 않는다.

## 이전 코드로 복구

DB 업그레이드 이후에는 Git revert나 RPM downgrade만으로 복구하지 않는다.
모든 관리/Usage 쓰기를 중지하고 업그레이드 직전의 일관된 DB 백업과 대응 소프트웨어·설정·키를 함께 복원한다.
여러 관리 노드가 서로 다른 DB 계약으로 접속하지 않도록 모두 같은 버전으로 맞춘다.
그 후 SystemVM/에이전트 호환, 외부 연동 상태, 신규/진행 중 작업, FTCTL/DR 소유권 및 볼륨 데이터 정합성을 확인한다.
DB 복원은 스토리지 데이터나 외부 시스템 변경을 되돌리지 않으므로 별도 대조와 복구가 필요하다.
실제 백업·복원 명령은 운영 배치 구조와 비밀 공급 방식에 맞춰 #1025 실행 기록에 확정한다.

이번 작업은 이 절차의 문서화와 격리 DB 자동 검증까지이며 실제 운영 복구 성공을 주장하지 않는다.
