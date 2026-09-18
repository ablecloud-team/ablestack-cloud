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

# VM 스냅샷 탭 작업 UI 검토 목업

이슈: https://github.com/ablecloud-team/ablestack-cloud/issues/1100

설계 검토용 정적 목업이며 API를 호출하지 않는다. `mockup.html`을 다운로드해 브라우저로 열면 상단 버튼으로 목록/작업 메뉴/복원 확인/삭제 확인을 전환할 수 있다. 나머지 생성/검색 등은 배치 검토용이다. 두 번째 스냅샷은 비활성 사유를 보여 주기 위한 예시 데이터다.

- list.png: 행 우측 복원/더보기, 현재 배지, 유형에 따른 비활성 사유
- menu.png: 상세/볼륨 스냅샷 생성/삭제 메뉴
- restore.png: 복원 대상 및 영향 확인
- delete.png: 삭제 대상 및 KVM 메모리 스냅샷 영향 확인

Docker의 headless Chromium으로 1600px 폭 PNG를 생성하고 육안 검토했다. 실제 제품 구현의 반응형/키보드/라이트 테마 검증은 이슈의 완료 기준으로 별도 수행한다. 승인 후 제품 구현에 사용한 설계 자료이며, 중복되는 탭 내부 제목은 사용자 요청에 따라 제거했다. 실제 목록은 10초 주기 자동 갱신을 유지한다.
