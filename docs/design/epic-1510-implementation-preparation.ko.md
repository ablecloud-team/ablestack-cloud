# Epic #1510 구현 준비

준비일: 2026-10-28 (KST)

## 작업 기준

- Epic: [#1510](https://github.com/ablecloud-team/ablestack-cloud/issues/1510).
- 구현 브랜치: `dhslove/epic-1510-vm-clone` (`origin`: `dhslove/ablestack-cloud`).
- Europa 기준: `37bceae693041b9c79cc52084053b050b577029d`. 원격 최신 ref를 확인했으며 조사 커밋 `76a82f3c29eb82663f261cb50aa6275a5e02d857`을 포함해 분기했다.
- Windows 편집 작업 사본: `C:\Users\ablecloud\.codex\worktrees\vm-clone-audit-20261028\ablestack-cloud`.
- WSL ext4 구현/변경 모듈 빌드 작업 사본: `/home/ablecloud/work/dhslove/cloud-epic-1510-vm-clone`.
- 기존 Windows/WSL FTCTL 브랜치와 다른 작업 사본을 전환하거나 초기화하지 않는다.
- [실환경 조사와 재현 근거](../validation/vm-clone-audit-20261028/report.ko.md), [관측 요약](../validation/vm-clone-audit-20261028/evidence-summary.json), [시험 자원 정리](../validation/vm-clone-audit-20261028/cleanup-summary.json)를 구현 기준으로 보존한다.

## 필수 구현 원칙

명시적 할당 정책의 존재만으로 기존 기능을 일괄 차단하지 않는다. 복제·템플릿 생성·다운로드를 실제 사용할 수 있도록 복원한다. UI 차단/설명만 추가하거나 allocation 세부정보를 제거해 legacy로 우회하는 방식으로 완료 처리하지 않는다.

원본의 요청 정책·형식·실제 적용 증거와 관측값을 보존한다. 새 볼륨의 적용/관측 증거는 새 작업 결과로 확정하고 원본의 과거 관측을 그대로 복사하지 않는다. 원본 볼륨 정책, 대상 볼륨의 물리 할당, 템플릿/다운로드 파일 형식을 구분한다. RAW 원본에서 QCOW2 템플릿이 생성되는 기존 정상 경로를 유지한다.

권한·정지/분리·암호화·보호·동시 작업·backing 참조 안전성은 계속 검증한다. 다운로드용 읽기 복사와 원본을 바꾸는 이동/복사를 구분하며 범용 lifecycle 검사를 무조건 제거하지 않는다.

## 구현 순서와 영향 범위

| 순서 | 범위 | 주 변경 지점 | 준비된 검증 경계 |
| --- | --- | --- | --- |
| 1 | [#1512](https://github.com/ablecloud-team/ablestack-cloud/issues/1512) 템플릿 생성 UI | `ui/src/config/section/storage.js`, `CreateTemplate.vue`, allocation 유틸 | explicit off/raw의 API 성공 경로를 UI에 복원. 기존 상태·권한·암호화·Flatten 차단 보존. 생성 템플릿 VM 부팅까지 검증 |
| 2 | [#1513](https://github.com/ablecloud-team/ablestack-cloud/issues/1513) 다운로드 | `VolumeApiServiceImpl.extractVolume/orchestrateExtractVolume`, `VolumeServiceImpl.copyVolume`, storage UI | Primary→Secondary export 전용 계약. 원본 보존, 실제 파일 형식/내용·URL 수명·임시 자원 정리 |
| 3 | [#1511](https://github.com/ablecloud-team/ablestack-cloud/issues/1511) VM 복제 | `CloneVMCmd`, `UserVmManagerImpl`, allocation 계약/결과, 필요한 저장소 어댑터 | GFS2/RBD·Full/Linked의 정책 상속과 대상 확정/증거, ROOT/DATA·다중 복제·시작 여부 |
| 4 | Epic CL-02/03: 수명·지원 조건 | `VMSnapshotManagerImpl`, `StorageVMSnapshotStrategy`, 복제 상태/액션 | Full 독립화 후 임시 스냅샷 정리, 정지 RBD 복제/삭제, 연속 작업. Linked 부모 참조 보존 |
| 5 | Epic CL-04/05/06: 결과·계약·UX | API 응답, `CloneVM.vue`, compute 액션/작업 추적 | 전체 배치 결과와 부분 실패, count 계약, 소유자/Zone 계약, 사전 요약·상태/오류·접근성 |

한 브랜치에서 이슈 단위로 설계·변경·집중 검증·실환경 근거를 누적한다. 현재 준비 단계에서는 제품 소스를 변경하지 않는다.

## 변경 모듈 빌드 경계

- UI만 변경하는 #1512에서는 Maven/JAR 빌드가 필요하지 않다.
- #1513은 우선 `engine/storage/volume`과 실제 변경이 생긴 `server`를 대상으로 한다.
- #1511과 스냅샷 정리는 실제 변경 파일에 따라 `api`, `server`, `engine/storage/volume`, `engine/storage/snapshot`, 필요 시 `engine/schema` 및 KVM 모듈을 선택한다. 변경 전부터 전체 모듈을 빌드 대상으로 고정하지 않는다.
- Maven은 위 WSL ext4 작업 사본에서 `-pl <실제 변경 모듈>`로 실행한다. 내부 의존 산출물의 버전을 확인하고 필요한 선행 모듈만 명시적으로 준비한다.
- 전체 Cloud/Release 빌드는 명시적 요청이 있을 때 GitHub Actions로만 시작한다. qemu/FTCTL 변경은 현재 계획에 없으며 필요해지면 해당 저장소/Actions 규칙을 따른다.

## 집중 검사와 실환경 검증

기존 검사 위치:

- `api/src/test/java/com/cloud/storage/VolumeAllocationPolicyTest.java`, `VolumeAllocationLifecycleTest.java`.
- `server/src/test/java/com/cloud/vm/UserVmManagerImplTest.java`, `UserVmManagerTest.java`, `VolumeAllocationPreflightTest.java`.
- `server/src/test/java/com/cloud/template/TemplateManagerImplTest.java`, `server/src/test/java/com/cloud/storage/VolumeApiServiceImplTest.java`.
- `engine/storage/volume/src/test/java/org/apache/cloudstack/storage/volume/VolumeAllocationLifecycleGuardTest.java`, `VolumeAllocationResultTest.java`, `VolumeServiceTest.java`.
- `server/src/test/java/com/cloud/vm/snapshot/VMSnapshotManagerTest.java` 및 `engine/storage/snapshot/.../vmsnapshot` 검사.
- UI allocation 유틸/컴포넌트와 관련 storage/compute 액션·대화상자 검사. 구현에 맞는 회귀 검사를 추가하고 기존 포괄 차단을 단순히 제거했다고 통과시키지 않는다.

실환경은 31 GFS2·32/22 RBD의 유효한 legacy/explicit 정책과 이미지 형식, ROOT/DATA, Running/Stopped/분리, Full/Linked, count 1/2, 시작 여부를 다룬다. 세부 지원 조합은 구현 계약과 함께 확정한다.

각 기능은 UI로 요청하고 API Job→DB→실제 이미지/backing→게스트 또는 파일 내용까지 대조한다. 정상 생성뿐 아니라 후속 재복제/삭제·실패/중복/재시도·권한과 상태 변경을 확인한다. 전용 시험 prefix를 새로 발급하고 이전의 VM/볼륨 ID를 재사용하지 않는다. 관리 webapp은 `WEB-INF`를 보존하며 배포 전후 HTTP 200과 정적/JAR/runtime 일치를 확인한다.

## 환경 준비 상태 기록

- Java OpenJDK 17.0.18, Maven 3.6.3, Node.js 20.20.2, npm 10.8.2, Python/sshpass/gh 경로를 확인했다. 프로젝트 Java source/target 기준은 11이다.
- Maven 내부 산출물 캐시가 존재한다. 실제 변경 모듈의 의존 버전/컴파일 성공은 해당 구현 단계에서 확인한다.
- WSL UI 의존성은 동일한 `package-lock.json` SHA256(`23379939285231d360907e8d7242206efd5359654b9903614788040680133ffc`)의 기존 검증 작업 사본에서 독립 복사했다. 기존 작업 사본의 node_modules는 변경하지 않았다. 새 작업 사본에서 Vue CLI·unit-jest·Vue·Jest 모듈 로딩과 Git clean 상태를 확인했다.
- 다음 기존 집중 UI 검사가 **2 suites / 33 tests PASS**했다. 실행 시간 41.705초, coverage 미수집. 기존 아이콘 중복 등록·Vue stub 경고와 오래된 Browserslist 데이터 알림이 있었고 검사 실패는 없었다. 준비 중 의존 버전/lockfile은 변경하지 않았다.

  ```bash
  cd /home/ablecloud/work/dhslove/cloud-epic-1510-vm-clone/ui
  NODE_OPTIONS=--openssl-legacy-provider npm run test:unit -- \
    --runTestsByPath tests/unit/utils/volumeAllocation.spec.js \
    tests/unit/components/view/AllocationPolicy.spec.js \
    --runInBand --coverage=false --watch=false
  ```

- 이 결과는 구현 전 UI 실행 환경과 기존 allocation 검사의 기준선이다. Maven 빌드나 제품 기능 복원 성공을 의미하지 않는다.
- 현재 작업은 브랜치·환경·구현 순서 준비이며 제품 변경, 테스트 서버 자원 생성/배포, 전체 Cloud 빌드를 시작하지 않았다.
