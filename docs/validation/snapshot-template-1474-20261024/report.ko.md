# #1474 템플릿 형식·가상 용량 정합성 구현·검증 결과

Epic #1473의 SS-01을 `dhslove/epic-1473-storage-snapshots`에서 구현했다. 시작점은 `6830ec76d0a15feb4b5fec1b04515242ff325a46`이며 기존 PR #1492에 누적한다. 최종 판정은 **PASS**다. API/UI/DB/실물과 정리 결과는 `evidence/verification.json` 및 각 증거 파일로 대조한다.

## 변경 결과

- 원본 RBD 볼륨 RAW와 Secondary 백업 QCOW2를 분리해 실제 파일의 형식·논리 용량으로 템플릿의 format, 확장자, size, properties를 결정한다.
- 공통 `SnapshotImageMetadata` 판별기를 제공하고 `SnapshotObjectTO.size`에 스냅샷 논리 용량을 전달한다. 원본 볼륨이 삭제돼도 백업 자체와 스냅샷 메타데이터로 처리한다.
- 복사 종료 결과·복사 후 메타데이터·properties 저장 결과를 검증한다. 실패를 Ready로 처리하지 않으며 소스 조회 부작용과 실패 복사의 파일/빈 디렉터리도 정리한다.
- 실검증에서 발견한 GFS2 원본 QCOW2 / 실제 RAW 백업의 복원 실패도 같은 판별기로 수정했다. NFS 복원 입력을 실제 형식·논리 용량으로 설정한다. #1476의 크기·오퍼링 입력 기능은 유지한다.

## 형식·용량 대조

수정 전 32의 전용 DATADISK 템플릿은 API/DB가 **RAW / 2,490,368 B**, 실제 파일은 **QCOW2 / 가상 1,073,741,824 B**였다. 수정 후 실제 헤더, 확장자, template.properties의 전역/형식별 값, API, vm_template 및 template_store_ref가 일치했다.

| 스토리지 | 볼륨 유형 | QCOW2 백업 | RAW 백업 | 논리 용량 |
| --- | --- | --- | --- | --- |
| 31 GFS2 | DATADISK | PASS | PASS | 1 GiB |
| 32 RBD | DATADISK | PASS | PASS | 1 GiB |
| 31 GFS2 | ROOT | PASS | PASS | 100 GiB |
| 32 RBD | ROOT | PASS | PASS | 100 GiB |

8개 조합에서 12개 정상 템플릿을 대조했다. RAW 조합은 전용 Secondary 백업을 qemu-img로 변환해 구성했다. 기본 GFS2/RBD 백업은 QCOW2다. RAW의 파일 길이와 할당된 블록 사용량을 별도 저장했으며, 기존 physicalsize 필드는 파일 길이 계약을 따른다. 기존 운영 템플릿과 메타데이터는 보존했다.

## 복원 데이터와 실제 부팅

- DATADISK 앞 1MiB `0x5a`, 끝 1MiB `0xa5`를 사용했다. 복원 볼륨 11개와 정상 DATADISK 템플릿 8개에서 두 패턴을 대조했다.
- 원본 DATADISK 볼륨을 삭제한 후 QCOW2/RAW 백업의 템플릿 생성과 볼륨 복원을 두 클러스터에서 확인했다. NFS 복원 경로에서 원본 형식 재적용으로 발생하던 GFS2 RAW 실패를 수정한 뒤 통과했다.
- UI에서 두 클러스터의 ROOT 스냅샷으로 QCOW2 템플릿을 생성하고 새 VM을 시작했다. RAW ROOT 템플릿 기반 새 VM도 UI로 시작했다. **신규 VM 4개** 모두 API/DB Running, 호스트 domain, QGA guest-ping·OS·루트 파일시스템 응답으로 부팅을 확인했다.
- ROOT QCOW2/RAW 템플릿 **4개**는 qemu-img compare로 스냅샷과 전체 논리 이미지가 동일함을 확인했다.
- 원본/신규 VM의 guest-exec는 기본 정책상 비활성화돼 있어 읽기 전용 QGA OS/파일시스템 명령으로 확인했다. 게스트 보안 설정은 변경하지 않았다.

## 실패·UI 검증

미지원 VMDK, 잘린 QCOW2, 누락 백업의 API 작업 실패와 Ready 템플릿 부재를 확인했다. 최종 잘린 헤더 오류는 `Truncated snapshot backup image header`로 전달된다. 복사 실패·복사 후 불일치·메타데이터 부재·외부 참조 등의 거절은 집중 테스트로 확인했다.

32 UI에서 손상 백업의 생성 요청이 기존 HeaderNotice의 서버 작업 실패로 표시됐고, VM 표준 공통 작업 상세에 구체적인 사유와 같은 요청/작업 UUID `a72bf790-b8d8-462d-919d-2537b345cf95`가 표시됐다. API 및 DB의 job_status=2와 대조했다. 두 UI에서 QCOW2/1GiB와 RAW/100GiB의 형식·크기·준비 상태도 확인했다.

대화상자와 공통 VM 화면 소스 변경은 없다. 새로 만들거나 변경하는 대화상자는 VM 표준을 따르며, 남은 대화상자의 일괄 정리는 최종 단계에 유지한다.

## 검사·배포

WSL ext4에서 변경 모듈만 검사했다. 총 **116 tests / 8 classes**, failures/errors/skipped 모두 0이다.

| 모듈 | 검사 | 결과 |
| --- | --- | --- |
| core | SnapshotObjectTOTest | 2 PASS |
| secondary-storage/server | metadata, template copy, 기존 NFS resource | 45 PASS |
| plugins/hypervisors/kvm | storage processor, snapshot image, RBD retry, allocation | 69 PASS |

세 모듈 Maven install/checkstyle 통과 및 git diff --check를 확인했다. 전체 Cloud 빌드, Actions dispatch, qemu/FTCTL 빌드와 UI 빌드는 수행하지 않았다. 집중 검사와 실환경 결과를 전체 Cloud CI로 표시하지 않는다.

관리 서버 JAR에는 DTO를 반영하고 SSVM 및 6개 KVM agent에 실행 코드를 반영했다. WEB-INF와 정적 UI를 보존했고 Mold active, /client/ HTTP 200, 각 클러스터 5개 agent Up을 확인했다. 기존 JAR entry와 FTCTL 프로그램은 보존했다. 롤백 백업은 각 서버의 `/root/snapshot-template-1474-*` 및 관련 stage 경로에 보존했다.

32 SSVM의 구버전 라이브러리 차이는 변경 메서드 5개와 판별기만 적용하는 배포로 해결했다. 다른 130개 메서드와 bootstrap recipe 보존을 확인했다. 최초 전체 클래스 교체의 UuidUtils 오류, 최초 Javassist 이식의 bootstrap 오류 및 누락 helper 오류는 실패로 기록하고 성공 판정에서 제외했다. 릴리즈는 같은 소스의 core/관리 서버·SSVM·KVM 구성을 함께 반영해야 한다.

## 정리 및 보존

- 전용 VM 6개, 스냅샷 8개와 생성한 볼륨/템플릿을 정상 API로 정리했다. 강제 삭제와 SQL 보정은 사용하지 않았다. 최종 활성 API/DB, 미종결 스냅샷 참조, Primary 템플릿 캐시, 알려진 NFS/GFS2/RBD 객체·RBD trash·3개 호스트 VM domain은 0이다.
- **초기 배포·초기 실패 시험 잔여물**인 이미지 1개, 부분 properties 1개와 빈 디렉터리 6개는 실패 job의 소유권/DB 참조·파일 해시/패턴을 확인한 후 수동 정리했다. 제품 최종 자동 정리 성공과 분리해 `*-legacy-artifact-cleanup.json`에 기록한다. 최종 실패 경로의 새 대상 디렉터리는 자동 제거됐다.
- 변환/오류 주입용 원본 백업 3개씩은 해시 확인 후 제거했다. 배포 롤백 JAR는 보존했다.
- 스냅샷별 SNAPSHOT.DELETE usage는 1회이며 admin snapshot count는 31=21, 32=6이다. snapshot/secondary_storage 자원 카운터가 활성 DB 집계와 일치한다.
- 기존 운영 볼륨·스냅샷·VM·템플릿의 상태/소유자/형식/크기 투영이 유지됐다. 보호된 Windows/WSL FTCTL 작업 트리의 HEAD/status/diff 해시도 이전 검증 기준과 일치한다.

## 다음 작업

다음은 **#1475 스냅샷 다운로드 확장자·실제 형식·완료 응답 정합성**이다. 이번 공통 판별 결과를 SSVM 원격 응답과 다운로드 계약에 연결한다. 이어 #1476 복원 크기·디스크 오퍼링 계약을 진행한다. 기능 개선과 최종 UI 정리 이후 #1489 통합 회귀를 수행한다. Epic #1473 및 나머지 미구현 이슈는 유지하며 PR #1492는 검토·병합 대기다.
