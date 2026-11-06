# #1460 설계 전용 Vue3·AntD 목업

실제 Mold Breadcrumb·ResourceLayout·MoldDialog·style/index.less를 사용한다. 제품 API 연결/자원 변경이 없다. 목록·상세·NFS, external/verified/failed/unknown 상태를 예시로 제공한다. 현재 화면 A와 개선 B를 혼동하지 않는다.

저장소 루트에서:
~~~bash
NODE_OPTIONS=--openssl-legacy-provider node docs/design/sharedfs-1460-local-probe-20261104/mockup/build.cjs
python3 -m http.server 8800 --bind 127.0.0.1 --directory docs/design/sharedfs-1460-local-probe-20261104/mockup/public
~~~

http://localhost:8800/index.html?view=nfs&scenario=external&theme=dark

- view=list/details/nfs
- scenario=external/verified/failed/unknown
- theme=dark/light
- dialog=diagnostic

app.js는 로컬 생성물로 커밋하지 않는다. 제품 모듈 빌드/배포가 아니다. 상세는 정보 전용, NFS 관측은 NFS 탭, 대화상자는 본문만 스크롤한다. 넓게 보기는 좌측 버튼 그룹의 마지막이다.
