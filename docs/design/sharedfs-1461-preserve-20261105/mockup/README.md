# #1461 설계 전용 Vue3·AntD·Mold 목업

제품 API와 연결하지 않고 실제 삭제를 실행하지 않는다. 실제 Mold Breadcrumb/ResourceLayout/MoldDialog/style/index.less를 import한다. B 개선안의 예시 데이터이며 현재 배포 UI(A)나 실제 실행 결과가 아니다.

저장소 루트에서:
~~~bash
NODE_OPTIONS=--openssl-legacy-provider node docs/design/sharedfs-1461-preserve-20261105/mockup/build.cjs
python3 -m http.server 8801 --bind 127.0.0.1 --directory docs/design/sharedfs-1461-preserve-20261105/mockup/public
~~~

http://localhost:8801/index.html?view=details&dialog=preserve&theme=light

- view=list/details/capacity/operations
- dialog=preserve/selected/failed (없으면 닫힘)
- theme=light/dark

목록·상세·세로탭과 제거 계획/선택삭제/부분실패 진단을 포함한다. 제목 헤더·확인/취소 푸터는 MoldDialog로 고정하고 본문만 스크롤한다. 상세는 정보 전용이다.

로컬 목업 webpack build PASS. 현재 브라우저 도구가 비활성화돼 시각 검수·이미지 캡처는 미완료다. 앱 재실행 후 A 현재 화면/S 실제 VM 표준/B 개선 목업을 구분해 이슈 이미지로 보완한다. 제품 구현/제품 모듈 빌드/배포/인수 결과로 확대하지 않는다.

public/app.js는 로컬 생성물로 커밋하지 않는다. 별도 목업 PR을 만들지 않는다.
