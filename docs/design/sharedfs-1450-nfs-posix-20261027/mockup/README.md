# #1450 설계 전용 목업

현재 canonical WSL 저장소의 Vue3·Ant Design Vue·Breadcrumb.vue·ResourceLayout.vue·MoldDialog.vue·MoldDialogActions.vue와 실제 style/index.less를 import한다. 제품/API 연결 없음. B 화면과 데이터·사전 점검·적용은 설계 예시이며 현재 배포 UI A와 구분한다.

```sh
cd /root/work/ablestack-cloud/docs/design/sharedfs-1450-nfs-posix-20261027/mockup
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
cp index.html public/index.html
python3 -m http.server 8800 --bind 127.0.0.1 --directory public
```

- index.html?view=list&theme=dark — VM 기준 Breadcrumb/목록
- index.html?view=details&theme=dark — 정보 전용 상세와 세로 탭
- index.html?view=nfs&theme=dark — NFS 컨텐츠·좌측 주 액션/업데이트/넓게 보기
- index.html?view=nfs&dialog=edit&theme=dark — 실제값/권장값 분리
- index.html?view=nfs&dialog=edit&policy=NONE&theme=light — No Root Squash의0:0 권장
- NFS ACL 생성/편집 — mapping 상속·RO와 디렉터리 권한 분리
- 권장값 사용→사전 점검 — 명시 변경 preview·확인 전 비활성화

policy.mjs는 설계 계산 프로토타입이며 제품 코드가 사용하지 않는다. public/은 빌드 산출물로 Git 제외다. 새 목업 PR을 만들지 않는다.
