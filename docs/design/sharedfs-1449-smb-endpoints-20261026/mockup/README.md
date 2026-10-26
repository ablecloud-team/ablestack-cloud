# 설계 전용 목업

현재 저장소의 Vue3, AntD, ResourceLayout, MoldDialog, MoldDialogActions와 style/index.less를 재사용한다. 제품/API 연결 없음. 모든 데이터·점검·적용은 설계 예시다.

canonical WSL에서 ui/node_modules 기존 의존성 사용:

```sh
cd /root/work/ablestack-cloud/docs/design/sharedfs-1449-smb-endpoints-20261026/mockup
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
cp index.html public/index.html
python3 -m http.server 8799 --bind 127.0.0.1 --directory public
```

- index.html?theme=dark 또는 light
- ?dialog=add&theme=dark
- ?dialog=edit&theme=light
- ?dialog=delete-blocked&theme=dark
- ?scenario=failure&theme=dark

실제 VM 표준과 비교: 중복 SMB 제목 없음, 세로 탭, 왼쪽 첫 파란 주 버튼, 아이콘+업데이트, 같은 버튼 묶음 마지막의 넓게 보기, body만 스크롤하는 고정 헤더/푸터, 16px 간격, 다크/라이트 가독성을 검토했다. 전체 UI 개편이나 제품 구현 PR이 아니다. public/ 빌드 결과는 git 제외다.
