# #1445 설계 실험 자료

제품 구현이 아니다. design.ko.md의 현재 구현/제안/실증/향후 인수를 구분한다.

- native-design-validation.json: 실제 SystemVM 설치 모듈의 격리 실행. 자원 부족은 metric 주입, protocol child는 안전한 Python으로 교체했다. 실제 프로토콜 apply는0회다.
- sketches/native_design_probe.py: 실증에 사용한 코드. 별도 private stage를 사용하고 설치 Transaction의 ROOT를 절대 사용하지 않는다. 기존 실험 경로에는 재실행 방지 sentinel이 있다. 새로운 전용 경로와 before inventory를 준비한 뒤에만 실행한다.
- sketches/resource_sketch.py: 제안 정책·확인 계약의 실행 스케치. 제품 runtime에서 import하지 않는다.
- Java 두 스케치: 기존 빌드 classpath로 실행한 실제 Serializer와 Plan/DAO 계약 검증. DTO/DAO는 스케치/mock이며 실제 DB 쓰기는0이다.
- preservation.json: 보호 파일21개·daemon PID/InvocationID·boot ID 동일, 실제 journal18개 유지. private before-image/journal 제거 결과.
- mockup: 실제 Vue3·AntD·MoldDialog/Actions·Mold less를 import한 독립 설계 화면. API에 연결하지 않는다. 선택 필드 발췌이며 실제 폼의 재배치를 뜻하지 않는다.

## 독립 목업 재현

canonical WSL에 UI 의존성이 설치된 환경에서 이 mockup 폴더로 이동한다.

~~~bash
mkdir -p public
cp index.html public/index.html
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
python3 -m http.server 8795 --bind 127.0.0.1 --directory public
~~~

로컬 index.html?mode=ready&theme=dark, mode=blocked, mode=running, theme=light가 검토 fixture다. 제품 UI build/deploy 명령이 아니다. 생성된 app.js와 probe class는 저장소에 넣지 않았다.
