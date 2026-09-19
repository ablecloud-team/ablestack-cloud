# VM 스케줄 탭 구현 및 31번 검증

## 변경 범위

- 이슈 #1130. Europa 기준 `e5fb9b5eb758`에서 VM 전용 `VmSchedulesTab.vue`를 추가하고 `InstanceTab.vue`에 연결.
- VM 스냅샷과 동일한 툴바/표/대표 작업/드롭다운/우측 페이징. 별도 탭 제목 없음.
- 생성, 편집, 활성화/비활성화, 삭제, 상세는 중앙 모달. 제목과 하단 버튼은 고정하고 본문만 스크롤.
- 한국어 Cron 선택기, 5종 VM 동작, 전체 시간대, 원본 Cron 편집, 날짜 범위 검증 유지.
- 기존 API만 사용. `listResourceSchedule`은 keyword를 처리하지 않으므로 VM 범위의 모든 페이지를 읽은 다음 검색/페이징. 실패한 갱신은 마지막 성공 목록 유지.
- 편집 시 API 응답의 날짜를 해당 스케줄 시간대로 표시. 변경하지 않은 과거 시작일은 다시 전송하지 않음.
- 기존 API가 종료 날짜 제거를 지원하지 않으므로 기존 종료일의 지우기를 제공하지 않고 이유를 표시.
- AutoScale용 `ResourceSchedules.vue`와 백엔드/API 코드는 변경 없음.

## 자동 검증

WSL ext4 작업 트리: `/home/ablecloud/work/dhslove/cloud-schedules-1130`.

```sh
NODE_OPTIONS=--openssl-legacy-provider npm run lint -- --no-fix src/views/compute/VmSchedulesTab.vue tests/unit/views/compute/VmSchedulesTab.spec.js
NODE_OPTIONS=--openssl-legacy-provider npm run test:unit -- --runInBand --coverage=false tests/unit/views/compute/VmSchedulesTab.spec.js
ABLESTACK_UI_BUILD_VERSION=v4.10.0-Europa-20260918 NODE_OPTIONS=--openssl-legacy-provider npm run build
```

단위 테스트 9개: 여러 API 페이지 검색, 갱신 실패 시 목록 유지, VM 전환 후 이전 응답 무시, 권한 없는 변경 차단, 중복 제출 방지, 토글 최소 파라미터, 원본 Cron/시간대 보존, 편집 날짜 전송/실패 복구, 삭제 리소스 파라미터 계약.

## 브라우저 검증 기록

대상: 31번 클러스터 W2025-GFS2-Sparse (`36d5333d-670a-4644-8c68-1f1dc154ba3a`).

- 실제 UI로 비활성 스케줄 생성 → 설명 편집 → 활성화 → 비활성화. Cron `0 0 1 1 *`를 사용해 테스트 도중 즉시 VM 작업이 실행되지 않도록 함.
- 생성/편집 결과를 DB로 대조: 원본 일정, Asia/Seoul, 비활성 상태, 시작일 보존. VM은 Running 유지.
- 잘못된 Cron과 시작일 이전 종료일은 저장 차단, 입력값 유지. 취소 시 서버 원본은 변경되지 않음.
- 페이징 데이터 10건을 별도로 생성(모두 비활성), 총 11건에서 2페이지 이동과 검색 확인.
- 행 메뉴 순서: 활성화 또는 비활성화 → 삭제 → 상세.
- 라이트/다크 생성·편집·확인·상세 창 확인. 강제 작업 경고와 연/월/주/일 반복 선택 확인.
- 900×480: 본문 scrollTop 0→244, 헤더 y=24/h=55와 푸터 y=399/h=57 유지. 본문 높이 320, 전체 콘텐츠 높이 574.
- 390×600: 모달 폭 358/높이 552, y=24. 날짜 입력은 한 열로 전환. 수평 중앙은 스크롤바를 제외한 가용 화면 기준.
- 초기 실검증에서 밝은 페이지 이동 버튼, 검은 달력/시간 선택기 글자, 삭제 API의 필수 리소스 유형 누락을 발견해 수정하고 실제 배포 화면에서 재검증 통과. 마지막 페이지의 마지막 행 삭제 후 앞 페이지로 복귀함을 확인.

## 검증 범위 제한

UI 변경 모듈의 빌드와 단위/브라우저 검증이다. 전체 Cloud/RPM 빌드는 수행하지 않았다. 31번에 AutoScale VM 그룹이 없어 해당 그룹의 실제 스케줄 변경은 수행하지 않았으며, 공유 컴포넌트와 연결 경로가 변경되지 않았음을 확인했다. 예약 시각의 실제 VM 전원 작업 실행은 이번 UI 검증에 포함하지 않았다.

## 최종 배포 및 재검증

- 소스: `821292b0f66` (최종 UI 빌드 후 동일 커밋의 런타임 locale JSON을 dist에 반영).
- UI 버전: `v4.10.0-Europa-20260918`.
- 아카이브 SHA256: `cebc1ace990e0d2fc714dc42df318dd7e831d15e2415f0bbeaa33455bfc6c914`.
- 활성 webapp에 정적 파일 829개 반영 및 파일별 SHA256 일치 확인.
- WEB-INF/config.json 보존, mold active 및 PID 126153 유지, /client/ HTTP 200.
- 최종 배포 백업: `/root/schedule1130-backup-release-20260919`. 최초 배포 전 백업: `/root/schedule1130-backup-20260919`.
- 1920×1080 모달: x=595, y=198, 폭=720, 높이=684. 스크롤바를 제외한 가용 화면의 수평 중앙 및 화면 수직 중앙과 일치.
- 390×600 모달: x=11, y=24, 폭=358, 높이=552. 본문 높이 440/콘텐츠 높이 668. 달력 팝업 폭 280, x=109, 우측 389로 화면 안에 표시.
- 날짜 선택기 헤더/요일/유효 날짜 글자는 `rgb(240,243,246)`로 표시. 시간 선택기와 달력 배경도 다크 테마에 맞게 표시. 페이지 이동 버튼의 흰 배경 제거 확인.
- 편집 저장과 취소/Escape 후 열었던 버튼으로 포커스 복귀 확인.
- VM 사이드바의 미번역 `label.cloud`를 한국어/영어에 추가하고 배포 화면에서 클라우드 표시 확인.
- 테스트용 스케줄 11개 모두 정리. API와 DB의 대상 VM 활성 스케줄 수 0, VM Running/host_id=5 유지.

## 화면 증거

아래 최종 배포 화면과 앞선 기능 검증 화면을 함께 보관한다. 라이트 모드 기능 화면은 최종 달력 테마 속성 수정 전 촬영한 것으로, 해당 기능 코드에는 후속 변경이 없다.

- [최종 다크 목록](list-dark.png), [정리 후 빈 목록/페이징](empty-dark.png)
- [중앙 추가 대화상자](create-dark.png), [다크 달력](calendar-dark.png), [좁은 화면 달력](mobile-picker-dark.png)
- [라이트 편집](edit-light.png), [날짜 오류](date-error-light.png), [상세](details-light.png), [삭제 확인](delete-light.png)
- [짧은 화면 본문 스크롤](short-viewport-scroll.png), [최종 좌표](final-metrics.json)

안내 문구의 실제 계산 색상 `rgb(197,204,212)` / 배경 `rgb(34,40,47)`의 대비는 9.18:1이다. 이 측정은 해당 문구의 대비 확인이며 전체 UI의 접근성 인증을 의미하지 않는다.
