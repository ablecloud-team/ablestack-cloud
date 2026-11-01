# 설계 목업 실행

제품 UI 소스는 수정하지 않으며, 현재 WSL UI dependencies와 실제 Mold 컴포넌트를 import합니다. API에는 연결하지 않습니다.

canonical WSL 저장소에서 이 디렉터리로 이동한 뒤 실행합니다.

```bash
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
python3 -m http.server 8796 --bind 0.0.0.0 --directory public
```

Node20/webpack4 개발 목업의 로컬 빌드 호환 flag입니다. 제품의 TLS/서명 설정을 바꾸지 않습니다.

- policy: http://localhost:8796/index.html?view=smb&dialog=policy&theme=dark
- light: theme=light
- 현재2775 충돌 예시: scenario=setgid
- 목록/정보 상세: view=list / view=details
- 초기 생성 예시: view=smb&dialog=create

목업 첫 상태의 FIXED는 선택 예시입니다. 실제 신규 생성의 기본은 AUTHENTICATED입니다.
