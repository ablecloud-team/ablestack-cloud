# #1457 설계 목업 재현

제품 API와 연결하지 않은 Vue3/AntD 목업입니다. 실제 Mold Breadcrumb·ResourceLayout·MoldDialog·MoldDialogActions 및 테마 CSS를 canonical UI 소스에서 import합니다.

WSL RockyLinux-9.7의 `/root/work/ablestack-cloud/ui/node_modules`가 있는 환경에서 이 디렉터리에서 실행합니다.

```bash
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
cp index.html public/index.html
python3 -m http.server 8797 --directory public --bind 127.0.0.1
```

`http://localhost:8797/index.html?view=smb&dialog=policy&theme=dark`를 엽니다.

- view: list / details / smb
- dialog: policy / create 또는 생략
- theme: light / dark
- scenario: ready / last / invalid / unknown / rollback

명시된 상태와 revision·접속 결과는 설계 예시입니다. 입력 정규화·전환 확인·스크롤을 검토하기 위한 목업이며 API→DAO→게스트 계약과 기능 인수 시험을 대체하지 않습니다. 컴파일된 app.js는 저장소에 넣지 않았습니다.
