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

# S5B: 스토리지·증분 NAS 백업·provider·템플릿 업로드 통합

부모 Epic #987, 기능 #994, 코드 PR [#1035](https://github.com/ablecloud-team/ablestack-cloud/pull/1035). Europa 시작 코드는 `394157cdf2e3aa501edbcc3dff523f66e472d12e`, 사용자 지정 DB 출발점은 `014895d8f3dc2f062f379b51ee62d36a0adae88a`이다. Apache 범위는 기존299개 추적표와 같은 `3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473` (`4.23.0.0`)이다.

## 코드 통합과 완료 기준

직접 S5B 원본47개를 검토했다. CLVM Apache #12617과 migration/ISO 후속2개, NAS 증분 기능과 후속 수정, KBOSS와 후속5개, Veeam KVM 및 EL9 dirty-context, ONTAP/LINSTOR/FlashArray/Adaptive, snapshot timeout, secondary storage 복제와 업로드를 함께 반영했다. 원본별 검토와 실제 적용 SHA는 `s5b-review.tsv`에서 관리한다. 직접47개는 Applied26 / Adapted20 / Already Satisfied1이며, S2 일반1개와 독립 해결/side source를 검토한 공유 merge6개를 더해 이번 판정은54개(Applied27 / Adapted20 / Already Satisfied7)다. 전체299개는 Applied77 / Adapted86 / Already Satisfied49 / Excluded28 / Pending59, S2 잔여는16개다.

사용자 확정에 따라 코드 검토·자동 검증·정상 PR 병합으로 #994를 완료한다. 실물 KVM/Ceph/CLVM/NAS·외부 제품 연동 시험은 [#1025](https://github.com/ablecloud-team/ablestack-cloud/issues/1025)에 인수하며 모든 코드 병합 후 S8 #999에서 실행한다. 실물 미실행을 PASS로 표시하거나 코드 PR을 Draft로 남기는 조건으로 삼지 않는다.

## Europa 적응 내용

- 기존 NAS/Commvault/NetBackup/BX/기타 provider를 보존한다. 공통 backup 호출은 `(vm, quiesce, isolated, scheduleId)`로 합성하고 기존2/3인수 호출을 연결한다. KBOSS가 제거한 upstream schedule 인수 때문에 Europa 증분 스케줄 식별자를 잃지 않도록 했다. 기존 ABLESTACK backup 스크립트와 #995 정책 계약은 유지한다.
- NetBackup EXTERNAL interval과 영속 restore 상태·재시작 복구를 유지한다. NAS chain 삭제의 resource accounting을 provider가 수행하는 경우 manager가 중복 차감하지 않는다. S4 CheckedReservation과 일반 VM 자원 제한, S3 비밀값 암호화·API key/사용자/프로젝트 ACL을 보존한다.
- Veeam control service는 명시적으로 구성한 서비스 계정을 사용한다. 계정 API 검사에 사용자/프로젝트 ACL을 추가하고 예외가 나도 기존 CallContext를 복구한다. 일반 destroy API의 `isForced()`는 계속 false이며, 내부 복구 경로의 새 옵션이 일반 API 권한을 우회하지 않는다. 기존 fast-clone/SharedFS/DR 보호는 유지한다.
- 이미지 전송 registry에서 요청 시작과 unregister를 원자적으로 관리한다. 진행 중인 전송을 같은 ID 등록으로 교체하지 않으며 unregister 시작 뒤에는 새 요청이 오래된 설정을 재사용할 수 없다. 활성 요청이 끝나면 정리하고 다음 등록을 허용한다.
- NAS 증분은 각 디스크의 부모 파일을 사용한다. manager의 UUID 목록을 우선하며 LINSTOR by-res/기존 raw DRBD 경로를 같은 파일명으로 연결한다. 정지 raw DRBD에 qcow bitmap을 추가하지 않는다. 복구 시 QemuImg metadata 실패는 안전하게 실패시키고, 평탄화/rsync는 timeout과 argv를 사용한다. 실패 후 guest thaw와 paused VM resume/정리 결과를 확인했다.
- VM 시작 인터페이스는 기존 FTCTL/DR의4/6인수 호출과7번째 `isExplicitHost` 의미를 유지한다. quick restore는 별도의 명시적 인수로만 전달하고 기존 호출은 false로 연결한다. 반환 타입/동일 시그니처의 bool 의미가 바뀌는 문제를 회귀 시험으로 검증한다.
- CLVM/CLVM_NG adaptor·lock transfer·lightweight migration·same-host ISO를 합성한다. 스토리지별 provider 선택, RBD parent/thin, FTCTL host 소유 progress/hangctl 보호, 기존 Glue/KVDO/TPM/UEFI와 다중 ISO/ConfigDrive slot을 보존한다.
- LINSTOR API token과 TLS 옵션은 대상 pool까지 전달한다. TLS 검증은 기본 활성화하고 UI token을 마스킹하며 agent의 auth.json fallback을 유지한다. SharedMountPoint/LINSTOR 암호화 변경은 포함하되 전체 KMS 정책·관리 기능은 S5C #996가 소유한다.
- all-zone 로컬 업로드는 첫 Zone의 저장소·용량·쓰기 가능 여부·SSVM/public 주소가 부적합하면 다음 후보를 탐색한다. 가용 Zone 하나에만 업로드를 배정하고 명시적 단일 Zone 요청은 다른 Zone으로 확대하지 않는다. SSVM 인증서 재시도 UI는 getAPI 인증과 서명/만료/metadata/x-host 헤더를 유지한다.
- template replica cap 조회는 예약이 아닌 현재 active copy 검사다. 동시 배치가 일시적으로 cap을 초과하면 완료 callback/periodic sync가 lock 아래 excess Ready 사본을 정리한다. 사용 중/미완료 사본은 삭제 후보가 아니며 최소 cap만큼 Ready 사본을 남긴다. 실제 동시 복제와 다른 Zone 배포는 #1025에서 확인한다.

## 동일 버전 DB 단계

`europa-4.23-s5b-v1`을 새로 추가했다. 기존 Complete S4/S5A 단계는 재실행시키지 않는다. management-server scoped 설정, Veeam checkpoint/image-transfer, KBOSS backup pool/store/job 테이블과 상태·압축·validation 필드를 이 단계에서 적용한다.

KBOSS view를 generic views 목록에 두면 S4/S5A의 view 갱신이 새 테이블보다 먼저 실행된다. 따라서 S5B SQL에서 의존 테이블 생성 뒤 view를 만든다. primary delta ref가 없는 백업도 secondary volume ID를 반환하도록 하고 MySQL ONLY_FULL_GROUP_BY에서 결정 가능한 볼륨별 조회로 보완했다. readonly VM detail 설정은 기존 값·NULL을 보존하며 필요한 key만 한 번씩 추가한다.

실제 MySQL8.0 Docker fixture 결과:

| 검증 | 결과 |
| --- | --- |
| 실제 DatabaseCreator 신규 설치 | 460개 table/view, 명명된 단계12개 Complete |
| 전체014 기준 DB 업그레이드 | 460개 table/view, 기존 version51행 보존 |
| 이미 S5A 적용된 같은4.23 DB | S5B만 추가, 기존 S4/S5A timestamp 보존 |
| 세 설치 경로 schema 비교 | 전체 테이블 열·인덱스·FK 차이0; 신규6개 table/view 정의 동일 |
| 각 경로 checker 반복 실행 | 전체 데이터·DDL·journal snapshot 동일 |
| SQL 자동 커밋 직후 강제 실패/재시도 | 17개 전체 경계에서 Pending→재실행→Complete 확인 |
| 기존 backup/config/journal | 보존, 설정 key 중복 없음, FK 위반 거부 |

열 표시 순서·COMMENT·AUTO_INCREMENT 카운터는 의미 계약 비교에서 제외한다. fixture dump와 인증 자료는 Git에 저장하지 않는다. 처음 실행에서 manifest 없는 classes classpath 때문에 checker가 진행하지 않은 시도는 PASS 근거에서 제외했고, manifest를 가진 schema JAR로 다시 실행하여 실제 단계·테이블 수를 확인했다. 테스트용 JAR은 현재 컴파일 클래스와 현재 SQL/view 리소스를 사용했으며 최종 조립본으로도 관리 서버를 확인한다.

## 관리 서버·API

최종 코드 `d7b88c24e3630de4d8b277d1a890599ff85e4672`로 조립한 noredist 관리 서버 JAR을 전용 MySQL과 loopback HTTP18994에서 실행하고 API를 다시 통과했다. JAR SHA256은 `3cdc9625b39928116560a88664261b7805494e4edf2dee570e447375efd99aad`다. 이 시작/검증 전후 version 및 명명된 DB 단계의 모든 행·timestamp가 같았다. 기존8개 provider가 모두 조회되고, NAS·KBOSS 혼합 zone 설정에서 KBOSS 오퍼링 생성→조회→이름 수정→삭제, backup/job 목록 API가 통과했다. KBOSS 오퍼링에 외부 retention plan 변경을 요청하면 기존 provider capability 검사로 거부한다. 이 시험이 실제 backup/restore 또는 외부 retention API 성공을 뜻하지 않는다.

관리 서버 시작은 SystemVM metadata와 전용 경로를 준비한 뒤 확인했다. 미설치 extension entrypoint, Usage/systemd 및 agent 없는 fixture의 background 경고는 운영 클러스터 검증으로 취급하지 않는다. Veeam 테스트 전용 image transfer API는 기본 비활성 상태이며 실제 image transfer 데이터 검증은 Python/NBD 시험으로 별도 수행했다.

## 자동 검증

Rocky Linux9.8 linux/amd64, JDK17, Maven3.9.10, Node14.21.3/npm6, Python3.10, MySQL8.0을 사용했다. 소스·Git·의존성·빌드·시험은 Docker 내부에서 수행했다. 기존 개발 DB나 볼륨은 초기화하지 않았다.

- Java: 클래스1,178개의 최종 결과는 tests14,018 / failures0 / errors0 / skipped15이며 `s5b-local-tests.tsv`에 기록했다. 처음 전체 reactor 실행에서 NAS restore unit fixture가 native libvirt/QEMU를 호출해 발생한7개 오류를 수정했다. QemuImg 경계를 mock으로 분리하고 실제 argv/timeout/metadata 실패는 추가3개 시험으로 검증했다. FTCTL 테스트가 발견한 기존 VM 시작 인터페이스 호환성도 보완하고 FTCTL/DR 및 관련 server 테스트를 재실행했다. 남은 reactor도 끝까지 통과했다. Tungsten 구간의 JVM 전체가 멈춘 실행은 중단하고 코드 변경 없이 다시 실행해3개 시험이5.477초에 통과했다. 각 재개 로그에서 같은 클래스는 마지막 결과만 집계했으며 단일 최종 SHA의 clean 전체 실행으로 과장하지 않는다.
- UI: 전체30 suites/351 tests와 변경 UI ESLint 통과. `./dev ui-build` exit0. 로컬 소스맵 제외 설정에 따른 source-map 및 bundle 경고를 성공 여부와 구분한다.
- Veeam image server: 기본122개 중 외부 qcow 입력이 필요한4개는 처음 skip됐다. Docker 안에 QEMU10.1/libnbd1.20.3/Python3.10 binding을 준비하고 자체 qcow 데이터로4개를 추가 실행하여 읽기·쓰기·extents·range copy·동시 요청·수명 관리를 검증했다. 등록 해제 경합2개 회귀 포함, 총122개 고유 테스트가 실행됐다.
- NAS shell fixture9개: 디스크별 부모 연결, 부분 bitmap full fallback, 명시 UUID/legacy LINSTOR 이름, 정지 raw DRBD, parent/rebase/backup-begin 실패와 VM thaw/resume. mount/libvirt 명령 경계는 disposable fixture이며 실물 NAS가 아니다.
- all-zone 업로드9개: 비가용/disabled/readonly/full/no-SSVM/no-public 후보 건너뛰기, 단일 pivot, 명시 Zone 범위 유지, 가용 후보 없음의 무할당 실패.
- 실물 HA/import/FTCTL·RBD/CLVM/provider 데이터 시험은 #1025에 기록했다. 이 문서의 자동 시험 결과로 체크하지 않는다.

최종 전체 backend/UI/RAT, Rocky9.8/9.7 Actions와 산출물은 [PR Checks](https://github.com/ablecloud-team/ablestack-cloud/pull/1035/checks) 및 #994 완료 기록에 연결한다. 기존 전체 pre-commit 부채는 #990에서 관리하며, 이번 변경 파일의 추가 진단은 제거했다. 코드 SHA `d7b88c24e3630de4d8b277d1a890599ff85e4672`의 Lint run34555336206은 기존13개 실패 hook 범주이며 검사 시작 이후 변경 경로 진단은0개다. 변경 파일 end-of-file/trailing whitespace/codespell/flake8와 Node14 호환 Markdown CLI 검사도 통과했다. skip/cancelled CI를 PASS로 계산하지 않고 workflow 비활성화나 관리자 우회 merge를 사용하지 않는다.

## 후속 인수

`e2012133599a`의 ONTAP README 표 수정은 반영했다. Network Extension README는 그 기능 코드가 아직 없어 원본 전체 판정은 Pending으로 유지한다. S6/다른 merge의 미반영 side commit을 S5B 제목만 보고 완료로 바꾸지 않는다. `s5b-merge-review.tsv`와 `s5b-dependencies.tsv`에 남은 SHA·소유 이슈를 기록한다.

S5C는 현재 암호화 provider 코드와 S4 자원 예약·S3 비밀값 보호를 인수하고 새 same-version checkpoint를 추가해야 한다. 이미 Complete인 S5B v1을 수정해서 기존 설치에 새 변경이 실행된다고 가정하지 않는다. 전체 기능 통합 후 최종 코드/DB 백업 조합과 #1025의 실물 결과로 S8 릴리즈를 판단한다.
