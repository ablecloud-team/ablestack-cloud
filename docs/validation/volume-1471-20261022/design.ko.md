# #1471 최초 기동 전 볼륨 연결의 null pod 수정 설계

- 원인: getPoolForAllocatedOrUploadedVolumeForAttach의 primitive long 언박싱으로 null pod가 예외가 된다. 클러스터가 뒤에 확인되는 경로도 그 전에 실패한다.
- 선택한 계약: 기존 즉시 할당 경로를 유지한다. 최초 기동 전에도 Zone와 오퍼링/DiskProfile·기존 cluster/host 제약을 기존 allocator에 전달해 적격 저장소를 선택하고 Allocated/Uploaded DATA를 materialize한 뒤 연결한다. 새 지연 할당·pod=0 치환은 도입하지 않는다.
- 공통화: 실제 연결의 getPool이 getSuitablePool 공통 구현을 사용한다. null pod는 DAO 조회 없이 allocator의 Zone 범위로 전달하고 알려진 cluster/pod가 DB에서 사라진 경우에는 범위를 넓히지 않고 명확히 거절한다.
- 보존: DiskProfile 태그·로컬 여부·재생성 가능 여부·크기·템플릿/하이퍼바이저·VM/host/cluster, 기존 ROOT의 pool 재사용, Ready 무변경·Uploaded/PowerFlex 제한·할당 실패 전달을 보존한다. 적격 pool 없음은 연결/생성 성공이 아니다.
- 구현 범위: server 모듈의 VolumeApiServiceImpl 두 메서드와 관련 회귀. API/DB schema·UI·Agent/qemu 변경은 없다.
- 배포: WSL ext4에서 변경 server 모듈만 package한다. 활성 fat JAR에 빌드된 두 메서드만 반영하고 나머지 메서드/엔트리는 바이트 비교로 보존한다. 각 실제 배포 JAR 조합의 JUnit 검사 후 31/32 관리 서버를 순차 재기동하며 UI/WEB-INF/config·DB schema·운영 VM을 보존한다.
- 검증: 원래 startvm=false + ROOT Allocated/pod/last_host null + DATA Allocated 조건을 계정/프로젝트 양쪽에서 수정 전 재현하고 같은 VM/볼륨으로 수정 후 UI 연결한다. 이후 첫 기동·disk mapping, 신규 정책·Ready/Uploaded·Running/기동 이력 있는 Stopped·부적격 태그 실패를 구분해 검사한다.
- 종료: 실제 API/DB/호스트·실물 증거, 전용 자원 정리와 운영 inventory/FT 작업 보존을 대조한다. 전체 Cloud/Release CI는 별도 명시 요청 없이 실행하지 않는다.

## 실제 클러스터 검증에 따른 보완

Zone 조회만으로는 CLUSTER/HOST 범위 할당기가 pod 부재로 후보를 반환하지 않는다. Zone 조회 실패 시에만, VM의 pod/cluster/host 제약이 모두 없을 때 해당 Zone의 실제 Enabled pod들을 기존 할당기에 전달한다. 다른 Zone 및 Disabled pod는 제외한다. 같은 DiskProfile과 VM을 전달해 태그·용량·하이퍼바이저·명시적 풀 제약을 유지한다. VM 배치 값은 변경하지 않으며, 후보 소진 시 명확한 오류로 실패한다. 기지정 pod/cluster/host가 있는 경우 조회 범위를 확장하지 않는다.
