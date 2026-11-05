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

로컬 목업 webpack build와 실제 라이트·다크 렌더 검토를 완료했다. A 현재13번 SharedFS/S 실제 VM 표준/B 개선 목업을 구분한 이미지23개와 목업 점검9개는 상위 README와 evidence/ui-review.json을 따른다. 1366×720에서도 제목/푸터 고정과 본문 스크롤을 확인했다. 제품 구현/제품 모듈 빌드/배포/인수 결과로 확대하지 않는다. 이전 제품 구현 지시는 취소됐으며 이 목업은 설계만이다.

public/app.js는 로컬 생성물로 커밋하지 않는다. 별도 목업 PR을 만들지 않는다.
