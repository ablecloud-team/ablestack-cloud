# 스냅샷 복사 UI의 StorPool 전용 옵션 제거

2026-10-24 · #1480 후속 · Epic #1473 / PR #1492

사용자 지시에 따라 ABLESTACK에서 지원하지 않는 ‘프라이머리 스토리지 복제 사용’을 스냅샷 복사 대화상자에서 제거했다. 해당 스위치, 종속 스토리지 풀 선택·목록 조회·검증과 폼 상태를 제거했다. 일반 Zone 선택과 대상 고정·최신 상태·비동기 중복 제출 보호는 유지했다.

제품 소스 변경은 `ui/src/views/storage/SnapshotZones.vue` 한 파일이다. 복사 요청은 선택한 `destzoneids`를 사용하고 기존 안전 경로가 원본 snapshot/Zone을 고정한다. `useStorageReplication`과 `storageids`는 이 UI에서 전달하지 않는다. API/Java/DB 정의·백엔드 기능은 변경하지 않았다. 기존 Primary/Secondary 사본 정보 표시는 유지한다. 후속 #1481에서도 StorPool 전용 옵션은 ABLESTACK UI에 노출하지 않는다.

기존 UI 안전성 집중 검사 5 suites / 69개, lint 및 production UI build가 통과했다. Java 변경이 없어 Maven 빌드는 실행하지 않았으며 전체 Cloud CI도 실행하지 않았다.

31 GFS2·32 RBD에 새 전용 1GiB DATA 볼륨·스냅샷을 각 1개씩 준비했다. 실제 Mold에서 변경 전 옵션 노출과 변경 후 Zone만 남은 대화상자를 대조했다. 라이트·다크 및 1280×720·459×488에서 VM 공통 md 셸과 header/body/footer 배치를 확인했다. 대화상자를 열 때 `listStoragePools`가 호출되지 않고 복사·삭제 제출이 추가되지 않은 것을 브라우저 네트워크에서 확인했다. 두 환경은 단일 Zone이므로 실제 Zone 간 복사는 실행하지 않았으며 일반 Zone 입력·미선택 확인 비활성 및 기존 안전성 검사를 확인한 범위다.

정적 UI 배포는 활성 webapp의 WEB-INF/META-INF·설정·서버 파일을 보존하고 921개 정적 파일의 해시를 검증했다. 관리 JAR SHA256과 Mold MainPID가 전후 동일하며 재시작하지 않았다. `/client/` HTTP200과 Mold active를 확인했다.

전용 자원은 정상 API로 정리했다. 활성 API/DB 참조, NFS 파일, Primary 이미지와 RBD trash 잔여0을 확인했으며 기존 snapshot/volume ID와 보호된 Windows/WSL FT 작업트리 hash를 보존했다. 상세 결과는 `validation.json`, 원본 화면과 DOM은 `browser/`, 소스·증거 해시는 `manifest.json`에 있다. 과거 #1480의 `f95aa4195dc` 검증 기록은 당시 구현의 이력이며 이번 옵션 제거 후 UI는 이 보고서를 기준으로 확인한다.
