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

# #1142 구현 및 검증 기록

## 범위와 변경 사유

- `InstanceTab.vue`: 백업 조회 전용 공통 테이블을 VM 전용 `VmBackupsTab`으로 교체. 다른 공통 테이블 사용처는 유지한다.
- `VmBackupsTab.vue`: 스냅샷 탭 형식의 생성/새로고침/검색/설정 툴바와 복원/더보기 행 작업 추가. 내부 중복 제목은 추가하지 않는다.
- 기존 compute/storage 작업 정의를 직접 재사용하여 권한, 오퍼링, 공급자, Offline/flatten 등의 노출·비활성 조건이 따로 분기되지 않게 한다. KBOSS 백업 체인 마감 작업도 설정 메뉴에서 기존 조건대로 제공한다.
- VM 오퍼링 지정·해제·체인 마감은 기존 VM 작업 폼을 호출한다. 백업 생성·스케줄·볼륨 복원·새 VM 생성은 기존 컴포넌트를 탭 내부 modal에 표시한다. 백업 행을 전역 VM 화면 리소스로 대입하는 부작용을 피한다.
- 원본 VM 복원/백업 삭제에는 대상/ID/생성일 확인, quickrestore/관리자 host 선택, 기본 꺼짐 forced, 데이터 유실 및 전체 체인 삭제 경고를 제공한다. 제출 직전 백업/VM/권한을 재조회한다.
- `listRefreshMixin`의 10초 bounded 갱신과 기존 `$pollJob`을 재사용한다. 별도 무한 polling을 추가하지 않는다. 동일 응답은 행 배열 참조를 유지하고 최초에만 loading을 표시한다. 오래된 요청 무시, 검색/페이지 유지, 빈 마지막 페이지 보정, unmount listener 정리를 적용한다.
- 결과 불명확 상태에서는 mutation을 재전송하지 않는다. 기존 job 추적의 catchMethod를 연결해 polling이 중단되어도 탭이 영구 진행 중으로 남지 않고 확인 필요 상태를 표시한다.
- 한국어/영어 안내 추가, 다크/라이트 테마 토큰, 대화상자 버튼 우측 정렬 및 8px 간격, 행 버튼 같은 높이/화살표 구현.

## 자동 검증

신규 테스트: 주기 갱신과 동일 행 보존, 갱신 오류, VM 전환 후 이전 응답 무시, 실제 공급자별 삭제 정책, 제출 전 권한 소실, 서버 상태 변경, 일반 사용자 host 파라미터 차단, 불확정 mutation 재전송 방지, VM/백업 문맥 분리, 검색 파라미터, polling 중단 및 완료 복구.

## 유지보수 대기 당시 기록 (이후 재개)

2026-09-21 사용자가 13번 클러스터 유지보수 중이므로 **구현·모듈 빌드까지만 수행하고 대기**하도록 요청했다. 13번/31번 배포, 실제 브라우저 UI 검증, PR 생성은 유지보수 완료 통보 후 진행한다. 현재 단계에서 실서버 기능 통과를 주장하지 않는다.

재개 시 동일 UI 산출물을 양 서버에 배포하며 config.json 및 WEB-INF 백엔드 파일은 보존한다. 각 서버에서 실제 백업 데이터/권한/오퍼링 상태, 생성 및 복원·삭제 확인창, 스케줄, 다크/라이트 배치, 60초 이상 자동 갱신과 네트워크/콘솔 오류를 검증한다. 기존 백업 복원·삭제를 UI 관측만을 위해 실행하지 않는다. 검증 범위와 한계는 PR에 명시한다.

## 2026-09-21 빌드 완료

- 전체 UI 테스트: 71 suites / 640 tests PASS (신규 11개 포함).
- 전체 UI lint PASS. 신규 테스트 형식 오류 수정 후 전체 재검사 완료.
- Docker Rocky Linux 9.8 amd64 UI-only production build PASS. 기존 Browserslist 데이터 갱신 안내 및 번들 크기 경고는 남아 있음.
- 빌드 소스: 922eab5d720118a15729890c760a24597f79f839
- UI archive SHA256: 7344478455e55cd06b75ccc004a1862100ec19b612adf4ef6a5ddfbcaab5a618
- Docker 볼륨 보관: `/workspaces/artifacts/issue-1142/ui.tar.gz` 및 manifest.json/build.log/tests-all.log/lint-all-final.log.
- 배포용 archive는 config.json/WEB-INF/META-INF 제외. 기존 로컬 config.json 변경도 원본 Git blob과 동일함을 확인.
- 최초 빌드 당시 유지보수 통보 대기. 이후 배포 결과는 아래 참조.


## 2026-09-21 재개 및 최종 배포

- 사용자 재개 요청 및 31번 재설치 확인에 따라 SSH 호스트 키를 갱신하고 양 서버에 UI 배포 완료.
- 실화면에서 오퍼링 할당 버튼이 표시되지 않는 슬롯 호환 문제를 확인하여 `action` 대신 지원되는 `description` 슬롯으로 수정.
- 수정 후 대상 11개 테스트 및 Vue lint PASS, Docker UI-only production 재빌드 PASS.
- 최종 배포 소스: `8eef88051c6756595ed46602c7eb1d20bf6cb818`.
- 최종 archive SHA256: `f3b0268381620b51c8419d96e0f48e1ca1c2c5b9cff023ee87c72d45b56bd0f2` (위 최초 산출물 대체).
- 양 서버 config.json 및 WEB-INF 체크섬 보존. 백엔드/DB 변경 및 관리 서버 재시작 없음.
- 롤백 파일: 13번 `/var/tmp/issue1142-13-20260921-090356/ui-before.tar.gz`, 31번 `/var/tmp/issue1142-31-20260921-090420/ui-before.tar.gz`.

### 브라우저 검증과 제한

- 13번: 빈 목록, 오퍼링 미지정 안내, 생성 비활성, 설정 메뉴 및 할당 대화상자 표시 확인. 할당 제출은 하지 않음.
- 13번: 70,364ms 관측 중 listBackups 약 10.01초 간격 호출, 백업 영역 DOM 변경 0회. 관측기는 제거.
- 다크/라이트 안내 및 보조 글자 색상 확인, 툴바 버튼 높이 32px 및 8px 간격 확인. 검증 후 다크 테마 복원.
- 13번 등록 백업은 0개이므로 실데이터 행 작업과 실제 생성·복원·삭제는 미검증.
- 31번: 최종 UI 및 VM 상세 로드 확인. 현재 관리자 화면에 백업 탭이 없고 전역 백업 경로도 대시보드로 이동. 원인을 단정하지 않으며 API/설정/권한 점검 필요.
- 기존/신규 번들 초기 로딩에서 GUI theme 조회 오류가 관측됨. 이번 백업 탭의 자동 갱신 오류와 구분.
- 공급자별 실데이터 생성/스케줄/복원/볼륨 복원/새 VM/삭제 및 31번 기능 노출 점검은 후속 이슈 #1143에 기록: https://github.com/ablecloud-team/ablestack-cloud/issues/1143


## 페이징 및 한국어 레이블 후속 보완

- VM 백업 탭의 이전/다음 버튼은 전역 다크 스타일에서 글자색만 변경되어 흰 배경과 겹쳤다. VM 스냅샷 탭과 동일하게 배경/테두리/글자 테마 토큰을 적용했다. 비활성 상태와 페이지 전환 로직은 유지.
- 전체 백업 목록 `/backup`의 `label.compressionstatus`, `label.validationstatus` 한국어 누락을 보완하여 압축 상태/검증 상태로 표시. 연결된 폼의 빠른 복원/독립 백업 번역도 추가.
- Vue lint PASS, Docker UI production build PASS, 산출물 한국어 JSON과 소스 일치 확인.
- 소스 `931f7b4e7aa18f76d1537b538c73eb10df47c281`, archive SHA256 `05ca0e2438cab4f7f736b358067c85ff9c344a6db083ab954a2f8a04cd0400bd`.
- 13/31 재배포 완료, config.json 및 WEB-INF 보존. 롤백 파일은 각각 `/var/tmp/issue1142-13-20260921-092120/ui-before.tar.gz`, `/var/tmp/issue1142-31-20260921-092145/ui-before.tar.gz`.
- 13번 실제 화면에서 전체 백업 목록의 압축 상태/검증 상태 열 제목 확인. 이번 확인 시 새 백업 1개가 표시됨(이전 0개 관측 이후 변경). 백업 생성/삭제/복원은 실행하지 않음.
- VM 백업 탭의 양쪽 화살표 표시 확인. 버튼 배경은 흰색에서 rgb(34,40,47), 테두리는 rgb(62,70,80)로 변경. 빈 목록의 비활성 화살표 상태 유지.
