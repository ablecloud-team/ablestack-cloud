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

# 이슈 #1132 VM 스냅샷 기반 볼륨 작업 제한 검증

## 변경과 검증 범위

- 기존 listVMSnapshot API로 VM 스냅샷을 조회. 생성 및 연결/기존 연결/분리 제한과 한국어·영어 사유 안내.
- 조회 중/실패/권한 부족은 변경 작업 차단. 대화상자 진입과 각 작업 단계/재시도에 재검사.
- VM 전환 이후 이전 응답 무시. 생성 성공 후 연결 제한 시 생성된 볼륨을 보존하고 연결 단계만 재시도.
- 중앙 대화상자, 제목/하단 버튼 고정, 본문만 스크롤. 독립 CreateVolume 화면은 기본 버튼을 유지.
- 기존 서버 정책은 변경하지 않음. UI 모듈만 빌드하며 전체 Cloud/RPM 빌드는 수행하지 않음.

## 자동 검증

WSL ext4 경로 `/home/ablecloud/work/dhslove/cloud-volume-1132`에서 변경 파일 lint 및 관련 단위 테스트 24개 통과.

```sh
NODE_OPTIONS=--openssl-legacy-provider npm run test:unit -- --runInBand --coverage=false tests/unit/views/compute/VmVolumesTab.spec.js tests/unit/utils/vmVolumeActions.spec.js
ABLESTACK_UI_BUILD_VERSION=v4.10.0-Europa-20260918 NODE_OPTIONS=--openssl-legacy-provider npm run build
mvn -P developer,systemvm -Dsimulator -Dnoredist -pl . org.apache.rat:apache-rat-plugin:0.12:check
```

RAT는 소스 아카이브에서 별도로 실행했고 미승인/미확인 모두 0건이다. 기존 누락 헤더와 자동 생성 검증 로그의 분류 보완은 #1131과 같은 변경이다.

## 배포 구성

31번에 배포된 스케줄 개선을 보존하기 위해 배포용 커밋 `cff9edc0ff0`은 이슈 #1132 소스 `5031cb0e7cb`와 #1131 소스를 통합한다. 두 변경의 locale JSON 키를 모두 유지했다. 새 PR의 기능 차이는 볼륨 관련 변경이다.

## 검증 대상 및 제한

W2025-GFS2-Sparse: VM 스냅샷 2개. GFS-TEST-VM: VM 스냅샷 1개. CLVM-TEST-VM 및 CLVM-NG-TEST-VM: VM 스냅샷 없음.

기존 VM 스냅샷과 볼륨은 변경하지 않는다. 마지막 스냅샷 삭제에 따른 재활성화 및 생성/연결 사이의 경쟁 상태는 단위 테스트로 검증하며, 운영자가 만든 기존 스냅샷 삭제로 재현하지 않는다.

## 배포 및 실제 브라우저 결과

- UI 빌드 성공, 표시 버전 `v4.10.0-Europa-20260918` 유지.
- 아카이브 SHA256: `2e7b3ec1410436e0b953ed2a00cae5ebd341d2db712d25a7c6ee7bd18eba4400`.
- 활성 webapp 정적 파일 829개 반영 및 파일별 해시 일치. WEB-INF/config.json 보존.
- mold active, PID 126153 유지, /client/ HTTP 200.
- 백업: `/root/volume1132-backup-20260919`.
- W2025 VM 스냅샷 2개 상태에서 생성 및 연결/기존 볼륨 연결/디스크 분리 모두 비활성화. 안내 문구 표시 확인.
- CLVM-TEST-VM(스냅샷 없음)에서 생성 및 연결/기존 볼륨 연결 활성화, 생성·연결·데이터 디스크 분리 대화상자 진입 후 취소 확인. ROOT의 기존 실행 중 분리 제한은 유지.
- 브라우저의 listVMSnapshot XHR에만 503 응답을 주입하자 확인 불가 안내와 생성·연결 버튼 차단. 가로채기 해제 후 새로고침하면 두 버튼 재활성화. 테스트용 네트워크 차단/가로채기 모두 해제.
- 첫 요청 차단 방식은 공통 인증 처리로 로그인 화면으로 이동하여 재로그인했다. 해당 방식은 실패 안내 검증으로 계산하지 않았고, 503 방식으로 검증을 완료했다.
- 다크 안내 문구 실제 색상 `rgb(214,235,255)` / 배경 `rgb(23,43,61)`. 라이트/다크 안내와 생성·연결·분리 대화상자 확인.
- 1600×1000 연결 모달: x=535/y=318/520×364, 생성 모달: x=535/y=158/520×684. 스크롤바를 제외한 가용 화면의 중앙 배치.
- 900×480 생성 모달: 본문 scrollTop 0→252, 헤더 y=24/h=55 및 푸터 y=403/h=53 유지. 본문 높이 324/콘텐츠 높이 576.
- 검증 전후 API 인벤토리 비교: VM 4개 모두 Running 유지, 연결된 볼륨 ID 5개 및 VM 스냅샷 ID 3개 동일. 볼륨 생성·연결·분리 또는 스냅샷 삭제는 실제 제출하지 않았다.

## 화면 증거

- [다크 제한 안내](blocked-dark.png), [라이트 제한 안내](blocked-light.png)
- [조회 실패 시 차단](lookup-failed-light.png)
- [기존 연결 창](attach-light.png), [다크 생성 창](create-dark.png), [다크 분리 창](detach-dark.png)
- [짧은 화면 스크롤 전](create-short-light.png), [스크롤 후](create-short-scrolled-light.png), [계측값](metrics.json)
