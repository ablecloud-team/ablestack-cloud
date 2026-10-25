# #1478 스냅샷 삭제 종결·재시도·잔여 복구 검증 결과

2026-10-23, Europa 기준 `a7543feb62a728a5a806283b1e0e6134a7305260`, 브랜치 `dhslove/epic-1473-storage-snapshots`. 31(GFS2), 32(RBD)에 변경 모듈을 배포하고 전용 `SNAP1478-*` 자원으로 검증했다. 구현 및 해당 범위의 실환경 검증을 완료했다. upstream 병합은 별도 절차다.

## 변경 내용

- 중복 snapshot/store/role 참조를 각각의 실제 참조 ID로 고정하여 경로 조회와 상태 갱신이 동일한 행을 사용한다.
- 전체/Zone 삭제는 모든 대상 참조의 성공을 확인한다. 일부 실패와 미종결 참조를 성공으로 반환하지 않는다. 기존 의존 체인을 위한 Hidden 의미는 유지한다.
- 전역 Destroyed + 하위 Destroying/Ready 잔여를 동일한 삭제 경로로 재처리한다. snapshot별 분산 잠금, 재검증, 종료 확인으로 동시·반복 요청을 멱등 처리한다.
- RBD 삭제는 ENOENT만 무변경 성공으로 인정하고, EBUSY·권한 오류는 실패로 전달한다. 기존 내부 catch가 실제 RBD 오류를 성공으로 삼키던 경로를 수정했다.
- snapshot 수와 Secondary 사용량은 해당 소유자에 한해 표준 DB 재계산을 사용한다. 생성 시 논리 크기와 표준 재계산의 물리 크기 차이를 추정 차감으로 처리하지 않는다. 삭제 usage 이벤트는 반복 발행하지 않는다.
- `deleteSnapshot(dryrun=true)`과 `expectedscopehash`를 추가했다. UUID·이름·전역 상태·선택 Zone·참조 목록을 진단하고, 실행 전 범위가 달라지면 새 진단을 요구한다. 일반 삭제 성공 응답은 유지했다.
- 스냅샷 목록에 **삭제 잔여 복구** 대화상자를 추가했다. 진단 → 대상/영향 확인 → 재처리 → job 결과 확인을 제공한다. 입력 변경, 중복 제출, 계정/프로젝트 변경, 지연 응답을 방어한다. 수락된 job 결과가 불명확하면 같은 job 조회만 재시도한다.
- 비동기 응답 직렬화에서 진단 필드가 누락되는 문제를 실제 API로 확인해 수정했고 별도 직렬화 회귀 검사를 추가했다.

## 실환경 검증

| 검증 | 31 GFS2 | 32 RBD |
|---|---|---|
| 정상 삭제 | API 전체 삭제, 모든 참조 Destroyed 및 NFS 파일 부재 | 기존 UI Zone 행의 삭제 경로, Primary/Image 모두 Destroyed, RBD native/NFS 파일 부재 |
| 기존 잔여 복구 | 전역 Destroyed + 참조 95 Destroying/100 Ready 중복을 실제 UI로 복구, 같은 물리 파일 부재 | 기존 UI 성공 후 남은 Image 47 Destroying을 명시 Zone UUID로 UI 복구 |
| 부분 실패 | 복사 파일/중복 참조에 immutable 장애 주입 → job 실패·잔여 보존 → 속성 해제 후 API 재시도·반복 성공 | 보호된 native snapshot의 외부 clone으로 EBUSY 주입 → job 실패·native/clone 보존 → clone 제거 후 API 재시도·반복 성공 |
| 파일 부재 | NFS 파일을 미리 제거하고 동시 삭제 2건 성공, 미종결 참조 0 | native snapshot을 미리 제거하고 동시 삭제 2건 성공, NFS도 정리, 미종결 참조 0 |
| 권한 | 일반 계정이 자신의 Destroyed 잔여 복구·반복 성공. admin 소유 대조 자원 조회·진단·삭제 거부 | 동일 검증 성공 |
| 재실행 | UI 재진단/반복 무변경 성공, 계수 추가 변동 없음 | 최종 서버에서 UI 재진단/반복 무변경 성공 |
| 최종 코드 동시 요청·계수 | 기준 snapshot 22 / secondary 1,206,229,987,360 bytes로 복귀, 삭제 usage 1건 | 기준 snapshot 8 / secondary 385,675,339,824 bytes로 복귀, 삭제 usage 1건 |
| 대조 자원 | 대조 NFS SHA256 보존 | 대조 NFS SHA256와 RBD native 보존 |

대조 NFS SHA256: `3b107e52f63fab104395d42dbb0d08b9ef9cb3f10b1e32fe083674dba258e7f8`. 보존을 확인한 뒤 이 전용 대조 자원도 API로 정리했다.

최종 동시 삭제 검사는 `updateResourceCount`의 snapshot(3)·secondary_storage(11) 기준값을 먼저 구하고, 생성·동시 삭제·반복 후 추가 재계산 없이 원시 DB 계수를 읽어 비교했다. 초기 검증 도구의 리소스 타입 10(primary_storage) 및 물리 차감량 가정은 이 기준으로 수정했다. 제품의 최종 재계산은 `ResourceType.secondary_storage`를 사용한다.

32 재시작 직후 fixture 생성 1건은 호스트/SSVM endpoint 재연결 전에 실패했다. 로그의 `No remote endpoint to send createObjectCommand`와 실패 job `b8538973-3f9f-4ab1-8df2-a92f36e35e53`를 보존했다. Error snapshot은 API로 제거했고 호스트 3대 Up 확인 후 새 fixture의 생성·동시 삭제를 통과했다. 실패한 시도를 PASS로 세지 않았다.

31의 불일치 상태/중복 참조와 양 클러스터의 일반 소유자 잔여는 통제된 fixture SQL 주입으로 구성했다. 이는 실패 상태 구성일 뿐이며, 제품 실행 후 참조/global 상태를 SQL로 보정하지 않았다. 32의 기존 삭제 잔여는 변경 전 UI 동작에서 실제 재현한 자원이다.

## 범위와 제한

- 두 클러스터는 각각 단일 Zone이다. Zone UUID 필터·다른 Zone 보존 계약은 단위 검사로 확인했고, 실제 복구는 32의 명시 Zone 범위로 검증했다. 여러 Zone에 동시에 살아 있는 사본을 둔 통합 회귀는 #1489에 남긴다. 이 결과를 실물 multi-Zone PASS로 해석하지 않는다.
- 32 기존 Zone 탭은 global store를 한 행으로 보여 주면서 참조 수 2개를 표시한다. 해당 UI/집계 개선은 #1480 등 Epic의 후속 범위다. 이번 수정은 기존 삭제 진입점을 유지하며 복구 대화상자에 명시 UUID 범위를 제공한다.
- 보관(archive)의 신규 중복 방지는 #1477이다. 변경 전 archive 실패/Allocated 잔여도 증거로 보존했지만 이번 수정으로 보관 전체를 완료했다고 판정하지 않는다.
- 목록·상세·탭·전체 대화상자의 가상머신 UI 표준화는 Epic의 나머지 하위 이슈 범위다. 이번 추가 대화상자는 공통 MoldDialogActions를 사용한다.

## 검사와 빌드

WSL ext4의 분리 작업 트리에서 변경 Maven 모듈만 빌드/설치했다: api, engine/api, engine/storage, engine/storage/snapshot, server, plugins/hypervisors/kvm. 전체 Cloud 빌드, qemu 빌드, GitHub Actions 수동 실행은 하지 않았다.

- Maven 집중 검사 **144개**, 실패/오류/skip 0: SnapshotManager 40, 응답 직렬화 1, 전략 37, factory 3, service 9, 참조 ID 2, RBD 삭제 4, 기존 KVM storage 48.
- UI **2 suites / 27 tests 통과**, lint 오류 0, production build 완료. UI 테스트의 Vue stub 경고 및 기존 번들 크기/Browserslist 경고는 남아 있다.
- 일반 사용자 ACL과 저장소 장애는 단위 테스트 외에 실제 API·DB·파일/RBD로 검증했다.
- 전체 Cloud CI 통과를 주장하지 않는다. 요청되지 않은 전체 빌드를 시작하지 않도록 최종 커밋의 `[skip ci]`를 사용한다.

## 배포·보존·정리

관리 서버의 기존 fat JAR를 백업하고 변경 클래스 21개만 덮어 반영했다. 32 호스트 3대는 KVM storage 클래스 2개만 기존 agent JAR에 반영했다. qemu FTCTL 스크립트와 패키지는 변경하지 않았다. rollback용 JAR 및 static 파일 백업은 서버에 유지한다.

- 관리 JAR 백업: 각 서버 `/root/snapshot-delete-1478-backend-quota-20261023/` (이전 단계 백업도 유지).
- UI 백업: 31 `/root/snapshot-delete-1478-ui-menu-20261023/`, 32 `/root/snapshot-delete-1478-ui-20261023/`.
- 32 agent 백업: 각 호스트 `/root/snapshot-delete-1478-kvm-20261023/`.
- 활성 JAR 클래스 21개·UI 파일 917개의 해시가 산출물과 일치한다. WEB-INF/config 보존, Mold active, `/client/` 200, 기존 FTCTL UI marker 보존 확인.
- 32 agent 교체 전후 VM 인벤토리 동일, 호스트별 FTCTL 프로그램 15개 해시 동일.
- 전용 snapshot/volume/account를 API로 정리했다. **31·32 모두 활성 시험 snapshot 및 미종결 store 참조 0**. 볼륨은 Expunged·removed, 계정은 removed. 실패 생성 snapshot도 removed이며 과거 Error 상태 이력은 보존한다.
- NFS 고유 snapshot 파일 31의 9개·32의 7개 부재. 31의 전용 GFS2 볼륨 파일, 32의 전용 RBD source/tenant image와 busy clone 부재. 삭제 참조 과거 이력은 정상 보존/GC한다.
- 기존 API snapshot 응답은 31의 23행·32의 7행 보존. 기존 볼륨 31의 154개·32의 48개 UUID/이름/상태/소유자/경로 보존. 31에 새로 관찰된 무관한 볼륨 1개는 그대로 유지했다.
- Windows·WSL의 기존 dirty FTCTL 작업 트리 HEAD/branch/status/diff 및 기존 파일 해시 보존.

자세한 job UUID, 전후 DB/실물/계수, 배포 해시는 [evidence](evidence/README.ko.md)에 있다. 재현 도구는 환경변수로만 인증 정보를 받고 인증 정보를 커밋하지 않는다.
