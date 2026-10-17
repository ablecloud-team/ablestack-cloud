# #1401 CNI 본문 보기·복사·다운로드 설계

## 사용자 동작과 API 계약

CNI 상세의 본문은 별도 `cni.content` 탭에서 확인한다. 기본값은 엄격한 UTF-8 해석이며 Base64 전환은 저장 문자열을 그대로 표시한다. 상세 메타데이터의 중복 Base64 행은 제거한다. 탭은 기존 ResourceView 라우터 이력과 `cnireturn` 목록 복귀 문맥을 사용한다.

`CniContent`는 기존 `UserDataContent` 공통 화면을 CNI 모드로 연결한다. CNI 조회는 `listCniConfiguration/listcniconfigurationresponse.cniconfig`로 한정하고 일반 `listUserData` 권한에 의존하지 않는다. UUID·이름·소유자/도메인 또는 프로젝트를 고정한 `cniIdentityParams/cniIdentity`로 정확한 1개 응답을 확인한다. 무필터 조회로 대체하지 않는다. 선택된 프로젝트와 원본 프로젝트가 다르면 요청하지 않는다.

본문 유틸리티 `userDataContent`를 재사용한다. canonical Base64 검증 후 원래 Uint8Array와 엄격하게 해석한 UTF-8을 함께 보관한다. BOM·CRLF·마지막 개행·특수문자는 변환하지 않는다. 본문은 Vue 텍스트 보간의 스크롤 가능한 `pre/code`로 표시한다.

## 데이터 상태와 내보내기

| 상태 | 표시와 제어 |
| --- | --- |
| 정상 UTF-8 | 원문/Base64 보기·선택 형식 복사/다운로드, 저장 바이트 수 |
| 비 UTF-8 | 경고, Base64 보기/복사/다운로드. 파일의 Base64를 디코딩하면 저장 바이너리 바이트와 일치 |
| 빈 기존 본문 | 저장 본문 없음, 복사/다운로드 비활성 |
| 손상 Base64 | 형식 오류, 본문 제거·내보내기 차단 |
| 일반 네트워크/서버 실패 | 이전 본문이 있으면 명시적으로 오래된 본문으로 표시, 내보내기 차단·재시도 |
| 부재/권한/식별 불일치 | 이전 본문 제거·내보내기 차단 |
| 요청 중/상위 상세 조회 미완료 | 복사/다운로드 차단 |

내보내기는 활성 탭·현재 권한·로그인/프로젝트/대상/경로와 성공한 조회 범위의 일치·조회 완료·비어 있지 않은 본문을 모두 요구한다. CNI는 상위 상세의 `loading/resourceActionReady`도 확인한다. AutogenView가 CNI 상세의 현재 범위/UUID·요청 중·마지막 실패를 readiness로 내려주며 본문은 이 값이 false인 동안 내보내기를 차단하고 안내한다. 상위 조회가 시작되면 하위 요청 세대를 폐기하고 완료 후 본문도 새로 조회한다. 같은 범위에서 loading 스피너를 생략하는 갱신 경로도 포함한다. 범위 변경은 본문/세대를 초기화하고 이전 응답은 적용하지 않는다. 요청 중 중복 조회는 합친다.

복사는 기존 브라우저 clipboard API와 HTTP 환경의 copy 이벤트 fallback을 재사용한다. fallback은 textarea 개행 정규화 대신 clipboardData에 원래 문자열을 넣는다. 다운로드는 Blob에 원래 Uint8Array 또는 선택한 Base64 ASCII를 넣는다. 파일명은 CNI UUID를 사용한다. 이름과 본문을 HTML로 해석하지 않는다.

## 검증과 배포

공통 UserData/VM/미리보기 회귀와 CNI API·권한·식별·프로젝트·오류·늦은 응답·바이트/복사 계약을 집중 테스트한다. 31번 실제 UI에서 OS 클립보드와 다운로드 파일의 바이트/해시를 API 저장 본문과 비교한다. 전용 자원으로 빈/손상 legacy 본문을 재현하고 정상 원문을 복원한 뒤 API로 정리한다. 기존 데이터의 API/DB 기준값은 보존한다.

UI 소스만 변경하므로 Maven 빌드는 필요하지 않다. WSL ext4에서 UI lint/production build 후 정적 자산만 배포하며 WEB-INF/META-INF·config.json·JAR·Mold PID를 보존한다. 전체 Cloud 빌드와 이 Epic PR의 병합은 이번 작업에 포함하지 않는다.
