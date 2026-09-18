# #1110 검증 기록 (13번 클러스터)

- Docker: Rocky Linux 9.8 / amd64, UI 모듈만 빌드.
- UI 전체 테스트: 61 suites, 554 tests PASS. 이번 변경 관련 테스트 17개 포함.
- 전체 lint PASS, git diff --check PASS.
- 사용자 추가 요청: 결과 대화상자 버튼 우측 정렬, flex-wrap, gap 8px 반영 후 UI 재빌드/재배포.

## 배포
- 소스: 353efb09400ad67f5f836abe0fafb39fe5e55969
- UI archive SHA256: bd7264bef5f59fa02075bf5973a319f6f0bb9061d54d5999b5c5497d63af64fa
- 서버 롤백 백업: /var/tmp/issue1110-20260917-021305/ui-before.tar.gz
- config.json/WEB-INF 백엔드 해시 보존 확인. 관리 서버 재시작 없음.

## 실제 UI/API 확인
- W2025-Base(726c3e62-a867-4d4f-ae8b-ff742db97cef): 75,691ms DOM 관측. 2행/컬럼/loading 변경 0회. 사용자 ROOT-165 및 W2025-Base-Data1 연결 유지 확인.
- 기존 VM 스냅샷이 있는 W2025-Base에서 attachVolume 정책 거부 확인. 생성된 볼륨 ID와 생성 성공/연결 실패가 유지되고, 후속 작업 취소로 새 볼륨 보존 확인.
- 별도 검증 VM: issue1110-volume-validation / 3a2db39f-c5ff-4245-b756-91547a84de09 (i-2-181-VM).
- issue1110-ui-volume / 4efa15c3-cc1c-4eb6-9c46-2b00ea984396 / 1GB: 생성 성공, 기존 볼륨 선택 연결 성공, 연결 해제 후 보존 성공. VM 목록에서 행이 사라져도 작업 결과 창은 완료 및 볼륨 링크를 유지.
- issue1110-ui-expunge / 86f55bce-9093-45e5-9948-6d32bdf96b0c / 1GB: 생성→자동 연결 성공. 영구삭제 경고/최종 위험 버튼 확인.
- 다크 테마 확인창의 정보 표/라디오/경고 가독성과 아래 화살표 아이콘 확인.

## 환경에서 관측한 서버 제한
- VM 스냅샷이 존재하면 attach/detach를 서버가 거부한다. 기존 스냅샷을 삭제하거나 이 정책을 우회하지 않았다.
- 최초 기동 전(루트 Allocated) 검증 VM의 attachVolume은 Internal Server Error를 반환했다. 최초 기동 후 같은 볼륨의 연결 재시도는 성공했다. 원인을 확정한 서버 수정은 이 UI PR 범위에 포함하지 않는다.

## 최종 배포 UI 추가 확인
- 66,330ms 관측: GET listVolumes 6회, 간격 약 10.02초. 2행/동일 컬럼/로딩 없음 유지, DOM 상태 변경 0회. 임시 관측기는 제거함.
- 결과 창 버튼: computed display=flex, justify-content=flex-end, gap=8px, 마지막 버튼의 우측 오차 0px. 실제 다크 테마 화면 확인.

- 라이트 테마: 확인창 표 배경 rgb(245,247,250)/rgb(255,255,255), 글자 rgb(31,41,55)/rgb(75,85,99). 실제 화면 확인 후 다크 테마 복원.
- 일반 삭제: issue1110-ui-volume 재연결 후 detach→destroyVolume(expunge=false) 연속 성공. API state=Destroy, virtualmachineid 없음, destroyed=true 확인.
- 최종 행 버튼 측정: 분리/드롭다운 높이 모두 24px, y 좌표 동일, 간격 8px(활성/비활성 행 모두).

- Device ID: issue1110-device6 / 996e9765-3cd1-4b3d-a015-e4415da28704 생성 연결 시 6 지정 성공. 보존 분리 후 기존 연결에서 7 지정 성공, 목록 Ready/7 확인. 예약 번호 3은 생성 폼에서 차단 확인.
- 영구삭제는 경고/최종 확인 버튼까지 확인. 실행 시점 사용자 승인이 아직 없어 실제 expunge는 실행하지 않음. 검증 VM과 연결된 1GB 검증 볼륨 두 개는 보존.

- 최종 안내 문구: 생성 및 기존 연결 양쪽 모두 다크 모드 computed color=rgb(197,204,212), margin-top=8px, 입력 박스 하단과 도움말 상단 실측 간격 8px 확인. 실제 화면 가독성 확인.
