## 실측 오류와 사용자 확인 (P1, #950 / #971 검증 중 발견)
32 RBD Plan 51, TEST_FAILOVER Run 420, session 35. 기존 NO_NIC + QGA_REQUIRED 시험에서 RBD clone 준비 이후 Cloud VM 생성이 실패했다.
`More than 1 default Isolated networks are found ... please specify networkIds`

Cloud VM은 네트워크 어댑터 없이 생성하지 않는다. 지원하는 방식은 어댑터를 유지하고 비활성화하는 것이다. 앞서 기재했던 빈 NIC 생성 우회 설계는 폐기했으며 server 수정과 해당 시험 코드는 배포하지 않고 제거했다.

## 원인
DrTargetMaterializationServiceImpl.ensureTestVm이 NO_NIC를 빈 networkIds로 보냈다. UserVmManagerImpl은 빈 목록을 기본 네트워크 자동 선택으로 해석한다. 기본 네트워크가 하나뿐이면 사용자의 격리 의도와 달리 활성 NIC가 붙을 수 있다.

## 정정한 구현 설계
- UI는 `네트워크 어댑터 비활성화` / `NIC_DISABLED`를 제공하고 연결할 네트워크를 명시적으로 선택한다.
- 과거 NO_NIC 요청은 호환용 별칭으로만 받아 어댑터 비활성화로 정규화한다. 네트워크 생성을 생략하지 않는다.
- DrReplicaDeployVMVolumeCmd.getIpToNetworkMap에서 기존 `IpAddresses(..., linkState=false)` 계약을 사용한다. 서버 공통 VM 생성 코드는 수정하지 않는다.
- 시험 VM은 Stopped로 생성한다. 최초 start 전에 NIC가 존재하고 모든 link_state가 false인지 확인하며, 조건 불충족 시 부팅하지 않는다.
- QGA는 직렬 통신 채널로 확인한다. Cloud NIC 행과 실행 중 libvirt XML의 link down을 함께 검증한다.

## 수용 조건
여러 기본 네트워크가 있어도 명시한 네트워크로 시험 VM을 생성하고, 모든 어댑터가 비활성화된 상태에서 QGA에 응답한다. VM 생성부터 부팅까지 운영망에 링크가 올라가는 구간이 없어야 한다. 정리는 해당 시험 VM/볼륨/clone만 제거한다. 일반 VM 생성 동작은 유지한다.
