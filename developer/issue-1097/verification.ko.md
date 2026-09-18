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

# #1097 볼륨 조회 복구와 DB 뷰 정합성 검사

## 원인과 복구

13번 DB의 volumes에는 KMS 키 컬럼이 있었지만 volume_view에는 kms_key_id, kms_key_uuid, kms_key_name, kms_wrapped_key_id가 없었다. S4~S8과 DB 4.23.0.0은 Complete여서 기존 단계 재실행만으로는 이 불일치를 확인하지 못했다.

2026-09-15 전체 cloud DB를 mysqldump --single-transaction --routines --events --triggers로 백업하고 gzip 검증을 수행했다. SHOW CREATE VIEW로 기존 정의를 저장했다. canonical SELECT의 LIMIT 0 조회로 의존 테이블과 컬럼이 유효함을 먼저 확인한 후 CREATE OR REPLACE VIEW로 복구했다. DROP VIEW는 사용하지 않았다. 기존 단계 마커와 볼륨 데이터는 수정하지 않았다.

- W2025-Base VM 165의 ROOT 볼륨 253 / UUID 62ac09e5-5e93-4ecf-bb31-17495f199cc8: Ready, listVolumes 성공.
- 전역 listVolumes: 43개 조회 성공.
- 복구 전후 volumes 건수/size 합계와 vm_instance 건수 동일.
- 서버 query VO의 @Column과 실제 DB view 비교: 추가 누락 없음.

## 재발 방지 코드

DatabaseUpgradeChecker가 S8 이후, 기존 DatabaseUpgrade 락을 유지한 상태에서 매 시작 시 EuropaVolumeViewReconciler를 호출한다. 기존 단계 Complete와 별개다.

1. 배포 JAR의 canonical volume_view SELECT를 LIMIT 0으로 실행해 모든 의존성과 예상 컬럼을 확인한다.
2. 실제 뷰에 예상 컬럼이 모두 있으면 DDL 없이 종료한다.
3. 누락이 있으면 canonical CREATE OR REPLACE VIEW를 실행하고 메타데이터를 재검증한다.
4. 사전 검증/DDL/사후 검증 실패를 숨기지 않고 기동 오류로 처리한다. 성공 마커를 기록하지 않으므로 다음 기동에서 재시도할 수 있다.

이 검사는 컬럼 정합성 검사이며, 동일 컬럼을 가진 임의로 잘못된 JOIN이나 데이터 타입 변경까지 감지하는 전체 SQL 정의 비교기는 아니다.

배포 사전/사후 검사 도구 tools/verify-europa-query-views.py도 추가했다. 완전한 소스 checkout과 보호된 MySQL 옵션 파일을 사용한다. 읽기 전용이며 누락 시 exit 1을 반환한다. 비밀번호를 명령 인자나 저장소에 기록하지 않는다.

```sh
python3 tools/verify-europa-query-views.py --defaults-extra-file /secure/mysql-client.cnf
```

## 빌드 및 검증

- Rocky Linux 9.8 amd64 Docker에서 engine/schema 모듈 package 성공.
- EuropaVolumeViewReconcilerTest 6개 통과: 정상 no-op, 오래된 뷰 복구 및 재실행, 뷰 없음, 의존성 실패 시 DDL 금지, DDL 실패 후 재시도, 사후 검증 실패.
- git diff --check 통과.
- 13번 정합성 검사 도구 실행에서 누락 없음.
- 관리 서버 재시작 후 대상 VM listVolumes 성공 및 UI HTTP 200.
- 실제 KMS 암호화 볼륨 신규 생성/키 회전과 전체 신규 DB 설치는 수행하지 않았다. 기존 canonical 뷰 정의와 조회 API를 변경하지 않았다.
- 브라우저 상세 탭 확인은 로그인 대기 상태이며 API 검증과 구분한다. UI 코드는 변경하지 않았고, 530 오류의 원인을 제거했다.

## 배포와 용량 정리

13번은 schema 모듈이 fat JAR에 포함되어 있어, Docker에서 빌드한 DatabaseUpgradeChecker와 EuropaVolumeViewReconciler 클래스만 기존 런타임에 반영했다. 다른 클래스/리소스와 UI/agent는 그대로 보존했다. 업로드 전 현재 런타임과 조립 기준 JAR의 SHA256 일치를 검증했다.

- 기존 JAR SHA256: `5609d0c97bf2129bb5b2244b1d24e2987cf3d6a4a4f4fab9b1a7b9e061a46cc5`
- 적용 JAR SHA256: `4cf335e0edc13086b38bfc4aba900302578a8ef547b2a0d57c0d8cff53cd3b95`

루트 공간 부족으로 최초 업로드가 실패했으며 불완전 업로드는 제거했다. 기존 배포 백업을 여유 파티션으로 옮겨 보존한 후 업로드·체크섬 검증·원자적 교체·관리 서버 재시작을 완료했다.

이후 사용자의 명시적 요청에 따라 이전 배포 백업과 이번 정상 확인이 끝난 백업을 포함해 40개 경로를 삭제했다. /var/tmp의 날짜별 issue 배포 디렉터리, /root의 cloud/DR 배포 백업, /var/log/ablestack-deployment-backups가 대상이었다. 실행 JAR과 스토리지의 VM/볼륨 데이터는 삭제하지 않았다.

- 루트 가용 공간: 0.38 GiB → 5.94 GiB.

이전 /var/tmp/issue1097-20260915-083821의 DB 및 뷰/JAR 복원 파일은 사용자 요청으로 삭제됐으므로 현재 복원 경로로 사용할 수 없다. 향후 배포는 먼저 여유 용량을 확인하고 단일 작업 백업의 보존/정리 정책을 적용한다. 볼륨 데이터가 아닌 조회 뷰 수정이며, 미래 롤백은 해당 버전의 canonical view와 바이너리를 함께 검토해야 한다.
