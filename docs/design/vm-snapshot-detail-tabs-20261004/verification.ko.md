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

# 세로 탭 목업 검증

제품 코드 적용과 분리된 설계 목업의 브라우저 검증 기록이다. 31번의 현재 VM 스냅샷/가상머신 화면을 읽기 전용으로 비교했으며 실제 클러스터 작업은 실행하지 않았다.

- Vue 3.2.37 / compiler-sfc 3.2.37 / Ant Design Vue 3.2.20. WSL ext4의 기존 UI 의존성으로 설계 앱 production bundle 빌드 성공.
- 공유 ResourceLayout/Status/vars.less/dark-mode.less/theme 토큰 사용. 탭의 공통 padding `8px 24px 8px 8px`, 데스크톱 상세·이벤트·코멘트 y 간격 54px가 가상머신 기준과 일치한다.
- 기본 1920×855 다크/라이트와 1366×768에서 left, 390×640와 모바일 경계 765에서 top, 태블릿 경계 766에서 left를 확인했다. 모든 기록에서 문서의 가로 넘침은 없었다. 이벤트 표의 가로 스크롤은 표 안에 한정된다.
- 상세→이벤트→코멘트, Tab으로 포커스 이동 후 Enter로 이벤트 선택, URL의 tab=comments 새로고침 복원, 코멘트 입력의 탭 전환 보존을 확인했다. 목업 입력은 메모리 내 값이다.
- 선택된 이벤트 탭을 시연 도구에서 숨기면 상세로 이동하고 이벤트 탭이 사라진다. 실제 권한 검증은 제품 구현 후 회귀 기준으로 남긴다.
- 시연 도구의 현재 가로 모드는 top, 개선 세로 모드는 left로 전환된다. 하단 도구는 제품에 추가할 요소가 아니다.
- 최종 번들을 새 브라우저 탭으로 열어 다크/라이트 콘솔을 확인했다. 최종 콘솔 결과는 browser-verification.json에 기록한다.
- 반응형 확인 후 viewport override를 해제했다. JSON에 12건의 배치·선택·입력 대조 결과를 보존했다.

[브라우저 관측](browser-verification.json) · [기술/빌드 정보](assets/build-info.json) · [구현 계획과 실행 방법](README.ko.md)

이 결과는 운영 UI의 기능 회귀 통과나 배포 완료를 의미하지 않는다. 제품의 ?tab 탐색·뒤로/앞으로·캐시·실제 조회와 코멘트 저장·권한·다른 자원 상세 회귀는 하위 이슈의 수용 기준에 따라 구현 후 검증한다.
