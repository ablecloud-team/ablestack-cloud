# #1444 설계 목업

실제 API 미연결. Vue3·AntD와 canonical WSL UI의 MoldDialog/MoldDialogActions 및 theme less를 직접 import한다. 제품 구현이 아니다.

- 빌드: `NODE_OPTIONS=--openssl-legacy-provider node build.cjs`
- 기존 UI node_modules만 사용하며 npm install을 실행하지 않는다.
- 빌드 후 public에서 `python3 -m http.server 8794 --bind 127.0.0.1`
- `?theme=dark&state=rolledback` / `?theme=light&state=rolledback`
- `?theme=dark&state=recovery` / `?theme=dark&state=pending`
- `?theme=dark&dialog=0&tab=operations` / `?theme=dark&dialog=0&tab=nfs`
- 예제 IP/작업 ID/진단은 fixture다. public/app.js는 빌드 산출물이므로 저장소에 포함하지 않는다.
- 기존 폼의 세로 배치를 유지하고 공통 UI shell·간격·대비와 처리 상태 표시를 검토하기 위한 문서 자산이다.
