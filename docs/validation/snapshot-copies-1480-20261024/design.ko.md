# #1480 사본 조회 계약과 UI 설계

## 문제와 변경

같은 Zone의 Primary/Secondary 및 복수 스토리지 참조를 Zone 하나로 덮어쓰던 UI와, 태그 검색 GROUP BY snapshot.id 때문에 조회 건수와 행 수가 달라지던 backend를 수정한다. Primary storageid 조회가 Image 역할을 찾던 조건도 바로잡는다.

showunique=true는 기존 논리 스냅샷 단위이며 copy 필드를 추가하지 않는다. showunique=false는 기존 snapshot/store 조인 행 및 정보 없는 Zone placeholder 단위로 페이지와 count를 일치시킨다. 서버 응답 순서를 유지한다. copy.id는 snapshot UUID + 기존 snapshotStorePair + Zone UUID의 이름 기반 UUID이며 DB 내부 번호를 새 필드에 노출하지 않는다. datastore가 없는 placeholder pair에도 Zone을 포함해 다른 Zone을 구분한다.

copy는 역할, datastoreknown, 참조 상태, availability(Ready/NotReady/Unknown), snapshot 시점 가상 용량, 확인된 다운로드 상태·진행률·오류를 제공한다. virtualsize는 캡처된 snapshot.size이며 사본의 물리 크기로 표현하지 않는다. 누락한 상태나 store 정보는 Unknown, 누락한 용량은 null이다. Ready는 Cloud 참조 상태이며 실시간 실물 검사 성공을 의미하지 않는다. 저장소 UUID/이름 및 오류의 기존 권한 규칙을 유지한다. primary/secondary/storageid/imagestoreid의 모순 조합은 431로 거부한다.

Zone 표는 서버 현재 페이지의 사본만 그룹화하며 각 Zone 아래에 저장소 사본을 별도 행으로 표시한다. 부모 행의 건수는 현재 페이지 내 사본 수임을 설명한다. 총합과 페이징은 서버 참조 단위이다. 같은 Zone이 다음 페이지에 이어질 수 있으며 임의 전체 조회나 클라이언트 중복 제거로 count를 바꾸지 않는다. Zone 단위 선택만 허용하며 갱신 시 최신 행에 선택을 다시 연결한다. 페이지·스냅샷·프로젝트·세션·조회 권한 변경은 이전 선택을 폐기한다.

503 같은 일시 실패는 같은 페이지의 기존 행과 count를 유지하고 stale 안내 및 재시도를 제공한다. 401/403/권한 오류는 기존 행·count·선택을 지운다. 요청 세대와 보안 scope가 달라진 늦은 응답은 화면을 다시 채울 수 없다. 삭제로 현재 페이지가 사라지면 마지막 유효 페이지로 이동한다. 작업은 조회 실패 중 비활성이다.

Zone 링크는 /zone, Primary는 /storagepool, Secondary는 /imagestore로 연결한다. zoneid 삭제는 Zone 전체 영향으로 명시한다. bulk 취소 후 단건 삭제에 오래된 bulk flag를 재사용하던 부분도 수정한다.

변경한 복사 대화상자는 VM 표준의 MoldDialog md(720px), 분리된 header/body/footer, MoldDialogActions, compact 대상 확인과 테마 토큰을 사용한다. 폼 항목 여백은 24px로 유지하여 중첩을 피한다. 복사 지원 조건과 실제 대상 전송 개선은 #1481이며 이 이슈에서 통제 fixture를 정상 복사 성공으로 주장하지 않는다.

## 검증 경계

31 GFS2와 32 RBD에서 각각 새 1GiB DATA 볼륨 2개를 API로 생성하고 앞뒤 1MiB를 0x5a/0xa5로 기록한 뒤 정상 snapshot을 생성한다. API/UI/DB 및 실제 NFS/RBD 데이터로 정상 경로를 검증한다.

다중 Zone, 많은 저장소, 진행/실패/누락 상태, 페이지 경계를 재현하기 위해 전용 Disabled Zone, readonly NFS alias store와 전용 snapshot의 참조 fixture를 별도로 구성한다. Ready fixture는 실제 NFS 파일과 SHA256·qemu-img·qemu-io 검증을 동반한다. Copying fixture는 1MiB 부분 파일, Failed fixture는 파일 없음으로 구성한다. 이들은 제품 copySnapshot 실행 증거가 아니다. 기존 운영 store와 참조는 변경하지 않는다. fixture 종료 시 전용 참조·store·파일·mount·프로젝트·Zone·volume·snapshot을 정리하고 운영 inventory 및 보호 FT 작업트리 hash를 대조한다.

변경 Maven 모듈(api, engine/schema, server)만 WSL ext4에서 빌드한다. UI 집중 검사·lint·production static build를 수행한다. 전체 Cloud 빌드와 GitHub Actions 실행은 요청하지 않았으므로 수행하지 않는다.
