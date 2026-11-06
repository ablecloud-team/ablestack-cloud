#1446 설계 자료

상태: 설계 및 격리 실증만 완료. 제품 구현·배포·실제 업그레이드 인수는 미착수이며 이슈는 OPEN이다.

- [코드 수준 설계](design.ko.md)
- evidence/: 현재 배포 코드/API 대조, 격리 시험 결과, 보존 확인, A 현재 화면과 B 개선 목업
- mockup/: Vue3 + AntD 및 기존 Mold ResourceLayout/MoldDialog를 사용하는 설계 전용 UI. 제품 API를 호출하지 않는다.
- sketches/: 실제 설치 updater의 동작을 격리 경로에서 확인한 시험 코드. 제품 패치가 아니다.

## 목업 재현

canonical WSL 저장소의 ui/node_modules가 준비된 상태에서 mockup 디렉터리에서 실행한다.

```bash
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
python3 -m http.server 8796 --bind 127.0.0.1 --directory public
```

브라우저에서 `http://localhost:8796/index.html?mode=ready&theme=dark`를 연다. `theme=light`, `mode=current`, `mode=unknown`, `view=prepare&state=prepared`도 제공한다. public/ 빌드 산출물은 Git에서 제외한다. fixture 성공 표시는 실환경 검증 결과가 아니다.

## SystemVM 격리 시험 해석

native_design_probe.py는 당시 설치된 RuntimeUpdater 및 복사한 실제 builder/CLI에 대해 실행됐다. 실행 결과는 evidence/native-design-probe.json이다. 일시 시험키는 라이브 신뢰 목록에 설치하지 않았고 제거했다. 실제 activate/protocol apply는 호출하지 않았다.

이 스크립트를 운영 경로 또는 이전 시험 경로에서 무심코 다시 실행하지 않는다. 재현이 필요하면 새 전용 private 경로를 만들고 runtime/state/trust/entrypoint/lock의 5개 root가 모두 격리됨을 확인한다. 저장소의 tools/build/build-storage-runtime-bundle.sh를 그 private 경로에 복사하고, stage 상수·sentinel·입출력 경로를 새 시험용으로 조정한다. 라이브 코드·설정·계정 파일과 데이터 볼륨을 변경하는 용도로 사용하지 않는다.

정상 fixture 서명 시험은 운영 dev/release 개인키의 존재나 정식 배포를 입증하지 않는다. 정식 서명 bundle과 실제 UI 적용·네 프로토콜 클라이언트 I/O 인수는 구현 이후에 별도 수행한다.
