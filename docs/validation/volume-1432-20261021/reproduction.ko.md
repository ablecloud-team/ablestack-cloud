# #1434에서 재사용할 권한별 실행 절차

이 문서는 정리된 fixture의 UUID를 재사용하는 절차가 아니다. 31/32 각각 전용 이름 접두어로 자원을 새로 만들고 실제 응답 UUID를 대응시킨다. 인증 값·세션·원본 운영 inventory는 저장소에 게시하지 않는다.

1. 테스트 전 전체 운영 VM/볼륨/오퍼링 inventory를 보존한다. Root 아래 A/B 도메인, A의 DomainAdmin/User/Peer, B의 Foreign, User 소유 Active 프로젝트를 만든다. User/Project별 L2 네트워크와 Stopped VM, 공용/각 도메인 사설 디스크·컴퓨트 오퍼링, 이름만 수정해도 보존되어야 할 purge=true 컴퓨트와 External: 테스트 값을 준비한다.
2. 두 환경에서 각 계정으로 로그인해 listApis를 저장한다. 프로젝트는 같은 User 로그인에 실제 projectid를 넣는다. [등록표](api-visibility.json)를 기대 계약과 대조하되 등록을 객체 접근 성공으로 간주하지 않는다.
3. [실행 행렬](api-execution-matrix.json)의 cluster/actor/case별 요청을 새 UUID에 적용한다. 허용 동작은 응답·재조회·DB/이미지에 반영됐는지, 금지 동작은 오류/빈 결과와 원본 필드 불변인지 각각 판정한다.

| 역할 | 허용 실행 | 필수 거절·보존 |
|---|---|---|
| Admin | 볼륨 부분 수정·연결/분리·삭제 보호, 컴퓨트 purge true/false 및 이름/상태/domain/zone 부분 수정, 계정↔프로젝트 이전, 오퍼링 접근/복제/비활성화 | 연결/스냅샷 볼륨 이전, 삭제 보호 위반, RAW checkVolume. 다른 오퍼링의 External 값과 내부 설정 보존 |
| DomainAdmin | 자기 도메인의 실제 허용 API, Stopped VM 연결/분리, 자기 도메인 계정 이전, 사설 오퍼링 편집/복제 | 기본 역할 updateVolume 미등록, 공용 수정·zone 변경·다른 도메인 범위 부여/볼륨 변경/이전. 권한 없는 태그 필드 숨김 |
| User | 자기 볼륨 생성/조회·연결/분리·삭제, 코멘트 생성/조회, 페이지 있는 이벤트 | 관리 API 미등록, 타 Peer/Foreign 볼륨·관련 조회·오퍼링 변이, 타 도메인 사설 오퍼링 사용. 거절 전후 대상 불변 |
| Project | 프로젝트 생성/조회·연결/분리·삭제·관련 조회 | 출발 프로젝트 자동 파라미터가 이전 목적지로 섞이지 않음. 일반 사용자 관리 작업 숨김 및 타 소유자 UUID 조회/변경 거절 |

4. 미연결 Ready DATADISK에 host qemu-io로 64KiB 0x5a를 기록한 후 UI 계정→프로젝트→계정 이전을 실행한다. 저장 요청은 accountid/projectid 하나만 포함해야 한다. 실제 새 소유권과 resource count를 확인하고 path/size/storage/allocation 불변 및 동일 패턴 재개방 읽기를 대조한다. 운영 VM의 파일에 쓰지 않는다. 31 QCOW2와 32 kernel RBD RAW를 각각 확인한다.
5. 실제 스냅샷을 생성한 별도 볼륨에서 이전 UI의 사전 차단·POST 0건과 직접 API의 거절을 확인한다. compute 편집은 이름만 수정할 때 purge/external 값 생략, true/false 명시 변경, 외부 정리 확인 전 POST 0건, 확인 후 해당 오퍼링만 정리, 외부 편집과 복제 키 필터를 검증한다. 비활성 오퍼링·도메인/존 UUID 선택·무변경 POST 0건도 포함한다.
6. 새 대화상자 및 Events/Comments/정기 스냅샷에 대해 한 API XHR만 실패시킨다. 읽기 실패 재시도, 저장 결과 불명 입력 보존·중복 차단, 기존 정책 행 보존과 실제 재조회, 프로젝트/로그인/자원 전환의 오래된 응답 폐기를 구분한다. 사용한 가로채기를 완전히 해제하고 브라우저 설정을 복원한다. UI [기록](ui-evidence.json)의 controlled=true는 실제 서버 장애 증명이 아니다.
7. 후보/정책의 다중 페이지는 일반 목록과 500건 경계를 별도로 검증한다. 이번 실제 목록은 그 경계보다 작았고 추가 페이지 경계는 focused 검사에서 다뤘다. #1209 VM 생성 선택기는 별도 수정 대상이다. #1434에서는 실제 충분한 후보와 서버 필터/총수도 대조한다.
8. 스냅샷/정책·코멘트 제거, 계정 및 프로젝트 양쪽 목록을 명시 조회해 모든 DATA/ROOT 볼륨과 VM 정리, 네트워크/프로젝트/계정/도메인 제거, 오퍼링 Inactive를 확인한다. Destroyed 스냅샷 이력은 활성 잔여로 세지 않되 ref_cnt와 실제 파일 제거를 확인한다. primary 이미지·snapshot 파일·VM 정의 0, 운영 inventory 동일, Mold/WEB-INF/HTTP/호스트 및 배포 해시를 재검증한다.

Running guest I/O, 장기 통계, KMS/Block 전용 환경과 물리 수명주기 전체 회귀는 이번 개별 검증으로 대체하지 않는다. #1434의 통합 환경·지원 범위에 맞춰 실행 또는 명시적 미실증으로 판정한다.
