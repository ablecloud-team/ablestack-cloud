# 설계 전용 Vue3/AntD 목업

실제 Mold UI의 ResourceLayout, MoldDialog, MoldDialogActions와 theme tokens를 사용한다.
실제 API 연결·VM 재시작·설정 적용은 없다. 모든 성공/실패·추가 IP는 설계 예시다.
InfoCard와 NIC 표의 데이터는 fixture이며 현재 실행 자원을 가장하지 않는다.

WSL canonical tree에서:
```sh
cd /root/work/ablestack-cloud/docs/design/sharedfs-1448-network-20261025/mockup
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
cp index.html public/index.html
python3 -m http.server 8798 --bind 127.0.0.1 --directory public
```
ui/node_modules의 기존 의존성을 사용한다. 생성 public/은 git에서 제외한다.

- index.html?mode=static&theme=dark
- index.html?mode=nogateway&theme=light
- index.html?mode=dhcp&theme=dark
- index.html?mode=failure&theme=dark
- index.html?dialog=create&theme=dark
- index.html?dialog=restart&theme=dark

확인: 세로 탭·왼쪽 첫 파란 주 작업·아이콘+업데이트·중복 탭 제목 없음,
고정 헤더/푸터와 본문 스크롤, VM NIC 표의 컬럼·간격, 다크/라이트 텍스트·오류·경고.
게이트웨이 입력 검증은 화면 설명용 한 사례이며 제품 validator가 아니다.
