# #1458 Vue3 + AntD 목업

설계 전용이다. 제품 API 호출·자원 변경·제품 배포는 하지 않는다.
실제 Mold Breadcrumb, ResourceLayout, MoldDialog, style/index.less와 현재 Vue3/AntD 의존성을 사용한다.
외부 CDN이나 별도 UI 프레임워크를 사용하지 않는다. 상단 설계 도구와 모든 수치는 예시다.

WSL ext4 저장소의 기존 UI 의존성이 설치된 상태에서 저장소 루트에서 실행한다.

~~~bash
NODE_OPTIONS=--openssl-legacy-provider node docs/design/sharedfs-1458-capacity-20261102/mockup/build.cjs
python3 -m http.server 8798 --bind 127.0.0.1 --directory docs/design/sharedfs-1458-capacity-20261102/mockup/public
~~~

브라우저: http://localhost:8798/index.html?view=capacity&scenario=partial&theme=dark

- view: list / details / capacity
- scenario: partial / current / stale / unknown / transition
- theme: dark / light
- dialog=rules: 집계 기준 대화상자

compiled app.js는 생성 파일이며 커밋하지 않는다. 이 빌드는 **목업 번들 빌드**이며 제품 UI 모듈 빌드가 아니다.
