# #1474 스냅샷 이미지 메타데이터 설계

Epic #1473 / SS-01 / P1. 작업 브랜치 `dhslove/epic-1473-storage-snapshots`, 시작 커밋 `6830ec76d0a15feb4b5fec1b04515242ff325a46`.

## 문제와 데이터 계약

RBD 원본 볼륨은 RAW이지만 Secondary 백업은 QCOW2일 수 있다. 원본 볼륨의 형식으로 백업의 처리기와 확장자를 선택하면 템플릿이 RAW로 등록되고 파일 길이가 논리 디스크 용량으로 사용된다. NFS 백업에서 볼륨을 복원하는 KVM 경로에도 같은 원본 형식 재적용 문제가 있다.

다음 값을 구분한다.

| 값 | 근거 | 용도 |
| --- | --- | --- |
| 백업의 실제 형식 | 백업 헤더와 독립적인 스냅샷 논리 용량 | 확장자, 템플릿 format, 복원 입력 형식 |
| 가상 용량 | QCOW2 헤더, 또는 검증된 RAW의 스냅샷 논리 용량 | API/DB size 및 template.properties virtualsize |
| 파일 길이 | 백업 파일 length | 기존 API physicalsize / properties size 계약 |
| 할당된 블록 사용량 | qemu-img actual-size / stat blocks | 실물 대조 증거; 위 파일 길이와 구분 |

## 공통 판별기

`SnapshotImageMetadata.inspect(File, Long expectedVirtualSize)`는 secondary-storage 모듈에 둔다. SSVM에 qemu-img가 없어도 실행할 수 있으며, 호스트의 독립 qemu-img 결과로 실물 검증한다.

- QCOW2 magic, v2/v3, cluster bits, 논리 용량과 암호화/외부 참조를 검사한다. v3는 기존 Qcow2Inspector로 backing/external-data/알 수 없는 incompatible feature도 검사한다. 기대 논리 용량이 있으면 헤더와 일치해야 한다.
- RAW에는 고유 헤더가 없다. 알려진 다른 형식을 배제하고, 스냅샷의 독립 논리 용량이 양수이며 파일 길이·512B 정렬과 일치할 때 처리한다. 정보가 없는 파일을 임의 RAW로 등록하지 않는다.
- 누락·빈 파일·잘린 헤더·미지원 형식·불일치는 IOException과 이유를 반환한다.
- 결과는 format / virtualSize / fileSize와 FormatInfo 변환을 제공한다. 원본 볼륨의 format, 확장자에 의존하지 않는다.

`SnapshotObjectTO.size`는 snapshot.getSize()에서 전달하는 선택적 Long이다. 기존 wire payload는 역직렬화할 수 있다. 원본 볼륨이 없어도 논리 용량을 전달하며 DB 스키마 변경은 없다.

## 템플릿 생성

KVM NFS 경로에서 원본 백업을 검사하고 실제 형식의 확장자로 복사한다. `cp --sparse=always -- <source> <target>`의 종료 결과를 확인하고, 복사한 파일을 다시 검사해 형식·논리 용량·파일 길이를 대조한다. `TemplateLocation.addFormat/save` 성공을 확인한 뒤 동일한 메타데이터로 API 응답과 properties를 생성한다. properties의 id는 실제 대상 템플릿 ID다.

소스 조회는 없는 백업 경로에 디렉터리를 만들지 않는다. 실패하면 이번 복사 파일·properties·빈 대상 디렉터리를 정리하고 실패 응답을 전달한다. 디렉터리 정리는 비재귀 방식이다. 임시 변환 원본은 성공 이후 정리한다. XenServer/다른 전송 프로토콜의 기존 분기는 유지한다.

## 볼륨 복원

`KVMStorageProcessor.createVolumeFromSnapshotOnNFS`도 같은 판별기로 실제 백업 형식과 크기를 설정한다. 판별 실패는 Primary 연결/복사 전에 거절한다. 기존 할당 정책·접근 권한·작업 안전장치를 유지한다. #1476의 크기/오퍼링 입력 계약은 별도 범위다.

## UI 및 다음 인터페이스

이번 변경은 backend/DTO에 한정하며 대화상자 소스와 VM 공통 컴포넌트는 변경하지 않는다. 실제 기존 UI의 ROOT 템플릿 생성, VM 시작, 알림 및 공통 작업 상세를 검증한다. 새로 만들거나 변경할 대화상자에는 VM 공통 표준을 적용하고, 남은 대화상자 일괄 정리는 최종 단계에 둔다.

#1475 다운로드 경로는 SSVM의 실제 파일에 대해 이 판별기를 재사용하도록 원격 command/answer 계약을 연결해야 한다. 관리 서버에서 원본 볼륨 형식으로 다시 추정하거나 별도 판별기를 중복 구현하지 않는다.

## 배포 검증

core / secondary-storage / KVM 변경 모듈만 WSL ext4에서 Maven 검사한다. 관리 서버 DTO, SSVM, KVM agent의 실행 클래스 해시와 실동작을 확인한다. 기존 JAR entry, WEB-INF, 정적 UI, FTCTL 프로그램과 운영 자원을 보존한다.

32 SSVM은 Diplo 라이브러리를 사용하므로 검증 배포는 기존 클래스에 변경된 5개 메서드를 ASM으로 이식한다. 다른 130개 메서드를 재직렬화 비교하고 bootstrap recipe를 보존한다. 최초 전체 클래스 교체 및 Javassist 시험의 실패는 진단 결과로 분리한다. 릴리즈 반영 대상은 같은 소스의 관리/core·SSVM·KVM 구성 전체다.
