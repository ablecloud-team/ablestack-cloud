# Epic #1422 / #1423 구현·빌드·31/32 배포 및 기능 검증

볼륨 이름·삭제 보호만 수정할 때 기존 경로가 NULL로 바뀌던 문제를 수정했다. 일반 Agent 실패 응답을 ResizeVolumeAnswer로 캐스팅하던 두 primary driver도 수정해 실제 실패 사유를 유지한다. 31 GFS2와 32 RBD에서 원래 결함 재현, 수정 배포, 권한별 API, UI 연결·확장·분리, 실패 복구·재시도 및 실물 데이터 보존을 확인했다.

검증 기간: 2026-10-19~20 KST. 기준 Europa: `248653b58920d2a950393ed3edc7b343b831105d`. Epic 브랜치: `dhslove/epic-1422-volume-offerings`. 구현 커밋: `c7b5ea1c6c8fae8ba603a4169636d35fcafaef54`. 이번 구현 범위는 #1423이며 #1422 전체 완료를 뜻하지 않는다.

## 구현

- path 미전달을 보존하고 빈 문자열/공백은 거절한다. 기존 경로와 동일하면 shareable 매핑까지 다시 바꾸지 않는다.
- 실제 경로 변경은 Root Admin의 자원 접근 권한, Allocated/Ready/Uploaded 상태, 연결 VM의 Stopped 상태를 확인한다. 이름·삭제 보호 수정에는 경로 입력을 요구하지 않는다.
- resize의 null 응답, 실패 응답, 실패 사유 누락 및 잘못된 성공 타입을 실패로 처리한다. 정상 ResizeVolumeAnswer에서만 size와 path/chain/pool을 갱신한다.
- Ablestack 모듈의 무조건 테스트 skip 설정을 제거했다. 기존 default driver의 CLVM 라우팅·예외 및 암호 정리 경로를 유지한다.

[설계와 손상 경로 탐지·복구 절차](../../design/mold-volume-1423-design.ko.md), [기계 판독 검증 요약](summary.json).

## 모듈 빌드와 검사

WSL Rocky ext4의 `/home/ablecloud/work/dhslove/cloud-epic-1422`에서 JDK 17/Maven 3.9.10으로 수행했다. 전체 reactor나 의존 모듈의 재빌드 없이 변경된 세 모듈만 선택했다.

```bash
mvn -B -pl server,plugins/storage/volume/ablestack,plugins/storage/volume/default \
  -DskipTests -Dcheckstyle.skip=true -Drat.skip=true package

mvn -B -pl server,plugins/storage/volume/ablestack,plugins/storage/volume/default \
  -Dcheckstyle.skip=true -Drat.skip=true \
  -Dtest=VolumeApiServiceImplUpdateTest,VolumeApiServiceImplTest,CloudStackPrimaryDataStoreResizeResponseTest,CloudStackPrimaryDataStoreDriverImplTest,AblestackPrimaryDataStoreResizeResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

| 검사 | 결과 |
|---|---:|
| 변경 모듈 package | 3/3 성공 |
| 부분 수정 신규 서비스 검사 | 15 통과 |
| 기존 VolumeApiServiceImpl 검사 | 159 통과 |
| default 응답/기존 CLVM 라우팅 검사 | 9 + 9 통과 |
| Ablestack 응답 검사 | 9 통과 |
| 합계 | **201 통과, 실패/오류/skip 0** |
| 서버별 원본 JAR 의존성에서 `-Xverify:all`과 신규 회귀 검사 | 33 × 2 통과 |
| 두 storage plugin 전체 Checkstyle | 성공 |

서버 모듈 전체 Checkstyle은 **기존 15건**의 wildcard import 위반으로 실패한다. 해당 7개 테스트 파일이 기준 커밋과 바이트 단위로 동일함을 확인했다. 이번 변경 파일 위반은 0건이다. 이 결과를 Checkstyle 전체 통과 또는 전체 CI 성공으로 표현하지 않는다. 전체 Cloud 빌드·릴리스 RPM·GitHub Actions 수동 실행은 수행하지 않았다.

## 배포와 보존

양 서버의 활성 CLASSPATH가 사용하는 합본 cloudstack JAR을 먼저 보관했다. 새 모듈의 변경 메서드 4개(새 helper 포함)를 원본 클래스에 반영했으며, 이외 VolumeApiServiceImpl 189개·Ablestack driver 34개·default driver 36개 메서드의 code 바이트가 보존됨을 확인했다. 원본 JAR의 나머지 항목도 내용이 같다. 출처 정보는 합본 JAR의 `META-INF/volume-1423-patch.json`에 추가했다.

| 항목 | 31 GFS2 | 32 RBD |
|---|---|---|
| 배포 JAR SHA256 | `78269f2a7c1f2361ffadee04723c6dd51b3fcc9cccecba669592824df9f0378c` | `b0076d644f3e3ed7fcff849e0a29690a8ecf10074e4ca2713036a31790ef192e` |
| 변경 외 보존 JAR 항목 | 204,498개 | 204,493개 |
| 원본 백업 디렉터리 | `/root/volume-1423-c7b5ea1c6c8` | `/root/volume-1423-c7b5ea1c6c8` |
| 최종 mold / client | active / HTTP 200 | active / HTTP 200 |
| index.html / WEB-INF | 해시 보존 | 해시 보존 |

서비스를 재시작하고 실제 활성 JAR 해시, API 인증 응답, 기능 실행을 확인했다. 배포 직전/직후 DB 스냅샷도 같았다. UI 자산과 호스트 패키지는 이번 수정의 배포 대상에 포함되지 않는다.

## 실제 기능 검증

전용 접두어 `VOL1423-31/32-20261019`를 사용했다. 양쪽 모두 배포 전 이름만 수정하는 API로 path 손실을 재현하고, 기록해 둔 전용 볼륨의 원래 경로를 복구했다. 이 재현 결과와 배포 후 성공 결과를 별도로 기록했다.

| 경로 | 31 | 32 |
|---|---|---|
| Admin 이름만/삭제 보호만/동시 수정·동일 경로·빈 경로 | 통과 | 통과 |
| User/DomainAdmin 및 Project의 수정·경로 보호·교차 소유권 | 통과 | 통과 |
| 최종 권한/API 행렬 | 18/18 | 18/18 |
| UI 이름 변경 → 전용 Stopped VM Device ID 1 연결 | 통과 | 통과 |
| 전용 잘못된 경로의 UI 확장 실패 | 원인 유지·Ready·1GiB 유지 | 원인 유지·Ready·1GiB 유지 |
| 기록된 원래 경로 복구 → UI 1→2GiB 재시도 | 성공 | 성공 |
| UI 분리 및 API/DB 경로·크기 대조 | Ready·2GiB·미연결 | Ready·2GiB·미연결 |
| qemu-img/RBD 실제 논리 크기 | 2,147,483,648 B | 2,147,483,648 B |

실패 job은 31번 `14035`, 32번 `1795`이며 status=2, code=530이다. API·DB·UI에 `Volume ... not found in libvirt pool` 원인이 유지되고 ClassCastException이 없다. 원래 경로 복구 후 같은 UI 확장은 성공했다. 의도적인 실패를 최초부터 성공한 실행으로 집계하지 않는다.

확장 전 전용 볼륨의 0/512MiB 위치에 64KiB씩 `0x5a`/`0x3c` 패턴을 기록했다. 확장 후 두 패턴과 1536MiB의 zero 읽기를 qcow2/RBD에서 모두 확인했다. 이는 호스트에서 수행한 데이터 무결성 검사이며 게스트 부팅·Running hotplug 검증을 뜻하지 않는다.

### 권한 환경의 구분

31번의 기본 User 역할에서는 updateVolume API 자체를 허용하지 않아 초기 호출이 432로 거절됐다. 기본 역할을 수정하지 않고, 전용 User/DomainAdmin 역할에 필요한 5개 API만 허용해 서비스·소유권·Project 검증을 진행하고 모두 삭제했다. 기본 역할 권한은 전후 동일하다.

또한 API의 parameter authorization은 일반 사용자의 관리자 전용 path를 서비스 호출 전에 제거할 수 있다. 따라서 성공/거절 코드만으로 판단하지 않고, 다른 path를 전달해도 실제 경로가 바뀌지 않는지 검증했다. 서비스 직접 검사에서는 권한 없는 명시적 변경 거절을 확인했다. 초기 RBAC 차단과 이 응답 가정의 정정은 최종 통과 행렬과 구분해 원본 journal에 보존했다.

## 정리와 남은 범위

데이터 볼륨 8개, 전용 VM 2개와 루트 볼륨 레코드 2개, 계정 6개, Project 2개, Domain 2개, 테스트 역할 4개 및 전용 오퍼링을 정리했다. 양 DB에서 관련 활성 볼륨·VM·오퍼링·계정·Project·Domain·역할은 모두 0이고, 해당 UUID의 GFS2 파일/RBD 이미지도 0이다. 삭제·비활성화 이력은 제품 계약에 따라 보존했다.

기존 볼륨 31번 140개/32번 48개의 누락·path/pool/size/type/VM 연결 변경이 없고, 기존 VM 상태/호스트 변경도 없다. 원래 Windows/WSL FTCTL 작업 트리는 변경하지 않는다.

Running VM의 실제 경로 변경 거절은 서비스 검사로 확인했으며 실환경의 연결/분리는 Stopped VM을 사용했다. UI 삭제 보호 폼·IOPS UI·신규 할당 정책·VM 표준 UI 및 Epic 최종 회귀는 각 선행 계획의 후속 이슈 범위다. 특히 이번 RBD 검증은 기존 Thin 볼륨 확장이며 Full 확장 지원의 근거가 아니다.

Epic 누적 PR에서 #1423 구현을 검토할 수 있도록 제안한다. 개별 이슈와 Epic은 PR 생성만으로 종료하지 않는다.
