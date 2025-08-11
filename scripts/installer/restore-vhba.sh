#!/bin/bash

# VHBA 자동 복원 스크립트
# /etc/vhba 경로의 dumpxml 파일들을 사용하여 vHBA 복원
# WWN 기반으로 vHBA 이름을 고정

VHBA_DIR="/etc/vhba"
LOG_FILE="/var/log/cloudstack/vhba-restore.log"
MAPPING_FILE="/etc/vhba/wwn_mapping.conf"

# 로그 함수
log_message() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

# WWN 매핑 파일 생성/업데이트
update_wwn_mapping() {
    log_message "WWN 매핑 업데이트 시작"
    
    # 매핑 파일 초기화
    > "$MAPPING_FILE"
    
    # 현재 활성 vHBA들의 WWN 정보 수집
    for vhba in $(virsh nodedev-list | grep "^scsi_host"); do
        wwnn=$(virsh nodedev-dumpxml "$vhba" 2>/dev/null | grep "<wwnn>" | sed 's/.*<wwnn>\(.*\)<\/wwnn>.*/\1/')
        wwpn=$(virsh nodedev-dumpxml "$vhba" 2>/dev/null | grep "<wwpn>" | sed 's/.*<wwpn>\(.*\)<\/wwpn>.*/\1/')
        
        if [ -n "$wwnn" ] && [ -n "$wwpn" ]; then
            echo "${wwnn}:${wwpn}=$vhba" >> "$MAPPING_FILE"
            log_message "WWN 매핑 추가: ${wwnn}:${wwpn} -> $vhba"
        fi
    done
    
    log_message "WWN 매핑 업데이트 완료"
}

# WWN으로 vHBA 이름 찾기
find_vhba_by_wwn() {
    local wwnn="$1"
    local wwpn="$2"
    local wwn_key="${wwnn}:${wwpn}"
    
    if [ -f "$MAPPING_FILE" ]; then
        grep "^$wwn_key=" "$MAPPING_FILE" | cut -d'=' -f2
    fi
}

log_message "VHBA 복원 시작"

# /etc/vhba 디렉토리 존재 확인
if [ ! -d "$VHBA_DIR" ]; then
    log_message "/etc/vhba 디렉토리가 존재하지 않습니다: $VHBA_DIR"
    exit 0
fi

# libvirtd 서비스가 완전히 시작될 때까지 대기
log_message "libvirtd 서비스 대기 중..."
for i in {1..30}; do
    if systemctl is-active --quiet libvirtd; then
        log_message "libvirtd 서비스가 활성화되었습니다."
        break
    fi
    log_message "libvirtd 서비스 대기 중... ($i/30)"
    sleep 2
done

# 추가 대기 시간 (디바이스 스캔 완료 대기)
log_message "디바이스 스캔 완료 대기 중..."
sleep 5

# 기존 vHBA들의 WWN 매핑 업데이트
update_wwn_mapping

# /etc/vhba 경로의 XML 파일들로 vHBA 복원
restore_count=0
failed_count=0

for xml_file in "$VHBA_DIR"/*.xml; do
    if [ -f "$xml_file" ]; then
        filename=$(basename "$xml_file")
        device_name=$(basename "$xml_file" .xml)
        
        log_message "vHBA 복원 시도: $filename"
        
        # XML 파일 유효성 검사
        if ! grep -q "<name>" "$xml_file" || ! grep -q "<path>" "$xml_file"; then
            log_message "XML 파일이 불완전함 (name 또는 path 태그 누락): $filename"
            failed_count=$((failed_count + 1))
            continue
        fi
        
        # 백업된 vHBA의 WWN 추출
        backup_wwnn=$(grep "<wwnn>" "$xml_file" | sed 's/.*<wwnn>\(.*\)<\/wwnn>.*/\1/')
        backup_wwpn=$(grep "<wwpn>" "$xml_file" | sed 's/.*<wwpn>\(.*\)<\/wwpn>.*/\1/')
        
        if [ -n "$backup_wwnn" ] && [ -n "$backup_wwpn" ]; then
            log_message "vHBA WWN: $backup_wwnn:$backup_wwpn"
            
            # WWN으로 기존 vHBA 찾기
            existing_vhba=$(find_vhba_by_wwn "$backup_wwnn" "$backup_wwpn")
            
            if [ -n "$existing_vhba" ]; then
                log_message "동일한 WWN을 가진 vHBA 발견: $existing_vhba"
                
                # 기존 vHBA가 있으면 삭제
                log_message "기존 vHBA 삭제 중: $existing_vhba"
                virsh nodedev-destroy "$existing_vhba" 2>/dev/null
                sleep 1
            fi
        fi
        
        # 부모 디바이스 확인
        parent_device=$(grep "<parent>" "$xml_file" | sed 's/.*<parent>\(.*\)<\/parent>.*/\1/')
        if [ -n "$parent_device" ]; then
            log_message "부모 디바이스 확인: $parent_device"
            if ! virsh nodedev-list | grep -q "^$parent_device$"; then
                log_message "부모 디바이스가 존재하지 않음: $parent_device"
                failed_count=$((failed_count + 1))
                continue
            fi
        fi
        
        # virsh nodedev-create로 vHBA 디바이스 생성
        result=$(virsh nodedev-create "$xml_file" 2>&1)
        
        if [ $? -eq 0 ]; then
            log_message "복원 성공: $filename"
            restore_count=$((restore_count + 1))
            
            # 생성된 vHBA의 실제 이름 추출
            created_vhba=$(echo "$result" | grep "created" | sed 's/.*created from.*//' | tr -d ' ')
            if [ -n "$created_vhba" ]; then
                log_message "생성된 vHBA: $created_vhba"
                
                # 새로운 vHBA의 WWN 매핑 업데이트
                new_wwnn=$(virsh nodedev-dumpxml "$created_vhba" 2>/dev/null | grep "<wwnn>" | sed 's/.*<wwnn>\(.*\)<\/wwnn>.*/\1/')
                new_wwpn=$(virsh nodedev-dumpxml "$created_vhba" 2>/dev/null | grep "<wwpn>" | sed 's/.*<wwpn>\(.*\)<\/wwpn>.*/\1/')
                
                if [ -n "$new_wwnn" ] && [ -n "$new_wwpn" ]; then
                    # 기존 매핑 제거
                    sed -i "/^${new_wwnn}:${new_wwpn}=/d" "$MAPPING_FILE" 2>/dev/null
                    # 새 매핑 추가
                    echo "${new_wwnn}:${new_wwpn}=$created_vhba" >> "$MAPPING_FILE"
                    log_message "WWN 매핑 업데이트: ${new_wwnn}:${new_wwpn} -> $created_vhba"
                fi
            fi
        else
            log_message "복원 실패: $filename - $result"
            failed_count=$((failed_count + 1))
        fi
    fi
done

# 복원 후 상태 확인
final_vhbas=$(virsh nodedev-list | grep "^scsi_host" 2>/dev/null | wc -l)
log_message "복원 후 활성 vHBA 디바이스 수: $final_vhbas"

# 복원 결과 요약
log_message "VHBA 복원 완료: 성공 $restore_count 개, 실패 $failed_count 개"
log_message "VHBA 복원 프로세스 완료" 