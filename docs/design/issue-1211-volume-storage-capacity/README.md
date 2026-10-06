<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License,
Version 2.0 (the "License"); you may not use this file except
in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# #1211 추가 설계: 볼륨 생성 및 연결의 스토리지 용량 표시

2026-10-03 · 구현 전 검토 목업. 기존 VM 생성 구현(PR #1215)의 head `1a8445368d57d778c9fc0d19a62dfddfa510784b` 위에서 문서 전용 브랜치를 분기했다. 이번 변경에는 제품 UI/API 수정, 빌드, 테스트 클러스터 배포, 볼륨 생성·연결이 포함되지 않는다.

## UI 개선 방향

VM 상세 → 볼륨 → 생성 및 연결 → 스토리지에 생성 켜기 흐름과 기존 대화상자 폭(520px), 입력 순서, 스위치 및 취소/확인 버튼을 유지한다. 기본 스토리지를 선택하는 드롭다운을 확장하여 이름만 보이는 현재 옵션을 이름·용량 비교 옵션으로 바꾼다.

- 각 옵션은 첫 줄에 스토리지 이름과 선택 체크, 둘째 줄에 **총 용량 / 할당 용량 / 남은 용량**을 3열 정렬한다. 요약 아래 설명에 남은 용량이 물리 여유임을 명시한다. 선택 아이콘은 이름 앞의 기존 resource-icon/hdd-outlined를 유지한다. 목업의 옵션은 가독성 검토용이며 실제 스토리지 이름·용량이 아니다.
- 닫힌 선택 상자는 이름과 기존 아이콘을 유지하고, 바로 아래 작은 3열 요약에 선택한 풀의 동일한 용량을 보여준다. 목록을 닫아도 용량을 확인할 수 있다.
- 바이트 값을 GiB/TiB로 변환하고 천 단위 구분·소수 최대 2자리·고정된 숫자 열 정렬을 적용한다. 단위를 값마다 명시한다. 생성할 볼륨의 크기 입력(기존 API의 GB 표시/계약)은 변경하지 않는다.
- 긴 이름은 줄임표 및 전체 이름 툴팁, 많은 풀은 옵션 목록 내부 스크롤, 뷰포트 여유에 따른 위/아래 펼침과 기존 dropdown 포털로 대화상자·본문 스크롤에 잘리지 않도록 처리, 작은 화면은 대화상자 너비 내 3열/줄바꿈으로 처리한다. 옵션 높이가 달라지므로 기존 가상 스크롤의 행 높이를 맞추거나 적절히 비활성화해 누락·겹침을 방지한다.
- 다크 모드에서 숫자, 보조 레이블, 구분선, 선택 배경의 대비를 확보한다. 남은 용량은 색상뿐 아니라 텍스트·값으로 식별한다. 키보드 선택, 이름 검색, 선택 UUID, 아이콘은 유지한다.
- 조회 중 기존 입력과 유효한 선택 UUID를 보존하고 대화상자 전체를 재생성하지 않는다. 로딩, 조회 실패, 선택 대상 없음, 용량 정보 미확인을 구분한다. 미확인 값은 **—**로 표시하며 0이나 충분한 용량으로 해석하지 않는다.

## 데이터와 범위

현재 `CreateVolume.vue:153-178`은 풀의 이름/아이콘만 표시한다. `fetchStoragePools:460-478`은 이미 `listStoragePools(zoneid,showicon=true)`를 호출하고 Up 상태 풀을 보관한다. 스토리지 선택은 관리자이며 스냅샷 생성이 아닐 때만 허용하는 기존 조건(`showStoragePoolSelect:291-293`)과 API 권한을 유지한다. 같은 공통 CreateVolume 컴포넌트를 사용하는 다른 진입점도 같은 용량 옵션/요약을 사용한다. 새 API나 다중 볼륨 생성 기능을 추가하는 범위가 아니다.

| UI | 기존 응답/처리 |
| --- | --- |
| 총 용량 | 유효한 `capacitybytes`, 호환 응답에서는 `disksizetotal` 사용. 보고 총량이며 관리형 풀은 공급자 의미 확인. |
| 할당 용량 | `disksizeallocated`. 일반 풀은 사용 할당 + 예약 용량이며 물리 사용량과 구분. |
| 남은 용량 · 물리 여유 | 총량과 `disksizeused`가 동일한 물리 용량 의미인 풀에서만 `max(0, total-used)`. `total-allocated`를 물리 여유로 표시하지 않음. 공급자 의미 불명확, 값 없음, 유효하지 않은 값은 —. |

`StoragePoolJoinDaoImpl:151-164`에서 할당량에 예약을 포함하고 `capacitybytes/disksizetotal`, `disksizeallocated`, `disksizeused`를 설정한다. 추가 할당 가능 용량은 오버프로비저닝/임계치/IOPS 등 서버 정책의 별도 값이므로 이 기존 응답의 단순 차이로 계산하거나 물리 여유와 동일시하지 않는다. 조회 시점 값은 생성 성공이나 오퍼링 호환성의 보장이 아니다. 기존 생성·배치 검증은 유지한다. 용량 부족 여부를 색만으로 결정하거나 미확인 값을 이유로 기존 가능 작업을 임의 차단하지 않는다.

## 검토 이미지

| 상태 | 일반 | 다크 |
| --- | --- | --- |
| 목록 펼침 | [일반](images/storage-options-light.jpg) | [다크](images/storage-options-dark.jpg) |
| 선택 후 요약 | [일반](images/storage-selected-light.jpg) | [다크](images/storage-selected-dark.jpg) |
| 일부 용량 미확인 | [일반](images/storage-unknown-light.jpg) | [다크](images/storage-unknown-dark.jpg) |

`mockup.html?theme=dark&state=open`으로 다크/목록 펼침, `state=selected`로 선택 후 요약, `state=unknown`으로 미확인 상태를 볼 수 있다. 샘플 100GB는 설명용이며 기능의 고정 크기가 아니다. 샘플 저장소 Primary/CLVM/Archive 및 숫자는 실제 클러스터 측정값이 아니다.

## 후속 구현 완료 조건

- [ ] 기존 드롭다운에서 이름 검색·아이콘·UUID 선택을 유지하면서 3종 용량과 선택 요약 표시.
- [ ] 기존 관리자/스냅샷/VM 연결 가능 조건 및 생성·연결 API/크기·IOPS/KMS/device ID 계약 보존.
- [ ] 일반·다크/520px·작은 화면, 긴 이름/많은 풀, 키보드 선택, 옵션 행 높이와 스크롤 검증.
- [ ] 누락/0/대용량/오버프로비저닝(할당 > 총량)/관리형 공급자 값, 조회 실패·빈 목록·로딩·선택 보존 검증.
- [ ] UI 모듈 빌드·31번 클러스터 배포 후 실제 생성·연결과 선택 pool UUID/호스트 경로 검증. 승인 전에는 목업으로 실행 검증을 대체하지 않음.

## 현재 화면 검토 범위

첨부된 현재 다크 모드 대화상자와 소스의 이름 전용 옵션을 확인했다. 브라우저로 확인한 Ubuntu24-Process VM에는 VM 스냅샷이 존재해 생성 및 연결이 비활성화되어 있었다. 스냅샷/볼륨을 변경하지 않았으며 실제 생성 작업은 수행하지 않았다. 이 추가 범위는 목업·설계 단계다.

## 목업 검수 결과

일반/다크 × 목록 펼침·선택 요약·미확인 상태의 6종을 브라우저에서 캡처했다. 520px 대화상자는 1920×855 뷰포트 안에 전체 콘텐츠와 취소/확인 버튼이 표시되고 본문 스크롤 누락이 없었다. 긴 이름 줄임표, 혼합 GiB/TiB, 미확인 값 —, CLVM 선택 후 요약 변경, ArrowDown 펼침/Escape 닫힘을 확인했다. 이는 정적 목업 검수이며 제품 구현/볼륨 생성 성공 검증이 아니다. 좁은 화면·대량 옵션·기존 제품 회귀 검증은 후속 구현 완료 조건이다.

## 구현 완료 기록

위 내용은 최초 설계 시점의 목업이다. 승인 후 기존 PR #1215에 구현·UI 빌드·31번 클러스터 배포 및 실제 생성·연결 검증을 누적했다. [실제 화면과 검증 결과](../../validation/issue-1211-volume-storage-capacity/README.md)를 참고한다.
