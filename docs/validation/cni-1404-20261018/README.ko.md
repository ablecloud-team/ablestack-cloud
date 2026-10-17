# CNI 파일 가져오기·전용 도움말 31번 검증 (#1404)

2026-10-18 KST. Epic #1395 / 누적 Draft PR #1409 / dhslove/epic-1395-cni. 최신 Europa 기준 dc31262567f2afe5f6278fae61c1cef7e307fe3e, 시작 HEAD cf467a9c066ec6bb5ff3c426c780a639b7fcd6b4. WSL ext4 작업 사본에서 구현했고 Windows의 기존 FT 변경은 수정하지 않았다.

## 구현 결과

- CNI 등록에 텍스트/명시적 Base64 파일 가져오기를 추가했다. 읽기 전 크기 제한, 엄격한 UTF-8, 빈 본문/잘못된 Base64/압축 파일 오류를 표시한다. 기존 canonicalCni 검증을 재사용한다.
- 파일 선택은 MoldDialog 미리보기만 연다. 이름·변수·기존 본문은 ‘본문에 적용’ 전까지 유지한다. 적용 후에는 Base64 입력 계약으로 원본 BOM·CRLF/LF·끝 개행 바이트를 보존하고 등록 시 현재 서버 제한으로 다시 검증한다.
- 취소/재선택/언마운트/소유 범위 변경 뒤 늦은 읽기를 무시하고, 읽기 중 제출과 중복 선택을 차단한다. 컴포넌트가 제거될 때 부모 읽기 잠금도 해제한다.
- 목록·상세·등록의 CNI 도움말을 앱에 포함된 한글/영문 문서로 연결했다. cloud-config와 Jinja 형식, 인코딩/크기, 변수 수동 선언·메타데이터 전달·AS_NUMBER 특수 표기와 소유 범위를 설명한다. 일반 UserData/SSH 도움말 URL은 유지한다.
- 본 화면에 상시 설명 문구를 추가하지 않았다. VM 공통 MoldDialog/detail-tab-toolbar/UserDataContent를 사용한다. 모바일 실측에서 scoped CSS가 포털 내부에 적용되지 않던 코드 높이를 CNI 미리보기 전용 클래스의 45vh 제한으로 보완했다.

[설계](../../design/cni-file-import-help-1404.ko.md)

최종 스크린샷 검토에서 네이티브 파일 입력이 중복 노출되는 공통 폼 CSS 우선순위 충돌을 추가로 발견했다. CNI 컨테이너/입력 클래스로 우선순위를 보완해 다시 빌드·배포했다. 최종 24개 조합은 입력의 computed display:none·너비/높이 0도 확인했고 숨겨진 입력의 버튼 기반 파일 선택 → 적용 → 등록/다운로드 → API 바이트 대조를 새 UUID에서 다시 통과했다. [보완 전](screenshots/native-file-input-before-fix.jpg) · [최종 등록](screenshots/final-deliverable.jpg)

## 실행 검증

- 최종 **9 suites / 247 tests PASS**, 전체 UI lint 및 production build 성공. Browserslist 갱신 안내와 기존 번들 크기/성능 경고는 남는다. Java 변경·Maven 모듈 빌드·전체 Cloud 빌드/CI는 없다.
- 실제 UI **79 checks (도움말 레이아웃 16 checks 포함)**, 실제 API **35 checks**. 저장 원문과 파일 다운로드/복원 바이트를 대조했다. Base64 바이너리는 기존 본문 탭 계약에 따라 Base64 텍스트로 내려받으며 디코딩한 원문도 일치한다.
- 최종 등록/미리보기/파일 오류 **3개 상태 × 1440/1024/768/390px × 라이트/다크 = 24개 조합**을 확인했다. 한글/영문 도움말은 별도 **16개 언어·테마·화면 폭 조합**을 확인했다.
- 실제 파일 선택: 작은 텍스트, 한글/UTF-8 BOM/CRLF/끝 개행, 줄바꿈이 있는 Base64, Base64처럼 보이는 일반 텍스트, 명시적 바이너리 Base64, 15,162바이트의 큰 본문, 긴 파일명.
- 실제 오류 파일: 빈 파일, 공백만 있는 파일, 잘못된 UTF-8, UTF-16, 원문 크기 초과, Base64 정규화 후 크기 초과, 잘못된 Base64, Base64 파일의 BOM, ZIP 확장자. 모두 기존 입력을 유지했다.
- 로컬 파일 미리보기에서 실제 클립보드 복사/다운로드와 Base64 전환도 원문 바이트와 일치했다. [미리보기 내보내기](screenshots/final-local-preview-export.jpg).
- 실제 미리보기 취소/동일 파일 재선택/명시적 적용/미리보기 Ctrl+Enter 오등록 방지/Escape로 미리보기만 닫기, 프로젝트 등록·다운로드·소유자 허용/비회원 거부, 일반 UserData 등록과 SSH 도움말 회귀를 확인했다.
- 읽기 실패/지연은 테스트 파일 2개에 한정한 브라우저 File.arrayBuffer 제어로 확인했고 원래 함수/프로퍼티를 복구했다. 실제 OS I/O 고장으로 주장하지 않는다. 빈 선택/파일 선택 취소 분기는 집중 테스트에서 확인했으며 자동화가 제공하지 않는 OS 선택창 취소 버튼 클릭을 실검증으로 세지 않았다.
- 최종 배포 뒤 코드 최대 높이 450px(45vh), 모달 푸터/페이지 폭/내부 스크롤·키보드, 파일 선택부터 등록/다운로드까지 다시 확인했다. 브라우저 테마/뷰포트 임시 설정과 제어를 복원했다.

[UI 증거](ui-evidence.json) · [API 검증](api-checks.json) · [저장 바이트/해시](stored-bytes.json) · [테스트 요약](tests-summary.txt) · [247개 결과](focused-tests.txt) · [lint](ui-lint.txt) · [production build](ui-build.txt) · [배포](deployment-verification.txt)

## 화면과 기준

| 화면 | 증거 |
|---|---|
| 변경 전 등록 | [이전 화면](screenshots/before-register-1440.jpg) |
| 최종 등록 | [라이트 데스크톱](screenshots/final-register-light-1440.jpg) · [다크 모바일](screenshots/final-register-dark-390.jpg) |
| 최종 파일 미리보기 | [라이트 데스크톱](screenshots/final-preview-light-1440.jpg) · [다크 모바일](screenshots/final-preview-dark-390.jpg) |
| 입력 보존/오류 | [오류](screenshots/invalid-file-keeps-draft.jpg) · [읽기 실패](screenshots/controlled-file-read-error.jpg) · [읽기 중 잠금](screenshots/file-read-pending.jpg) |
| 도움말 | [한글](screenshots/help-ko-light-1440.jpg) · [영문 모바일](screenshots/help-en-dark-390.jpg) |
| VM 기준 회귀 | [목록](screenshots/vm-list-regression-1440.jpg) · [상세](screenshots/vm-detail-regression-1440.jpg) |
| 키보드/최종 정리 | [미리보기](screenshots/final-keyboard-preview.jpg) · [원래 CNI 3개](screenshots/final-cleanup-original-cni.jpg) |
| 일반 소비자 | [일반 UserData](screenshots/ordinary-userdata-register-regression.jpg) · [SSH 도움말](screenshots/ssh-documentation-regression.jpg) |

## 최종 배포·정리

최종 정적 **879개 파일 해시 일치**. 백업 `/root/cni-1404-ui-20261018-015317`. 최종 index SHA256 `d4fc0973919d39391938ba362889a38a0219a62c4b40f49cb536e4b9f3542766`. [배포 manifest](cni-1404-manifest.json) · [정적 묶음 해시](static-artifact.sha256)

활성 경로 `/usr/share/cloudstack-management/webapp`의 정적 파일만 갱신했다. WEB-INF는 전후 존재, META-INF는 기존과 같이 부재다. 설정·JAR·Mold PID 1510297을 보존했고 서비스 active, /client/ 및 양 언어 도움말 HTTP 200과 현재 manifest의 FTCTL/CNI 표식을 확인했다. 관리 서비스 재시작·호스트 패키지 변경 없음.

정상 API로 **CNI 9개·일반 UserData 1개·프로젝트 1개·일반 계정 2개·도메인 1개**를 정리했다. 프로젝트 계정과 사용자도 함께 제거됐다. 활성 잔여 13종 모두 0, 기존 **CNI 3개 API 전체·Kubernetes 7개 안정 필드/노드 UUID·DB 36행**이 동일하다. DB 기준선 SHA256 `2d61f159980ac4fa490f62c74e16ce6024ac1f8bb9210c2c6caaa366c74357f7`. 새 클러스터·VM·네트워크는 만들지 않았다. [삭제 영수증](cleanup-receipts.json) · [정리/보존 결과](cleanup-summary.json)

원격 URL/압축 해제/스크립트 실행/실제 CNI 설치, 변수 자동 추출·치환 결과, 복제/버전 기능은 이번 범위가 아니다. 다음 **#1405 변수 추출·Kubernetes 치환 미리보기 → #1406 복제 → #1407 이력·비교**를 진행한다. Epic #1395는 10/13, PR #1409는 Draft를 유지한다. 신규 파일 UI의 #1408 표준 검증 결과를 Epic 점검 목록에 반영했다.
