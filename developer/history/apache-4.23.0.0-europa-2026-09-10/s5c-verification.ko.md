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

# S5C: KMS·암호화 및 Usage 키 의존성 통합

부모 Epic #987, 기능 #996, 코드 PR [#1036](https://github.com/ablecloud-team/ablestack-cloud/pull/1036).
시작 코드는 27dbd94c2914162862c2b6e61d5e69dd22f5303d이며 DB 출발점은 사용자 지정 014895d8f3dc2f062f379b51ee62d36a0adae88a를 유지한다.
Apache 범위는 3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473 (4.23.0.0)이다.

## 원본 범위와 판정

직접 원본 3개를 Applied 1 / Adapted 2로 판정했다. KMS 기능 #12711, Usage DAO 후속 #13535, UI 아이콘/순서 #13568이다.
[원본별 검토](s5c-review.tsv)에 적용 SHA와 보완 코드를 기록했다.
전체 고정 299개는 Applied 78 / Adapted 88 / Already Satisfied 49 / Excluded 28 / Pending 56이다.
S5C 원본을 유입하는 공유 merge는 없으므로 S2 잔여 16개(merge 12 + 일반 4)의 판정은 변경하지 않는다.
남은 기능은 S6 32개, S7 8개이며 다음 코드 통합은 #997이다.
추적표 검증기는 S5B의 기존 54개 완료 행이 후속 Europa 검토 SHA를 기록한 형식을 허용하도록 보완했다.
고정 Apache 범위·DB baseline은 유지하고 후속 검토 SHA가 baseline 이후 현재 코드의 실제 조상인지 검사한다.

사용자 확정에 따라 코드 검토·자동 테스트·CI 및 정상 PR 병합으로 #996을 완료한다.
실물 HSM·KVM·스토리지 시험은 [#1025](https://github.com/ablecloud-team/ablestack-cloud/issues/1025)에 인수했고 전체 코드 병합 후 S8 #999에서 실행한다.
실물 미실행을 PASS로 표시하거나 코드 PR의 Draft 조건으로 삼지 않는다.

## Europa 적응 및 키 보존

- Database/PKCS#11 provider, KMS/HSM API·UI, 볼륨 DEK 참조와 계정 정리 기능을 추가했다.
  KVDO 압축·중복제거·물리 사용량, KBOSS·기존 백업 provider, CheckedReservation, customParameters, TPM·DR·Desktop·Automation 구성을 보존한다.
- 이전 VolumeApiService/UserVmService/VolumeOrchestrationService/OrchestrationService 호출 시그니처는 기본 메서드로 연결하고 KMS ID를 null로 전달한다.
  FTCTL/DR 등 기존 호출자의 인수 의미가 바뀌지 않는다. S5B VM start/quickRestore 계약도 유지한다.
- legacy passphrase는 이미 Base64 문자열이다. KMS 전환에서 먼저 디코딩한 원래 48바이트 DEK를 감싼다.
  VolumeObject는 unwrap 후 한 번만 인코딩하므로 기존 libvirt/QEMU 비밀값을 그대로 반환한다. 임시 바이트 배열은 정리한다.
- 전환은 볼륨 행 잠금과 트랜잭션으로 wrapped-key 삽입·볼륨 참조 변경을 묶는다.
  같은 계정·존을 확인하고 DB update 실패를 성공으로 처리하지 않는다. 기존 passphrase 행을 삭제하지 않는다.
- 키 생성·회전·전환·삭제와 rewrap은 같은 KMS 키의 클러스터 잠금을 사용한다.
  이전 KEK를 정리하는 동안 새로운 참조가 생기는 경합을 막는다. 잠금 실패 시 작업을 거부하고 예외에도 잠금을 해제한다.
- 지정한 KMS 키/래핑 키 부재 및 unwrap 실패를 새 legacy 키로 대체하지 않는다.
  snapshot 복제에서도 KMS 선택을 공통 암호화 경로로 전달한다. 기존 encrypted snapshot에서 새 볼륨 생성 금지 정책은 유지한다.
- HSM 응답에서 PIN 등 민감한 세부 값은 마스킹하고 다른 계정에 공개된 profile의 세부 값은 생략한다.
  마이그레이션 성공 응답을 명시하며 배치 중 실패가 있으면 성공/실패 개수를 포함해 async job을 실패로 표시한다.
  이미 성공한 개별 볼륨을 되돌리지는 않으며 실패한 볼륨의 이전 참조는 보존한다.

## 동일 버전 DB

새 europa-4.23-s5c-v1 단계가 KMS 테이블 6개와 볼륨 참조 2개/FK를 추가한다.
기존 Complete S4/S5A/S5B를 재실행하거나 수정하지 않는다.
이전 단계의 volume_view 갱신은 KMS 컬럼이 없는 동안 pre-s5c-cloud.volume_view.sql을 사용하고,
S5C DDL 이후 KMS·KVDO 필드를 모두 포함한 최종 뷰로 갱신한다.

| 실제 MySQL 8 fixture | 테이블·뷰 | version 행 | migration journal 행 | 결과 |
| --- | ---: | ---: | ---: | --- |
| 신규 설치 | 466 | 52 | 13 | PASS |
| 014895d8f3 기존 DB | 466 | 51 | 4 | PASS |
| S5B 완료 동일 버전 DB | 466 | 52 | 13 | PASS |

세 경로 모두 재실행 전후 전체 스냅샷이 동일하다.
기존 version/Complete journal의 행·시간과 볼륨/passphrase 데이터가 보존된다.
DDL 10개 실행 경계마다 커밋 직후 중단을 주입하고 Pending 상태에서 재실행·완료·반복 성공을 확인했다.
실제 FK가 존재하지 않는 키 참조를 거부한다.
[DB 결과](s5c-db-results.tsv)와 [재현 fixture](s5c-fixtures/README.ko.md)를 참조한다.

## 자동 검증

- Rocky 9.8 linux/amd64 Docker, JDK 17·Maven 3.9.10·Node 14/npm 6·Python 3.10·MySQL 8을 사용했다.
  개발 DB/볼륨은 초기화하지 않았고 테스트는 epic996-mysql-ddl의 일회용 DB와 loopback 18996 서버에서 실행했다.
- 전체 developer/systemvm/simulator/noredist install 컴파일 성공.
  [집중 Java 189개 / 11개 suite](s5c-local-tests.tsv)는 실패·오류·skip 없이 통과했다.
  기존 VolumeOrchestrator 테스트의 닫히지 않은 Mockito 생성자 mock을 정리해 이후 테스트 오염도 해소했다.
- UI 30개 suite / 351개 테스트 통과. 기존 Vue 동작의 회귀를 확인하고 KMS 변경 파일은 lint와 production build로 추가 확인한다.
- 실제 조립된 Management에서 HSM/키 생성·조회·수정·회전·삭제, 일반 사용자/프로젝트 소유 키,
  다른 계정의 키 조회·변경·삭제·회전 거부, 일반 사용자의 HSM 생성 거부와 profile 비밀 마스킹을 확인했다.
  공개 profile 선택에는 listall=true를 사용하며 기본 Database profile은 관리자 활성화가 필요하다.
- 실제 UsageServer.start와 component lifecycle을 실행해 KMSWrappedKeyDao/VolumeDao 주입 및 CLOUD_DB 조회를 확인했다.
  Usage 전용 jar와 의존성으로 실행하며 Management의 전체 classpath를 섞지 않는다.
- API 전환 중 실제 MySQL BEFORE UPDATE 오류를 주입했다.
  async job 실패, wrapped-key 삽입 rollback, 기존 passphrase/볼륨 참조 보존을 확인했다.
  비활성 HSM·키도 새 키 대체 없이 거부한다. 동일 볼륨 반복 전환은 안전하게 성공한다.
- 동시에 요청한 KEK 회전과 실제 background rewrap 뒤에도 원래 DEK가 유지된다.
  실제 DatabaseKMSProvider AES-GCM·VolumeObject 출력으로 QEMU 10.1이 기존 LUKS QCOW2의 4 MiB 비영 데이터 전체를 복호화했고 바이트 단위로 대조했다.
  이는 소프트웨어 fixture 검증이며 실물 HSM·KVM I/O 검증은 아니다.
- 공식 backend/UI/RAT/Rocky 9.8·9.7 결과와 마지막 SHA의 검사 링크는 PR #1036의 완료 기록을 기준으로 한다.
  전체 저장소 pre-commit의 기존 실패는 S2 기준과 분리하고 변경 경로의 새 진단은 해소한다.

## 인수

[후속 계약](s5c-dependencies.tsv)에 새 DB 단계, 기존 API 인수, 스토리지 암호화 및 UI 보존 사항을 기록했다.
S6/S7는 완료된 S5C 단계의 DDL을 고쳐 기존 설치에 적용될 것으로 가정하지 않는다.
실물 인수 #1025에는 HSM별 PIN/단절/지연, 다중 Management 경합, 재시작,
SharedMountPoint migration·LINSTOR encrypted snapshot backup/restore와 키 보존/데이터 체크섬 항목을 추가했다.
