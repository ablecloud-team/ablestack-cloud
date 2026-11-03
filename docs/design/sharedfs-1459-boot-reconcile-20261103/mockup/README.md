# #1459 설계 전용 Vue3·AntD 목업

현재 UI 의존성과 실제 Mold Breadcrumb, ResourceLayout, MoldDialog, style/index.less를 사용한다. 제품 API 연결·설정 변경은 없다. A 현재 UI/S 기준 VM/B 개선 목업은 상위 설계 문서에서 분리한다.

저장소 루트:
~~~bash
NODE_OPTIONS=--openssl-legacy-provider node docs/design/sharedfs-1459-boot-reconcile-20261103/mockup/build.cjs
python3 -m http.server 8799 --bind 127.0.0.1 --directory docs/design/sharedfs-1459-boot-reconcile-20261103/mockup/public
~~~

http://localhost:8799/index.html?view=recovery&scenario=pending&theme=dark

- view=list/details/recovery
- scenario=pending/ready/unknown/blocked
- theme=dark/light
- dialog=diagnostic/resume

app.js는 생성 산출물로 커밋하지 않는다. 이는 로컬 목업 번들 빌드이며 제품 UI 모듈 빌드·배포가 아니다.
