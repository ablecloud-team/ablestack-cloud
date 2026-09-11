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

# Europa 4.23 코드 통합 릴리즈 노트

이 문서는 Epic #987의 S3–S7 통합 코드에 대한 릴리즈 준비 문서다. 아직 최종 릴리즈/RC를 발행한 상태가 아니다.
Apache 대상은 4.23.0.0이며, ABLESTACK Europa의 기존 기능과 제품명을 유지한다.
최종 버전 번호·RC 산출물·설치 및 복구 판정은 S8 #999에서 확정한다.

## 추가 및 변경된 기능

| 영역 | 통합된 동작 | 사용 시 확인할 조건 |
| --- | --- | --- |
| API 키·권한 | 여러 API key pair의 이름·설명·기간·허용/거부 규칙 관리, 계정/프로젝트 권한 검사 | 기존 키와 권한 상속 유지. 자동화는 동기 registerUserKeys 응답과 keypairid를 사용한다. |
| 로그인·인증 | 도메인별 OAuth 공급자, SAML 서명 및 사용자 CA 검증, 테마별 로그인 기본 도메인 | 공급자 HTTPS endpoint·서명 키·클레임 설정과 등록된 IdP 인증서가 필요하다. 실제 IdP 로그인은 최종 환경 시험 대상이다. |
| VM·KVM | VM 수명주기·migration/import·오퍼링·자원 예약과 배치 처리 개선 | Europa FTCTL/DR·TPM/UEFI·GPU·KVDO 및 기존 배치/자원 제한 계약 유지. 실제 호스트 조합별 검증이 필요하다. |
| 스토리지·백업 | CLVM(#12617 포함), NAS 증분 백업, KBOSS, Veeam KVM 및 저장소 provider·업로드 개선 | 기존 NAS/Commvault/NetBackup/BX 등 provider와 Europa 스케줄·복구 상태를 유지한다. provider별 지원·설정 및 실제 복원 시험을 별도로 확인한다. |
| KMS | Database/PKCS#11 provider, KMS 키/HSM 프로파일, 기존 볼륨 키 전환·교체 | HSM은 별도 장치/라이브러리 구성이 필요하다. 기존 passphrase를 유지하며 KMS 선택 오류를 새 legacy 키로 대체하지 않는다. |
| Usage·Quota | Usage 집계·인덱스·요금제/활성화 규칙·잔액/크레딧 API와 화면 | quota.enable.service 설정과 역할별 API 권한에 따라 기능이 노출된다. Europa 통화 및 기존 credit/usage 값을 유지한다. |
| DNS | PowerDNS provider, DNS 영역·레코드 및 네트워크 연결 | 관리자 provider 등록, 공용 도메인 접미사, 테넌트 충돌/접근 검사 적용. 실제 DNS 전파·장애·네트워크 이벤트 연동은 최종 시험 대상이다. |
| Network Extension·VR | 외부 네트워크 provider 호출, DHCP/IPv6/VXLAN/MAC-IP hook, VPC firewall/LB soft delete, NIC 설명·속도 확장, CKS 개선 | 기존 Storage Service 처리와 기본 network throttle 10000 Mbps 유지. SystemVM 기본 OS/패키지 recipe 교체는 이번 S6/S7 변경에 포함하지 않는다. |
| UI | 프로젝트 삭제 이름 확인, 준비 완료 이미지 배포 버튼, 계정 링크, 검색 아이콘, SAML MS cookie 처리 | ABLESTACK 브랜딩·한국어·기존 Storage Service/DR/Desktop/Automation 메뉴와 역할 기반 표시 유지. |

## 테마 로그인 기본 도메인

관리자는 createGuiTheme/updateGuiTheme의 loginbasedomain과 commonnames로 접속 호스트별 기본 ACS 로그인 도메인을 설정할 수 있다.
loginbasedomain을 제공하면 commonnames도 필요하다. CSS/JSON 없이 도메인만 지정하는 테마도 생성할 수 있다.
예를 들어 기본값 tenant에 사용자가 child를 입력하면 tenant/child로 로그인한다. 입력이 없으면 tenant를 사용한다.
OAuth 공급자 조회와 로그인도 동일한 도메인을 사용하며, OAuth 입력과 비밀번호 로그인 입력을 분리한다.

테마에 값이 없으면 기존 config.json의 loginBaseDomain을 유지한다. 테마의 명시적인 빈 값은 기본값을 지운다.
테마를 바꿀 때마다 정적 설정을 다시 읽어 이전 테마의 도메인이 남지 않게 한다.
API 수정에서 loginbasedomain을 생략하면 기존 DB 값을 유지한다. 빈 값으로 지우는 경우 CSS/JSON 등 다른 유효한 테마 설정을 함께 제공한다.
이 ACS 로그인 도메인은 VM/SSVM 인증서의 DNS 이름 설정과 별개다.

## 업그레이드 준비 및 실행 순서

1. 기존 Europa 기준은 사용자 지정 014895d8f3이다. 신규 설치와 이 기준 DB, S6 완료 동일 버전 DB를 별도로 검증한다.
2. 최종 배포 전 DB·설정·인증서·암호화 키와 백업 provider 정보를 보존하고, 실제 복구 가능한 백업 및 유지보수 절차를 준비한다.
3. 검증된 최종 Actions 산출물과 해당 설정을 사용한다. 이번 S7 Snapshot 또는 로컬 개발 번들을 정식 릴리즈로 표시하지 않는다.
4. 관리 서버 시작 시 기존 업그레이드 순서와 Europa migration journal을 사용한다. 기존 Complete 단계를 수정해 재실행시키지 않는다.
5. S7의 europa-4.23-s7-v1이 gui_themes.login_base_domain을 추가하고 view를 갱신한다. 이전 단계에서 이 열이 없으면 기존 테마 view를 사용한다.
6. 중단된 S7은 Pending에서 재시도하며 DDL을 중복 적용하지 않는다. Complete 후 반복 시작은 해당 DB 단계의 전체 스키마·데이터·기록 시간을 보존한다.
7. 최종 설치의 로그인·역할·기존 데이터·키·Usage/Quota·백업 복구·네트워크/스토리지 동작은 #1025의 인수 항목으로 검증한다.

DB가 이미 4.23으로 표시되어도 추가 Europa 단계를 건너뛰면 안 된다. 마이그레이션을 건너뛰기 위해 version/journal 행을 수동으로 수정하지 않는다.
업그레이드 후 단순 패키지 다운그레이드를 DB 복구 절차로 간주하지 않는다. S8에서 최종 SHA에 맞는 백업 복원·되돌리기 절차를 확정한다.

## 제외 범위와 남은 작업

고정 299개 원본은 [inventory.tsv](inventory.tsv)에서 하나씩 판정한다. Excluded 28개는 기능 통합 완료로 세지 않으며, 각 행에 제외 근거가 있다.
별도 범위인 Europa 백업 정책 #898/#995 전체 작업을 이 통합으로 완료했다고 간주하지 않는다.

S7 이후 Pending 2개는 S2 #990 / S8 #999가 마무리한다.

- a7f9756d6267: legacy realhostip 인증서/설정 제거. 아직 반영되지 않았으며, 인증서 리소스·SystemVM 소비 경로·기존 사용자 설정 보존을 함께 검토해야 한다.
- 463f8d029470: 최종 릴리즈 버전 숫자. 현재 Snapshot/기존 UI buildVersion을 유지하고 S8의 최종 버전·산출물 확정 시 반영한다.

실물 HSM·KVM·스토리지·백업 provider·IdP·DNS/VR 시험과 운영 규모 DB 복구는 NOT_RUN이다.
자동 테스트·Docker의 실제 관리 API/MySQL·Chromium 검증은 실물 환경 시험의 통과를 의미하지 않는다.
코드 PR은 자동 검증 후 정상 병합하며, [#1025](https://github.com/ablecloud-team/ablestack-cloud/issues/1025)에 남긴 시험은 모든 코드 통합 후 수행한다.


## S8 최종 코드 확정

Maven/Docker 버전을4.23.0.0으로 확정하고 폐기된 realhostip 도메인 기본값을 독립 DB 단계로 제거했다. 사용자 지정 도메인을 보존하며 SystemVM 인증서 파일 이름과 참조를 함께 전환했다. 고정299개는 최종 판정 완료(Pending0)다. 실물 검증 및 RPM 설치/복구/릴리즈 승인은 #1025에 NOT_RUN으로 남긴다. 일반 사용자 관리자 API 탐색/Promise 정리는 #1042에서 후속 추적한다. [S8 검증](s8-verification.ko.md)과 [복구·인수 문서](s8-recovery-and-handoff.ko.md)를 참조한다.
