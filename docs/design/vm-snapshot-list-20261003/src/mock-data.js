/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file for details.
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 */
export const data = [
  ['win11','Windows11-Process','109','업데이트 전 정상 상태','Windows 보안 업데이트 적용 전','DiskAndMemory','Ready','Running','2026-10-01T11:27:08+09:00',true],
  ['debian13','Debian13-Process','96','서비스 구성 변경 전','서비스 설정 변경 전 복원 지점','DiskAndMemory','Ready','Stopped','2026-10-01T11:10:31+09:00',true],
  ['debian12','Debian12-Process','95','패키지 업데이트 전','루트 디스크 상태 저장','Disk','Ready','Running','2026-10-01T11:09:41+09:00',true],
  ['win19','Windows19-Process','108','운영 설정 반영 전','운영 설정 검증용','DiskAndMemory','Ready','Running','2026-10-01T11:07:50+09:00',true],
  ['win22','Windows22-Process','107','드라이버 변경 전','게스트 도구 업데이트 전','DiskAndMemory','Ready','Running','2026-10-01T11:06:47+09:00',true],
  ['win25','Windows25-Process','106','업데이트 검증용','스냅샷을 생성하고 있습니다','DiskAndMemory','Creating','Running','2026-10-01T11:04:42+09:00',false],
  ['ubuntu22','Ubuntu22-Process','94','서비스 배포 전','웹 서비스 배포 전','DiskAndMemory','Ready','Running','2026-10-01T10:18:43+09:00',true],
  ['ubuntu24','Ubuntu24-Process','93','기본 설치 완료','게스트 에이전트 설치 완료','DiskAndMemory','Ready','Running','2026-10-01T10:16:54+09:00',true],
  ['ubuntu26','Ubuntu26-Process','92','애플리케이션 구성 전','백업 정책 등록 전 복원 지점','DiskAndMemory','Ready','Running','2026-10-01T10:16:15+09:00',true],
  ['rocky10','Rocky10-Process','87','초기 구성 완료','운영체제 초기 구성 검증','DiskAndMemory','Ready','Running','2026-10-01T01:54:11+09:00',true],
  ['rocky9','Rocky9-Process','88','업데이트 전 상태','최근 작업 실패 · 이벤트 확인 필요','DiskAndMemory','Error','Running','2026-10-01T01:53:25+09:00',false],
  ['rocky8','Rocky8-Process','89','운영 기준 상태','서비스 운영 기준점','Disk','Ready','Stopped','2026-10-01T01:52:46+09:00',true],
  ['w2025-c','W2025-GFS2-Sparse','13','드라이버 업데이트 전','VirtIO 드라이버 변경 전 정상 상태','DiskAndMemory','Ready','Running','2026-09-19T23:09:30+09:00',true,'w2025-b'],
  ['w2025-b','W2025-GFS2-Sparse','13','보안 업데이트 전','Windows 보안 패치 적용 전','DiskAndMemory','Ready','Running','2026-09-19T22:29:49+09:00',false,'w2025-a'],
  ['w2025-a','W2025-GFS2-Sparse','13','기본 설치 완료','운영체제 및 기본 게스트 도구 설치','DiskAndMemory','Ready','Running','2026-09-18T18:40:00+09:00',false],
  ['gfs-test','GFS-TEST-VM','7','디스크 검증 전','공유 스토리지 테스트 기준','Disk','Ready','Stopped','2026-09-18T15:16:29+09:00',true]
].map((r,i) => ({id:r[0],vm:r[1],instance:'i-2-'+r[2]+'-VM',title:r[3],description:r[4],type:r[5],state:r[6],vmState:r[7],created:r[8],current:r[9],parent:r[10]||null,uuid:'a3100'+String(i).padStart(3,'0')+'-0d20-4000-8000-000000000031',name:'i-2-'+r[2]+'-VM_VS_'+r[8].replace(/[-:T]/g,'').slice(0,14),account:'admin',domain:'ROOT',zone:'Zone',backup:r[0]==='ubuntu26'}));
export function eligibility(action,x) {
  if (!x) return '대상 스냅샷을 다시 조회해야 합니다.';
  const busy = data.some(y=>y.vm===x.vm && ['Creating','Reverting','Expunging'].includes(y.state));
  if (busy && !(action==='delete' && x.state==='Expunging')) return '이 VM에서 스냅샷 작업이 진행 중입니다.';
  if (action==='delete') return ['Ready','Error','Expunging'].includes(x.state) ? '' : '현재 스냅샷 상태에서는 삭제할 수 없습니다.';
  if(x.state!=='Ready') return '사용 가능한 스냅샷만 복원할 수 있습니다.';
  if(x.backup) return '이 VM에 백업 정책이 있어 현재 스냅샷 복원이 제한됩니다.';
  if(x.type==='Disk' && x.vmState==='Running') return '디스크 전용 스냅샷 복원 전 VM을 정지해야 합니다.';
  if(x.type==='DiskAndMemory' && x.vmState==='Stopped') return '메모리 포함 스냅샷 복원 전 VM을 시작해야 합니다.';
  return '';
}
export const date = value => new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value));
export const typeText = type => type === 'DiskAndMemory' ? '디스크 + 메모리' : type === 'Disk' ? '디스크' : type;
export const statusText = state => ({ Ready: '사용 가능', Creating: '생성 중', Error: '오류', Reverting: '복원 중', Expunging: '삭제 중' })[state] || state;
