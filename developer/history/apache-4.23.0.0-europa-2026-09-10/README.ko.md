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

# Apache 4.23.0.0 → Europa: S1 추적 기준

부모: [Epic #987](https://github.com/ablecloud-team/ablestack-cloud/issues/987). 기준일: 2026-09-10.

**범위 추출·작업 배정·사전분류를 완료한 초기 추적표다. 제품 코드를 반영하거나 299개 변경의 기능 검증을 완료한 결과가 아니다.**

## 고정 기준

- Apache 이전 체크포인트: `3166e64891fc75d4d32b66d874cff3f613b09b52`
- Apache 목표: `4.23.0.0` / `463f8d0294702a920e8020b62ae3b67f52ae1473`
- Europa 기준 및 사용자 확정 업그레이드 출발점: `014895d8f3dc2f062f379b51ee62d36a0adae88a`
- 원본 범위: `3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473`
- 299개(일반 280개, merge 19개). 두 Apache tree의 순수 diff는 1,722개 파일 / +157,330 / -14,414이며 실제 Europa 반영량이 아니다.
- SHA로 범위를 고정한다. 날짜를 필터로 사용하지 않는다. 이후 Europa가 진전해도 원본 집합은 유지하고 적용/검증 SHA를 갱신한다.

## 산출물과 사용법

| 파일 | 용도 |
|---|---|
| [inventory.tsv](inventory.tsv) | 299개 원본 SHA별 소유 이슈·부모·변경 파일/API/DB/UI·선반영/중복 후보·최종 상태 |
| [merges.tsv](merges.tsv) | 19개 merge의 부모·유입 SHA·remerge-diff 파일/해시·검토 내용 |
| [dependencies.tsv](dependencies.tsv) | 초기 기능 의존성 및 최종 상태 검토 순서; 부모 DAG와 별도로 관리 |
| [evidence.tsv](evidence.tsv) | 280개 일반 커밋의 patch-id·reverse check·4월 이후 Europa 수정 파일 겹침 |
| [workstreams.tsv](workstreams.tsv) | 하위 이슈와 원본 커밋 배정 수 |
| [scope-and-gates.ko.md](scope-and-gates.ko.md) | 지원/업그레이드 범위·실행 게이트·다음 작업 |
| [validate_inventory.py](validate_inventory.py) | 원본 집합, 위상 순서, 중복, 이슈/의존성 참조 및 근거 필드 검증 |

TSV는 ASF 라이선스 주석 줄(`#`)을 제외하고 읽는다. 배열은 세미콜론, 관련 작업 코드는 쉼표로 구분한다. 일반 커밋의 `files`는 부모 대비 변경이고 merge는 **첫 번째 부모 대비 전체 유입 diff**다. merge의 해결 위치는 `merges.tsv`의 `remerge_files`를 사용한다. 파일 겹침은 검토 신호이며 기능 충돌이 확정됐다는 뜻은 아니다.

```bash
# 호스트 실행 폴더에서: 검증은 컨테이너 저장소에서 실행된다.
./dev exec python3 developer/history/apache-4.23.0.0-europa-2026-09-10/validate_inventory.py
```

## 최초 사전분류

| 사전분류(서로 배타적) | 건수 |
|---|---:|
| Reverse Patch Present Candidate | 6 |
| Source Review Required | 247 |
| Merge Resolution Review | 19 |
| Upstream Duplicate Candidate | 24 |
| Same Subject Candidate | 3 |

- stable patch-id가 같은 원본: **12개 그룹 / 24개 SHA**. upstream 내부 중복 후보이며 Europa 반영을 뜻하지 않는다.
- 제목은 같지만 patch-id가 다른 원본: **9개 그룹 / 18개 SHA**. 보안/권한 및 브랜치 차이를 검토하며 자동 제외하지 않는다. 위 표와 별도 축이므로 합산하지 않는다.
- Europa clean tree에 역적용 가능한 후보: **6개**. `4df32ae79f`, `ae177a1655`, `d75140b657`, `ced36291e7`, `928dc7dfc0`, `dd3427d914`.
- 4월 기록과 연결되는 예: NSX #12833 → Europa `7ad9fbc1f0`. 기존 API key pair 모델 → Europa `1a367f0dd5`는 이번 API 후속 작업의 선행 계약이다.
- 최종 `decision`은 **299개 모두 Pending**으로 시작한다. S1 사전분류와 실제 Applied/Adapted/Already Satisfied/Excluded 완료 판정을 혼동하지 않는다.

## 작업 배정

| 코드 | 하위 이슈 | 원본 SHA 수 |
|---|---|---:|
| S1 | [#989](https://github.com/ablecloud-team/ablestack-cloud/issues/989) [Europa][4.23][S1] 원본 커밋 추적표·merge 해결 내역·의존성 기준 확정 | 0 |
| S2 | [#990](https://github.com/ablecloud-team/ablestack-cloud/issues/990) [Europa][4.23][S2] Rocky 9.8 검증 기반·빌드 의존성·CI 및 라이선스 정비 | 77 |
| S3 | [#991](https://github.com/ablecloud-team/ablestack-cloud/issues/991) [Europa][4.23][S3] 인증·권한·보안 및 API 기반 변경 통합 | 66 |
| S4 | [#992](https://github.com/ablecloud-team/ablestack-cloud/issues/992) [Europa][4.23][S4] 동일 버전 DB 업그레이드·자원 할당·Usage 및 Quota 통합 | 38 |
| S5A | [#993](https://github.com/ablecloud-team/ablestack-cloud/issues/993) [Europa][4.23][S5A] VM 수명주기·KVM·migration·import·오퍼링 변경 통합 | 28 |
| S5B | [#994](https://github.com/ablecloud-team/ablestack-cloud/issues/994) [Europa][4.23][S5B] 스토리지·증분 NAS 백업·provider·템플릿 업로드 통합 | 47 |
| S5C | [#996](https://github.com/ablecloud-team/ablestack-cloud/issues/996) [Europa][4.23][S5C] KMS·암호화 및 Usage 키 의존성 통합 | 3 |
| S6 | [#997](https://github.com/ablecloud-team/ablestack-cloud/issues/997) [Europa][4.23][S6] DNS·Network Extension·VR/SystemVM·VPC 및 CKS 통합 | 32 |
| S7 | [#998](https://github.com/ablecloud-team/ablestack-cloud/issues/998) [Europa][4.23][S7] UI·브랜딩·한국어 및 릴리즈 문서 통합 | 8 |
| S8 | [#999](https://github.com/ablecloud-team/ablestack-cloud/issues/999) [Europa][4.23][S8] 최종 병합 SHA 통합 검증·RC 및 복구 절차 확정 | 0 |

S1은 추적 기준 관리, S8은 통합 게이트이므로 직접 배정 SHA는 0개다. merge 19개는 S2가 총괄하고 `related_workstreams`에 적힌 기능 소유자가 함께 적용한다. S2의 77개 소스를 모두 끝낸 후에만 기능 개발을 시작하는 구조가 아니다. 초기 `baseline_ready`와 최종 `DONE`을 구분한다.

## Merge 개별 분석

`git show --remerge-diff`(Git 2.52.0, 기본 merge 설정)로 **13개에서 차이**, **6개에서 빈 diff**를 확인했다. 빈 diff는 현재 Git 알고리즘 기준 해결 차이가 없다는 뜻이며 부모 커밋 반영 완료나 무검증 제외의 근거가 아니다. 비어 있지 않은 diff에도 conflict marker 제거·버전 유지가 포함되므로 모든 줄을 신규 기능으로 해석하지 않는다.

| merge | 해결 파일 수 | 관련 작업 | S1 분석 / 후속 검증 |
|---|---:|---|---|
| [856d83a15e](https://github.com/apache/cloudstack/commit/856d83a15eeef23806c896fa4fddbec136b28613) | 0 | S5A,S6 | remerge 차이 없음. 유입 4개 일반 커밋과 #12833 선반영 후보 별도 판정. |
| [8906aa1d46](https://github.com/apache/cloudstack/commit/8906aa1d460166044feb11f5a511a140ca776281) | 2 | S6 | VPN UI DH22/23/24/31 및 기본 Group31, props/data 합성 확인. |
| [3e688b0197](https://github.com/apache/cloudstack/commit/3e688b0197863bd35635d7e0999b91a35b692ac6) | 3 | S5A | 실행 중 disk-only snapshot revert 제한, unmanaged import 호출/테스트 합성. a127a26ebd 대조. |
| [a4a52c9665](https://github.com/apache/cloudstack/commit/a4a52c96659d9f94fd080096aa673916ae221ef2) | 0 | S5A,S5B | remerge 차이 없음. 유입 HA/import/storage 부모 변경 개별 판정. |
| [21b2025c50](https://github.com/apache/cloudstack/commit/21b2025c5055d4580e828f708d406510853f0060) | 0 | S5B | remerge 차이 없음. 4.20 provider 후속 부모 커밋 상태 확인. |
| [67b849f3ef](https://github.com/apache/cloudstack/commit/67b849f3efd16f5c353501d176a42e45d87acb44) | 168 | S2,S5A,S7 | 168개 해결 파일 중 다수 POM 버전. UserVmManager 주입/테스트, Docker/Marvin, changelog, pt_BR도 검토. |
| [ce52b9dae0](https://github.com/apache/cloudstack/commit/ce52b9dae0cef57394967346247914a47cb04da4) | 1 | S4 | SnapshotManager 생성/persist/자원 카운트 합성. 중복 count 및 실패 회수 확인. |
| [d5101b0c90](https://github.com/apache/cloudstack/commit/d5101b0c905a818a83c5048dc37447162040089e) | 2 | S4,S5B | snapshot copy 메서드/복사 URL/자원 제한 및 테스트 import 합성. |
| [fb5e24fa08](https://github.com/apache/cloudstack/commit/fb5e24fa086880b0b3058278c0878ef404af0e59) | 3 | S3,S2 | ACL Rule regex, ApiServer import, server POM 해결. f49ab6b394 최종 동작 대조. |
| [b3b9caddc1](https://github.com/apache/cloudstack/commit/b3b9caddc191da6842b99ee6b2f2d5f92cb70145) | 2 | S5A,S4 | HostJoinDao 및 VolumeApiService 테스트 해결. host core/자원 테스트 대조. |
| [c7e2c748f7](https://github.com/apache/cloudstack/commit/c7e2c748f74619e3b0454d6f2904b89abaf39003) | 9 | S4,S5A,S5B,S3 | upgrade 체인/JSONContentType/DRS VMInstanceDetails/업로드 getAPI async 합성. DB와 local-upload 회귀 포함. |
| [846803db07](https://github.com/apache/cloudstack/commit/846803db0766e95e618d0675186268c4d31fefb0) | 2 | S6,S5B | NetworkOrchestrator DHCP+throttling 설정 합성, backup restore/attach 제한 메시지 일치. |
| [a503a52ba6](https://github.com/apache/cloudstack/commit/a503a52ba6907cdfa704212dac9d5d831a248f3d) | 0 | S2 | remerge 차이 없음. Ubuntu/systemvm 부모 변경을 지원 환경별 판정. |
| [76a4bc8c9d](https://github.com/apache/cloudstack/commit/76a4bc8c9dea970f829772fdb8419013974797f9) | 2 | S5A | ManagementServer migration 조회 메서드 경계와 테스트 합성. 17e5947a6d 후속과 함께 검토. |
| [fe3df6b660](https://github.com/apache/cloudstack/commit/fe3df6b660d3757797235cf4cbd3f96716feffbe) | 1 | S5A | ManagementServerImplTest vGPU/DeploymentPlanningManager mock 중복 해결. |
| [e1cf0f335a](https://github.com/apache/cloudstack/commit/e1cf0f335a7f2e4b6cd71c3147ef64bcd89d486e) | 0 | S7,S6 | remerge 차이 없음. 부모 VNF UI 수정 등은 별도 추적. |
| [7fd56e573b](https://github.com/apache/cloudstack/commit/7fd56e573b8d6055b2d560babc9ad1f6c25d5d32) | 12 | S3,S5A,S5B | DeployVM/API·LDAP·중복 host 확인·backup restore/volume/userdata·보안 테스트 합성. 이후 main forward merge까지 추적. |
| [efa58cc52f](https://github.com/apache/cloudstack/commit/efa58cc52fca2c58840b8c83b583e3e36f0f5577) | 19 | S3,S5B,S7 | ACL/AccountManager, webhook 권한·redirect·future, OAuth 도메인 flow, backup/NAS LINSTOR, escapeHtml 합성. 19개 파일 기능별 검증. |
| [02182a1572](https://github.com/apache/cloudstack/commit/02182a1572a12a89fc2f429f57bcec6f9e6ad7c0) | 2 | S3,S2 | AccountManagerImpl/MockAccountManager 메서드 경계 해결. 8b72a16d61 후속 compile/test와 결합. |

## 상태와 근거 갱신

- `pretriage`, patch-id, reverse 결과는 시작 SHA에서 수집한 증거다. 후속 코드 변경 시 재검증 기준 SHA를 명시한다.
- `decision`: Pending → In Progress/Blocked/Deferred → Applied/Adapted/Already Satisfied/Excluded. 미완료 상태를 완료율에 합산하지 않는다.
- Applied/Adapted에는 실제 Europa 적용 SHA·PR·검증 근거, Already Satisfied에는 현재 기능/테스트 근거, Excluded에는 이유·대체 동작/영향·검토 근거를 기록한다.
- 의존성의 `code_dependency`는 파일/심볼 근거가 확인된 의존성, `baseline_contract`는 기준 Europa 선행 구현, `review_order`는 관련 기능의 권장 검토 순서, `merge_followup`은 해결 내용 대조 순서다. 권장 순서를 필수 cherry-pick 관계로 단정하지 않는다.
- 모든 부모 SHA를 기록한다. 범위 밖 부모도 정상이며 집합 안 부모는 해당 행보다 앞선다. 모든 전이적/런타임 의존성이 증명됐다고 간주하지 않고 구현 중 발견한 의존성을 추가한다.
- 기존 4월 문서 및 #978/#950/#898은 보존하고 이번 통합에 필요한 교차 계약만 연결한다.

## 근거 재현

아래 명령은 저장소 경로의 `./dev exec` 또는 `./dev shell` 안에서 실행한다. 원본 태그/커밋 object가 필요하다.

```bash
git rev-list --reverse --topo-order 3166e64891fc75d4d32b66d874cff3f613b09b52..463f8d0294702a920e8020b62ae3b67f52ae1473
git show --format= --binary SOURCE_SHA | git patch-id --stable
git show --format= --binary SOURCE_SHA | git apply --reverse --check --whitespace=nowarn -
git show --remerge-diff --format= --no-ext-diff MERGE_SHA
```

역적용 결과를 재현하려면 **시작 SHA의 깨끗한 별도 컨테이너 worktree**를 사용한다. 현재 작업 브랜치를 reset/checkout해서 사용자 변경을 지우지 않는다. `reverse_check=FAIL`은 코드가 없다는 증명이 아니며, 적응 구현·문맥 변화·경로 차이 때문에 실패할 수 있다. remerge-diff 해시는 알고리즘/설정에 영향을 받으므로 Git 버전과 함께 해석한다.
