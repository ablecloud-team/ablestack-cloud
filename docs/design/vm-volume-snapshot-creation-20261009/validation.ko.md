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

# 실제 검증 계획 및 증거 기준

## 검증 환경과 기본 규칙

사용자가 지정한 31번 GFS2 SharedMountPoint(CLUSTER), 32번 Glue(Ceph) krbd RBD(CLUSTER)를 각각 필수 E2E 환경으로 고정한다. 32번은 Europa 관리/Agent 업그레이드를 완료했다. [환경 준비 결과](environment-20261009.ko.md)와 [실행 manifest](evidence/fixture-manifest.json)를 따른다. 실제 파괴적/장애 테스트 전에 최신 inventory와 전용 fixture를 확보하고 실행 범위를 확정한다. 운영 VM의 ROOT를 분리하여 원본을 만들지 않는다. 일반 Cloud 생성 검증과 FTCTL/DR 단계별 승인 검증은 별도 흐름이다.

UI에서 생성 요청을 제출하고 API 요청/async job, Cloud DB, agent/management 로그, 실제 host domain/root device, guest 결과를 같은 test case ID로 묶는다. 실패 시 해당 케이스를 중단하고 증거·원본을 보존한다. 원인과 복구를 확인한 뒤 새 run ID로 다시 시작하며, 중간 보정 후 계속한 결과를 전체 체인 PASS로 세지 않는다. FT/DR manual-block 검증은 별도 사용자 승인 흐름을 따른다.

## 케이스 행렬

| ID | 케이스 | 통과 조건 |
|---|---|---|
| SRC-01 | 볼륨/snapshot 조회 0개·배열 생략 | 정상 빈 안내; JS 오류 없음; 생성 불가 |
| SRC-02 | 25개/30개 이상, page 1·2·3, pagesize 10·20 | 중복/누락 없음, 정확한 count; 선택 UUID 유지 |
| SRC-03 | 이름/UUID 검색·Zone·소유자·프로젝트·arch 전환 | 서버 범위와 일치; 늦은 응답 및 이전 선택값 혼입 없음 |
| SRC-04 | 기존 화면의 volumeid/snapshotid 사전 선택 링크 | 해당 원본만 조회/선택; 삭제/권한불가 원본은 안내 |
| SRC-05 | Creating/BackingUp/Error/Destroyed snapshot | 선택/제출 차단; provider별 복구 가능한 상태만 허용 |
| SRC-06 | 미연결 DATADISK, template 누락, 지원 CLUSTER와 다른 cluster/HOST 범위 볼륨 | 적격성/차단 사유 일치; 일반 데이터 디스크를 OS로 오인하지 않음 |
| VOL-01 | 전용 detached ROOT, KVM, 지정 CLUSTER GFS2/RBD 범위, Linux BIOS | 동일 volume UUID/device0/ROOT/new instance, 디스크 부팅 및 fixture 파일 확인 |
| VOL-02 | 같은 조건의 Windows UEFI | 원본 boot mode/bus와 실제 XML 일치, 게스트 부팅/fixture 확인 |
| VOL-03 | startvm=false → 상세 화면 시작 | 최초 Stopped/root mapping, 시작 뒤 guest 정상 |
| VOL-04 | attached/non-Ready/삭제/변경된 source, owner mismatch | 서버 재검증 거절; 원본·다른 VM 변경 없음 |
| VOL-05 | 동시 생성 2회/클릭 중복/응답 유실 | 최대 한 VM으로 편입; 자동 deploy 재송신 없음; 결과불명 재조회 |
| VOL-06 | VM 할당 전 실패/편입 후 시작 실패 | 원본 데이터 삭제 없음; 연결·타입·device 또는 복구 필요 상태가 설명과 일치 |
| SNP-01 | ROOT snapshot, KVM, Linux BIOS, startvm=true/false | 새 root UUID, snapshot 유지, 실제 시점의 파일/OS 부팅, 정지 생성 후 시작 |
| SNP-02 | Windows UEFI, startvm=true/false | 원본 snapshot 시점 boot 환경, 게스트 부팅; 기존 SID/IP 등 관측 |
| SNP-03 | snapshot 생성 후 원본 디스크 데이터 변경 | 새 VM 데이터가 snapshot 시점과 일치; 현재 원본 데이터가 복제되지 않음 |
| SNP-04 | 원본 VM 삭제/expunge, 원본 volume 제거 fixture | 유효 snapshot metadata로 복구 또는 명시적 불충분 오류; NPE/불가능한 templateId 안내 없음 |
| SNP-05 | 같은 snapshot으로 새 VM 2개(서로 별도 요청) | 각각 독립 ROOT UUID; snapshot 유지; isolated network에서 identity 충돌 평가 |
| SNP-06 | root-only/data snapshot/VM snapshot 구분 | root-only 범위 명확; data-only와 memory snapshot을 VM 생성 원본으로 오인하지 않음 |
| SNP-07 | 지정 Ceph krbd RBD/GFS2 SharedMountPoint provider | capability로 지원 여부 판정; 지원 조합만 복구 PASS, unsupported는 근거 있는 차단 |
| BOOT-01 | BIOS↔UEFI, root bus, metadata 누락, Secure Boot/vTPM 의존 | 검증된 상속 또는 명시적 차단; 잔존 ISO boot marker 없음 |
| CAP-01 | 오퍼링 tags/local/encryption/IOPS/placement 불일치·용량 부족 | allocation 전 또는 시작 전 명시적 오류; 원본 소유권/용량 counter 손상 없음 |
| CAP-02 | snapshot logical size와 physical usage가 다른 fixture | root size는 logical bytes 기준; 용량 증가/부족·자동 배치 근거 확인 |
| JOB-01 | restore 실패·agent/management 재시작·timeout·화면 재진입 | job/entity 연계; 실패/확인 필요 구분; 재시도 및 잔여 객체 범위 설명 |
| UI-01 | template→ISO(추가 ISO)→volume→snapshot→template | 원본·OS/boot·root size·ISO·userdata·tpm·validation 상태 초기화/상속 정확 |
| UI-02 | 라이트/다크, 1680/1366/390px, 키보드/스크롤 | 선택/차단/요약/확인/진행/오류 모두 접근 가능, 가로 overflow 없음 |
| REG-01 | 기존 template/ISO 단일·복수 ISO, startvm=true/false | 기존 생성/부팅·추가 ISO·디스크/스토리지 동작 회귀 없음 |

초기 필수 성공 행렬은 볼륨/스냅샷 × Linux BIOS/Windows UEFI × startvm=true/false의 8개를 환경별로 실행한 총 16개다. GFS2와 Ceph krbd 양쪽의 대표 성공 결과가 필요하다. CLUSTER ROOT 편입 지원(#1337, P0)을 먼저 구현한다. 지원 스토리지 유형에 대한 실제 배치/복구 증거를 추가한다. 특정 조합이 미지원이면 PASS 수에 포함하지 않고 지원 범위와 차단을 따로 보고한다.

## PASS 증거

- **생성 전:** source UUID/owner/zone/state/template/boot profile, snapshot creation time, logical size, storage scope, fixture checksum, 기존 VM/volume inventory와 quota.
- **생성 요청:** 생성 화면/요약/확인 캡처, 정제한 요청 파라미터, sourcekind별 정확히 하나의 ID, startvm와 offering/network. API keys/session cookie/암호는 수집 파일에서 제거.
- **작업:** async job ID와 entity ID, 완료/오류/시각, 원본 편입 또는 새 ROOT UUID, 새로 만든 자원 목록. jobid 접수만으로 PASS 금지.
- **Cloud/host:** VM state/volume instance_id/type/device_id, storage placement, 실제 libvirt XML/domain state, disk image/RBD mapping, 부팅 순서·버스.
- **guest:** 콘솔 또는 QGA/OS 접속에서 부팅 완료, filesystem/fixture 파일, snapshot 시점 데이터, 서비스·고정 IP/identity 관측. Running만으로 guest PASS 금지.
- **원본 무결성:** snapshot 유지/삭제 안됨, volume reuse는 원래 UUID 유지 및 데이터 파일 보존, 원래 VM/다른 볼륨 inventory 불변. 부팅 후 정상 OS 쓰기로 전체 디스크 hash가 달라질 수 있으므로 fixture 파일 checksum과 의도한 변경을 구분.
- **실패/정리:** source를 제외한 생성 소유 객체에 한정, 잔여 job/domain/volume/usage counter 상태, 재시도 결과, 테스트 자원 보존/삭제 여부.

## 이번 조사에서 완료한 것

- 최신 source SHA 고정 및 위 두 경로/제약 조사.
- 현재 목록 메서드의 로컬 fake API 재현 6개 관측.
- 31번 실제 생성 폼에서 볼륨/스냅샷 전환 및 후보/요약 표시 관측.
- 개선 UI 설계안의 로컬 상호작용/화면 검증(별도 `evidence/mockup-verification.json`).

실제 VM 생성, 부팅, 원본 checksum, provider restore, 실패 주입, 변경 Maven 빌드, 전체 Cloud 빌드, UI 배포는 이번 설계 단계에서 미실행이다.

## 환경 준비 단계 추가 확인

32번 Europa 관리/Agent 업그레이드, runtime/API/UI, VM·볼륨 보존, GFS2 공유 접근과 Ceph krbd I/O·기본 snapshot/clone을 확인했다. Windows 기준 이미지 Ready와 전용 L2 설정을 확보했다. 이는 제품의 볼륨·스냅샷 생성 E2E PASS가 아니다. 전용 seed/ROOT/Cloud snapshot과 guest checksum은 실제 검증 착수 시 만들며, 아직 존재한다고 보고하지 않는다.
