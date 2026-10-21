# VOL-03 (#1425) GFS2 생성·템플릿 복사 할당 설계

부모 Epic #1422 · 공통 계약 #1424 · RBD 구현 #1426 · 후속 수명주기 #1427 · 정책 UI #1431

## 지원과 의미

실제 GFS2에 마운트된 SharedMountPoint에 한해 QCOW2 metadata/falloc/full, RAW off/falloc/full의 명시적 계약을 실행한다. RAW metadata와 QCOW2 off는 이 어댑터의 지원표에 포함하지 않는다. 기존 thin/sparse/fat의 QEMU 매핑은 변경하지 않고 실제 적용 증거도 backfill하지 않는다. RBD는 기존 #1426의 RAW off/full만 허용하며 falloc·QCOW2로 확장하지 않는다.

metadata는 QCOW2 메타데이터를 준비하고 데이터 영역을 sparse하게 유지한다. RAW thin은 off다. falloc은 posix_fallocate 기반 공간 할당이며 eager 쓰기와 같다고 설명하지 않는다. Full은 파일 preallocation=full과 전체 논리 내용 검증을 수행한다. 파일 길이·가상 크기·stat.st_blocks×512는 별개다. 관측값은 생성/복사 시점의 파일시스템 할당 블록이며 영구 예약이나 guest discard 이후 보장이 아니다.

## 경로와 지원표

빈 DATA 생성과 primary/secondary에 있는 RAW/QCOW2 템플릿의 독립 전체 복사만 지원한다. 템플릿은 암호화·backing chain·외부 data file 없는 일반 파일이어야 한다. 대상 형식/크기는 요청 계약에 고정한다. 복사는 먼저 요청 형식/크기/정책으로 대상을 만든 뒤 qemu-img convert -n으로 데이터를 채우고 qemu-img compare로 원본 및 큰 대상의 zero tail을 대조한다. Full/falloc의 전체 할당이 sparse 최적화로 풀리지 않도록 -S 0을 사용한다. 따라서 falloc 템플릿 복사도 zero 데이터를 쓸 수 있지만 blank falloc을 eager initialization으로 표현하지 않는다.

Agent가 설치 QEMU 옵션과 실제 마운트 파일시스템·xattr 지원을 조회한 capability를 반환한다. 관리 서버는 형식·정책·작업·풀·연결 호스트를 검증하며 모든 eligible Up host가 create/copy를 지원해야 신규 요청을 수락한다. SharedMountPoint라는 타입 이름만으로 GFS2라고 추정하지 않는다. 미지원 확장·오퍼링 변경·clone·snapshot 복원·이동은 #1427의 별도 실증 전까지 변경 전에 차단한다.

## 공간·시간과 소유권

qemu-img measure로 대상 형식의 메타데이터/전체 할당 필요량을 구하고 실제 파일시스템 usableSpace와 pool.available 중 작은 값으로 검사한다. 템플릿 복사는 전체 데이터가 필요할 수 있으므로 보수적으로 fully-allocated 값을 사용한다. 이 검사는 공간 예약이 아니며 이후 ENOSPC도 job 실패·전용 stage 정리로 처리한다. 전체 작업에는 최대 1시간의 예산과 명시적인 시간 초과 오류를 사용한다.

풀 루트 아래 예측 불가능한 0700 전용 작업 디렉터리에서 image를 생성한다. 형식·가상 크기·파일 길이·실제 블록·backing 부재·논리 데이터 검증 이후 요청 fingerprint를 inode의 user.cloudstack.allocation.v1 xattr에 기록하고 fsync한다. 풀·볼륨 UUID/DB ID·소유자·유형·크기·계약·작업·원본 경로/형식/시각/크기가 지문에 포함된다.

검증된 inode를 최종 UUID에 hard link로 원자적으로 공개한다. link의 EEXIST가 호스트 간 경합에서도 기존 대상을 보존한다. 기존 최종 파일은 정확한 지문이 일치하고 데이터·할당을 재검증할 때만 응답 유실 재시도로 채택한다. 미소유 파일·symlink·다른 요청을 덮어쓰거나 정리하지 않는다. 실패/완료 시 해당 호출이 만든 stage와 디렉터리만 제거한다. 정리 실패는 정확한 전용 경로와 함께 오류로 보고한다. 파일시스템/풀 전체 정리는 하지 않는다.

## 관리 서버 검증과 UI

Agent 결과의 풀 UUID·이미지 UUID·크기·실제 형식·요청 계약·GFS2 메서드·관측 출처를 검증한 뒤 경로/Ready와 증거를 저장한다. Full/falloc은 실제 할당 블록이 가상 크기 이상이어야 한다. 구 Agent가 계약을 무시해도 성공으로 표시하지 않는다. 관측 출처는 stat-st-blocks-times-512다. DDL과 기존 볼륨 backfill은 없다.

현재 폼은 API로 생성한 명시적 오퍼링을 사용한다. 정책 선택·requested/applied/observed 표시 및 미지원 작업 UI 제어는 #1431, VM 기준 목록·상세·탭·콘텐츠·대화상자 통일은 #1433에서 진행한다.

## 검증 계획

31번에서 6개 지원 조합의 UI/API·DB·실물 대조, legacy 3종 호환, 작은 Full 전 범위 zero 및 템플릿 데이터 비교를 수행한다. 실제 어댑터의 중간 실패·공간 부족·중복 공개·응답 유실 재시도에서 원본과 미소유 파일 보존을 확인한다. 운영 풀을 가득 채우지 않고 부족 조건을 통제한다. 변경 모듈만 WSL ext4에서 빌드하며 기존 FT worktree·운영 VM/볼륨·WEB-INF·정적 UI를 보존한다. #1240의 별도 파일시스템 포맷 문제나 전체 Cloud CI 성공으로 확대하지 않는다.

기술 근거: [QEMU 이미지 형식](https://www.qemu.org/docs/master/system/images.html), [qemu-img create/convert/measure/compare](https://www.qemu.org/docs/master/tools/qemu-img.html). 구체적인 실제 결과·배포 해시·범위 제한은 검증 보고서에 별도 기록한다.

## RAW 기동 경로 보완

첫 Cloud RAW ROOT 기동에서 기존 디스크 정의가 RAW 파일을 QCOW2로 전달하여 QCOW2 metadata cache 계산을 시도하는 실패를 확인했다. 명시적 RAW 계약과 검증된 VolumeObjectTO RAW 형식, 풀 UUID, 실제 일반 파일을 확인한 뒤 기존 disk definition을 file/RAW로 정규화한다. 블록 장치로도 잘못 전달하지 않는다. 기존 버스·장치 이름·serial·cache/QoS 설정은 보존하고 QCOW2 metadata cache는 사용하지 않는다. 기동 시 QCOW2·legacy·RBD 정의에는 적용하지 않는다. 연결 경로도 SharedMountPoint의 실제 RAW 일반 파일을 파일 기반 RAW로 유지하고 기존 RBD/LVM 블록 장치 정의를 보존한다. 수정 후 신규 ROOT 생성부터 전체 경로를 다시 검증하며 최초 실패를 성공으로 재집계하지 않는다.

연결 명령은 적용 증거 대신 실제 형식만 전달할 수 있으므로, attachVolume 진입에서도 Cloud의 실제 RAW 형식·풀 UUID·일반 파일을 확인해 physical disk 형식을 고정한다. 이 과정은 빈 allocation map을 새 적용 증거로 채우지 않는다. 신규 GFS2 RAW의 Ready 결과가 구 디스크 정의의 QCOW2 기본값으로 다시 해석되지 않도록 기동·연결 모두에서 보장한다. 최초 RAW 연결 실패도 별도 보존하고 새 VM에서 다시 검증한다.
