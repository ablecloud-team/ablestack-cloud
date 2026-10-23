# #1431 할당 정책 선택과 요청·적용 증거 UI

## 범위

볼륨·디스크 오퍼링·컴퓨트 오퍼링 및 VM의 루트/데이터 디스크 선택에 공통 용어를 적용한다. API 계약의 requested/fixed/effective/applied/observed 값과 legacy provisioningtype을 구분한다. UI 표준 전체 재배치는 #1433, 일반 크기/IOPS 폼 확대 개선은 #1429, 잔여 API/권한 매핑은 #1432에서 진행한다.

## 정책 선택

- 새 오퍼링은 기존 thin/sparse/fat 모드와 명시적 정책 모드를 구분한다. 명시적 요청에는 allocationpolicy/allocationformat만 보내며 provisioningtype을 함께 보내지 않는다.
- 참조 스토리지와 이미지 형식을 선택해 getVolumeAllocationCapabilities를 조회한다. Up 풀·Up KVM 연결 호스트에서 검증된 agentpolicies만 선택지로 사용한다.
- 참조 풀은 오퍼링에 저장하는 바인딩이 아니다. 실제 생성 대상·형식·작업·호스트는 백엔드에서 다시 검증한다.
- RBD는 RAW off/full만, GFS2는 QCOW2 metadata/falloc/full과 RAW off/falloc/full만 선택한다. RAW Thin은 off를 그대로 보낸다.
- capability 실패·권한 없음·빈 결과는 지원 성공으로 바꾸지 않는다. 입력을 보존하고 미확인 조합 제출을 막는다. 원본과 같은 계약의 오퍼링 정의 복제는 기존 요청을 그대로 보존할 수 있다.
- 풀·오퍼링·zone이 바뀌면 부적합한 선택을 해제한다. 모든 페이지를 조회하며 오래된 응답을 반영하지 않는다.

## 상속과 복제

compute-only 오퍼링은 새 내부 디스크 오퍼링의 요청을 소유한다. 일반 디스크 오퍼링을 연결하면 그 정책이 루트 디스크에 상속되고 별도의 정책·provisioningtype을 보내지 않는다. 추가 데이터 디스크는 별도 선택한 디스크 오퍼링을 따른다.

오퍼링 정의의 복제는 실제 이미지 clone과 별도 작업이다. 복제 API의 미지원 일괄 차단을 제거하고 원본 요청을 복사하거나 명시적으로 선택한 새 요청을 적용한다. 기존 thin/sparse/fat의 이름만 복제할 때 기본 getter의 thin 값으로 덮지 않는다. 관측/적용 증거는 오퍼링 복제로 복사하지 않는다. 할당 계약은 전용 정책 편집기로 관리하며 allocation.* 내부 키를 편집 가능한 하이퍼바이저 세부 정보로 노출하지 않는다. 연결된 일반 디스크 오퍼링의 정책 소유권은 유지한다. 저장된 디스크 크기(bytes)를 복제 API의 GiB로 변환하며, 사용자 지정 크기를 생략해도 기존 용량을 정확히 상속한다.

## 표시와 수명주기

상세 탭은 기존 resource-details-list의 세로형 항목명/값 구조, 기존 항목 순서, 글꼴·간격·구분선을 유지한다. 별도 요약 그리드를 추가하지 않고, 기존 항목 다음에 요청 정책/형식, 확정 정책, 실제 적용 정책/메서드, 실제 형식, 논리 용량, 관측 할당량과 시각, 검증 상태를 같은 목록 항목으로 표시한다. 관측값과 오퍼링 상속의 설명은 해당 항목의 도움말로 제공한다. 대화상자의 요약 배치는 유지하고 동일한 표시값 생성 함수를 공유한다. 증거가 없거나 잘못됐으면 알 수 없음으로 표시하며 이름·fat 값으로 실제 할당량을 계산하지 않는다. 관측은 생성·복사 시점이며 지속 예약 또는 RBD 복제본 포함 물리 소비량이 아니다.

listVolumesMetrics의 BeanUtils 응답 복사에서도 allocation을 보존하도록 VolumeResponse getter를 제공한다. 일반 목록과 Metrics/상세 응답이 같은 증거를 반환한다.

생성 대화상자는 자동 대상 재검증과 Thin/metadata/falloc/Full의 시간·공간 비용을 안내한다. #1437의 falloc 최대 2회 새 이미지 재시도와 소진 시 안전 실패를 설명한다. 동일 정책의 오퍼링 변경은 변환을 뜻하지 않으며 명시적 물리 resize/restore/migrate/image clone을 안내·차단한다. 이동 대화상자는 명시적 정책일 때 대상 조회와 제출을 막고 같은 지원 제한을 안내한다. 볼륨 다운로드·템플릿 생성의 이미지 복사 작업도 툴팁과 비활성 상태로 안내한다. API의 기존 가드는 유지한다.

## 검증과 배포

변경 API/server 모듈과 Metrics 회귀 검증 모듈만 WSL ext4에서 Maven 빌드하고, UI 관련 테스트·lint·production build를 수행한다. 기존 배포본의 무관 메서드/항목과 운영 VM·자원을 보존한다. 31·32 정적 UI 배포는 WEB-INF/META-INF/config를 보존하고 index/assets/locales 등 정적 파일만 갱신한다. 전용 fixture의 UI→API→DB→실물 정책을 확인하고 정리한다. 전체 Cloud 빌드/CI는 요청 범위가 아니다.
