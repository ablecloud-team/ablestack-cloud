# #1447 설계 자료

이 폴더는 **제품 구현이 아닌 설계·실증·목업**이다. 인수 기준은 미완료이며 #1447을 닫지 않는다.

- [코드 수준 설계](design.ko.md): 범위, 배포/소스 확인, 상태 전이, API·DB·native 변경 지점, 이행 및 구현 인수 계획.
- `evidence/A-*`: 13번에 실제 배포된 화면. `B-*`: 아래 독립 Vue3 목업 화면.
- `evidence/management-probe.json`: 비특권 nobody(uid65534)에서 기존 서명/파일 계약을 재사용한 비특권 산출물 검사.
- `evidence/native-probe.json`: 실제 SystemVM의 설치 verifier로 실행한 격리 실증.
- `evidence/native-before.json`, `native-after.json`, `preservation.json`: 라이브 런타임·9파일 hash·boot·Samba PID/InvocationID·관리 JAR/UI 보존.
- `evidence/code-identity.json`: 관리 JAR의 4개 class와 canonical WSL compiled class 일치, 등록 archive/manifest hash 및 실제 다운로드 일치.
- `evidence/current-runtime-api.json`: 실제 UI 조회 응답의 본문만 저장. 세션/인증 헤더는 포함하지 않는다.
- `sketches/catalog_design_probe.py`: 설계 검증용 스케치. 제품 API/DAO/fence 구현이 아니다. 14개 정책 사례는 독립 순수 함수이며 기존 native 기능과 구분된다.

## 목업 실행

canonical WSL의 기존 UI 의존성을 사용한다. 추가 npm 설치나 제품 소스 변경이 필요 없다.

```bash
cd /root/work/ablestack-cloud/docs/design/sharedfs-1447-catalog-20261025/mockup
NODE_OPTIONS=--openssl-legacy-provider node build.cjs
cp index.html public/index.html
python3 -m http.server 8797 --bind 127.0.0.1 --directory public
```

`http://localhost:8797/index.html?view=list&theme=dark&lang=ko`를 열면 된다.
view는 list/detail/consumer, detail의 tab은 details/verification/usage/audit, dialog는 register/revoke/select다. theme=light와 lang=en도 제공한다.

실제 Vue3·AntD·ResourceLayout·MoldDialog·MoldDialogActions·Mold 공통 style을 import한다. 고정 sidebar·InfoCard 표현과 행 데이터는 fixture이며 API에 연결되지 않는다. 구현 시에는 실제 ResourceList/ResourceView/InfoCard를 사용한다. 생성된 public 번들은 git에서 제외한다. 목업 전이는 실제 서비스 검증 결과가 아니다.

## 실증 경계

두 플랫폼에서 정상 서명9파일을 읽기 전용 stream 검사하고 변조 서명을 거부했다. 실제 SystemVM은 private runtime/state/trust/entrypoints/lock 경로에서만 begin/finalize/verify/activate/rollback을 수행했다. live current·공유 설정·DATA에는 적용하지 않았다. native가 catalogState/revision을 무시한다는 관찰과 파일 재조회 기반 fence 스케치는 아직 구현되지 않은 최소 보완 지점을 확인한 것이다.

실제 OS 재부팅을 통한 새 fence 내구성·새 API/DAO의 수명주기·실제 모듈 배포 인수는 수행하지 않았다. 구현 후 검증할 항목이다. 이번 시험은 새 VM/디스크를 만들지 않았고 UI에서 기존 ROOT/DATA의 SPARSE 값을 확인했다. 주입 스크립트와 임시 경로는 정리했다.
