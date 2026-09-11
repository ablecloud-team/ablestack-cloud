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

# S6: DNS·Network Extension·VR/SystemVM·VPC·CKS 통합

부모 Epic #987, 기능 #997, 코드 PR [#1037](https://github.com/ablecloud-team/ablestack-cloud/pull/1037).
시작 코드는 e4e1abf28d4561d94fcbd37e201f7a877c6c6d9b이며 사용자 지정 DB 출발점 014895d8f3dc2f062f379b51ee62d36a0adae88a를 유지한다.
Apache 범위는 3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473 (4.23.0.0)이다.

## 원본과 공유 merge 판정

직접 32개는 Applied 17 / Adapted 13 / Already Satisfied 2다.
PowerDNS 최초 기능과 #13946 테넌트 충돌, #13821 URL 검증을 함께 반영했다.
Network Extension, VPC 방화벽·규칙 soft delete, DHCP/IPv6/VXLAN/MAC-IP hook, NIC 설명·네트워크 오퍼링·CKS 변경을 포함한다.
NSX #12833은 기준 코드와 회귀 테스트에 이미 존재한다. VNF #13423의 두 원본 중 후자는 동일 변경이므로 중복 적용하지 않는다.

S2 소유 후속 13742921d1ec (#13608)은 Firewall 서비스로 forVpc=true를 추론하던 경로를 제거한다.
e2012133599a (#13848)는 Network Extension README 표를 반영하고 S5B의 ONTAP 표와 함께 원본 전체를 완료했다.
[원본별 검토 44행](s6-review.tsv)에 직접32·후속2·공유 merge10의 적용 SHA와 최종 검토 SHA를 기록했다.

[공유 merge 12개](s6-merge-review.tsv)는 S1 remerge 원문 해시를 대조하고 해결 코드·유입 원본을 함께 확인했다.
10개는 완료했고 fb5e24fa0868은 S7 8e933b735e21, b3b9caddc191은 S7 a951ac61d090이 남아 Pending을 유지한다.
67b849f3efd1은 Git 재생성 중 synthetic blob 부재가 있어 S1에서 저장한 원본136174바이트와 고정 SHA256을 검증했다.
버전 숫자를 이전 4.22 릴리즈로 되돌리지 않으며 기존 S3/S4/S5의 인증·업로드·DRS·VM·스토리지 해결을 보존했다.

고정 299개 최종 판정은 Applied 97 / Adapted 102 / Already Satisfied 60 / Excluded 28 / Pending 12다.
잔여는 S7 8개와 S2 4개(공유 merge2, realhostip 제거 a7f9756d6267, 최종 버전463f8d029470)다.
원본을 임의로 범위 밖 처리하거나 공유 merge의 side source만으로 완료를 추론하지 않았다.

## Europa 적응과 보존 계약

- 기존 StorageService 등록·provider 매핑·네트워크 처리와 FTCTL/DR·KVDO·KMS 호출을 보존하고 DNS/Network Extension을 추가했다.
  기존 UI의 Storage Service, DR/Desktop/Automation 및 NIC link-state 경로와 한국어/다국어 키도 유지했다.
- VPN 공용 폼에 DH22/23/24/31을 추가했다. AES256/SHA256/Curve25519는 설정상 허용될 때만 기본 선택한다.
  제외된 알고리즘은 허용 목록으로 대체하고 기존 gateway 편집값은 그대로 둔다. upstream의 curve25519 공백 불일치도 보완했다.
- 동적 network throttle 기능을 반영하며 Europa 기본10000 Mbps를 유지한다. unsigned INT DB 확장으로 높은 속도를 수용한다.
- DHCP lease 파일을 원자 교체할 때 원래 소유자·권한을 보존하고 실패 시 남은 임시 파일을 정리한다.
  IPv6 응답 트래픽·중복 VPC radvd, VXLAN 모드·MAC/IP hook도 통합했다.
- VPC 방화벽·soft delete·public IP 선택을 반영하고 Network Extension 후속 provider 응답과 offering 분류를 최종 상태로 맞췄다.
  CKS KVM offering scaling은 기존 권한·컴퓨트 검사를 거치는 경로에 연결한다.
- S6는 SystemVM OS/패키지 제작 recipe를 변경하지 않는다. 기본 OS 교체나 기존 Storage Service 런타임 마이그레이션을 포함하지 않는다.
  실제 배포 이미지와 provider/스토리지 트래픽 호환성은 최종 실물 검증 대상으로 기록했다.

## 동일 버전 DB 업그레이드

새 europa-4.23-s6-v1 단계로 신규 DNS 테이블4개, MAC 재사용·NIC 설명·provider 상세/기능 컬럼,
속도 INT 확장과 firewall/LB soft delete·unique index·VPC 참조 nullable 변경을 적용한다.
기존 Complete S4/S5A/S5B/S5C 단계는 수정하지 않는다.
이전 단계의 view 재생성은 DNS 테이블 생성 전까지 DNS view3개를 건너뛰며, S6 DDL 이후 최종 view를 생성한다.
기존 KMS 이전 volume_view 분기도 유지한다.

| 실제 MySQL 8 경로 | 테이블·뷰 | version 행 | journal 행 | 재실행 결과 |
| --- | ---: | ---: | ---: | --- |
| 신규 설치 | 473 | 52 | 14 | 전체 schema/data 동일 |
| 014895d8f3 기존 DB | 473 | 51 | 5 | 전체 schema/data 동일 |
| S5C 완료 동일 버전 DB | 473 | 52 | 14 | 전체 schema/data 동일 |

S5C의 기존 행·version·Complete journal 시간은 보존되고 새 S6 journal만 추가된다.
DDL 24개 각각의 커밋 직후 실패를 주입해 Pending→재시도→Complete→재실행을 확인했다.
기존 network/firewall/LB/provider 데이터, 이전 단계 시간과 높은 속도100000 값을 확인했다.
[DB 결과](s6-db-results.tsv)와 [재현 fixture](s6-fixtures/README.ko.md)에 근거를 기록했다.

## 자동·소프트웨어 검증

- 모든 소스·Git·의존성·실행은 Rocky 9.8 linux/amd64 Docker에서 수행했다.
  JDK 17·Maven 3.9.10·Node 14/npm 6·Python 3.10·MySQL 8을 유지하며 기존 개발 DB/볼륨은 초기화하지 않았다.
- developer/systemvm/simulator/noredist 전체 compile/install 성공. 전체 Java 결과는 PR #1037의 최종 검사 기록을 참조한다.
- UI 31 suite / 354개 테스트 및 전체 UI lint 통과. 추가 VPN 테스트 포맷 오류는 수정하고 재검증했다.
- NetworkExtensionScriptTest 3개는 실제 shell 프로세스를 실행해 JSON 원문 전달, exit code/응답 실패 처리와 임시 파일 삭제를 확인한다.
  문자열의 shell 기호·따옴표·한글도 인수로 안전하게 전달된다.
- 실제 조립된 Management를 일회용 MySQL에 연결하고 HTTP 모의 PowerDNS 서버로 provider 등록·수정·삭제,
  public suffix 존, 일반 사용자 권한, 다른 계정의 동일/하위 존 충돌 및 레코드 CRUD를 검증했다.
  loopback/link-local/비 HTTP URL 거부와 응답의 API 키 생략도 확인했다.
  Europa 단일 로그인 정책을 유지하며 계정 전환 시 재로그인했고 async 작업 완료를 기다렸다.
- DHCP·주소·config·netfilter 집중 Python 10개는 통과했다. 전체 TestCs는 변경 전25개·후28개 중 같은 라우팅 실패2개가 남는다.
  TestCsRoute.test_add_defaultroute, TestCsRoute.test_defaultroute_exists의 기존 실패를 분리했으며 전체 Python PASS로 표시하지 않는다.
- 원본 추적 검증기는 299개 SHA·280개 일반 원본·19개 merge·37개 의존성·10개 작업 연결을 통과했다.
  공식 backend/UI/License/Rocky 9.8·9.7 산출물의 최종 SHA와 링크는 PR #1037 완료 기록에 남긴다.
  전체 Lint의 기존 실패 범주는 기준과 비교하고 S6 경로의 추가 진단을 해소한다. skipped 검사는 PASS로 계산하지 않는다.

## 실물 인수와 다음 작업

실물 시나리오는 [#1025의 S6 인수](https://github.com/ablecloud-team/ablestack-cloud/issues/1025#issuecomment-5630084938)에 모두 NOT_RUN으로 기록했다.
운영 PowerDNS·VR 재시작/DHCP/IPv6·VPC firewall/LB·외부 provider·VPN/VXLAN·NSX/Netris·CKS 트래픽은 실행하지 않았다.
HTTP 모의 서버와 소프트웨어 자동 검증을 실물 성공으로 대체하지 않는다.
사용자 확정에 따라 정상 코드 병합으로 S6를 완료하고, 모든 기능 병합 후 S8 #999에서 최종 SHA의 실물 검증을 수행한다.
다음 코드 작업은 S7 #998이며 [후속 계약](s6-dependencies.tsv)과 Pending12개를 인수한다.
