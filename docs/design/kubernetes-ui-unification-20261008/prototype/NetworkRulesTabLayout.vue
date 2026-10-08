<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <section class="shared-network-rules-tab" :data-network-kind="model.kind" :data-network-context="context">
    <div class="detail-tab-toolbar">
      <a-button type="primary" :disabled="unavailable" @click="$emit('navigate',model.create)"><template #icon><plus-outlined/></template>{{model.addLabel}}</a-button>
      <a-button @click="$emit('refresh')"><template #icon><reload-outlined/></template>업데이트</a-button>
      <a-button v-if="selected.length" danger @click="$emit('navigate',model.remove)"><template #icon><delete-outlined/></template>선택 규칙 삭제 ({{selected.length}})</a-button>
      <a-input-search v-model:value="search" class="detail-tab-toolbar-right" placeholder="규칙 검색" style="width:220px" />
    </div>
    <a-form layout="vertical"><a-row :gutter="20"><a-col :xs="24" :md="12"><a-form-item label="네트워크 / VPC 티어"><a-select :disabled="context==='ip'" value="app-isolated-network" :options="[{value:'app-isolated-network',label:'app-isolated-network · Zone-A'}]" /></a-form-item></a-col><a-col :xs="24" :md="12"><a-form-item label="공인 IP 주소"><a-select :disabled="context==='ip'" v-model:value="ip" :options="[{value:'all',label:'전체 공인 IP'},...['203.0.113.10','203.0.113.11','203.0.113.12'].map(value=>({value,label:value}))]" @change="page=1" /></a-form-item></a-col></a-row></a-form>
    <a-alert v-if="unavailable" type="warning" show-icon message="네트워크·관리 규칙 조회 실패" description="마지막으로 확인한 목록입니다. 다시 조회한 후 설정 작업을 진행할 수 있습니다." />
    <a-alert v-else type="info" show-icon :message="model.help" />
    <a-table class="section-space" size="small" :columns="model.columns" :data-source="visibleRows" row-key="id" :pagination="false" :scroll="{x:940}" :row-selection="{selectedRowKeys:selected,onChange:keys=>selected=keys,columnWidth:30,getCheckboxProps:row=>({disabled:row.protected||unavailable})}">
      <template #bodyCell="{column,text,record}">
        <template v-if="column.key==='owner'"><a-tag :color="record.protected?'gold':'blue'">{{text}}</a-tag><span v-if="record.protected" class="muted">보호됨</span></template>
        <a-badge v-else-if="column.key==='state'" status="success" :text="text" />
        <div v-else-if="column.key==='actions'" class="shared-network-row-actions">
          <a-button size="small" :disabled="record.protected||unavailable" :aria-label="record.name+' 설정'" @click="$emit('navigate',model.edit)">설정</a-button>
          <a-dropdown :trigger="['click']"><a-button size="small" :disabled="record.protected||unavailable" :aria-label="record.name+' 추가 작업'"><template #icon><down-outlined/></template></a-button><template #overlay><a-menu>
            <a-menu-item v-if="model.kind==='loadbalancers'" key="backends" @click="$emit('navigate','lb-backends')">대상 VM·NIC</a-menu-item>
            <a-menu-item v-if="model.kind==='loadbalancers'" key="stickiness" @click="$emit('navigate','lb-stickiness')">세션 유지</a-menu-item>
            <a-menu-item v-if="model.kind==='loadbalancers'" key="tls" @click="$emit('navigate','lb-tls')">SSL 인증서</a-menu-item>
            <a-menu-item key="tags" @click="$emit('navigate','network-tags')">태그 편집</a-menu-item>
            <a-menu-item key="delete" danger @click="$emit('navigate',model.remove)">규칙 삭제</a-menu-item>
          </a-menu></template></a-dropdown>
        </div>
        <span v-else>{{text}}</span>
      </template>
    </a-table>
    <a-pagination class="detail-tab-pagination" size="small" :current="page" :page-size="pageSize" :total="filteredRows.length" :show-total="showTotal" :page-size-options="['10','20','50','100','200']" show-size-changer show-quick-jumper @change="changePage" @show-size-change="changePage"><template #buildOptionText="props"><span>{{props.value}} / 쪽</span></template></a-pagination>
  </section>
</template>
<script>
import { ref, computed, watch } from 'vue'
export default {
  name: 'NetworkRulesTabLayout',
  props: { model: {type:Object,required:true}, context: {type:String,default:'kubernetes'}, unavailable: Boolean },
  emits: ['navigate','refresh'],
  setup(props) {
    const search=ref(''),ip=ref(props.context==='ip'?'203.0.113.12':'all'),page=ref(1),pageSize=ref(20),selected=ref([])
    const filteredRows=computed(()=>props.model.rows.filter(r=>(ip.value==='all'||r.ip===ip.value)&&Object.values(r).join(' ').toLowerCase().includes(search.value.toLowerCase())))
    watch(search,()=>{page.value=1;selected.value=[]})
    watch(ip,()=>{selected.value=[]})
    const visibleRows=computed(()=>filteredRows.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
    const showTotal=total=>'전체 '+total+' 개 항목 중 '+Math.min(total,1+(page.value-1)*pageSize.value)+'-'+Math.min(page.value*pageSize.value,total)+' 표시'
    const changePage=(p,size)=>{pageSize.value=size;page.value=p;selected.value=[]}
    return {search,ip,page,pageSize,selected,filteredRows,visibleRows,showTotal,changePage}
  }
}
</script>
<style scoped>
.shared-network-row-actions {display:flex;gap:8px;align-items:center;white-space:nowrap;}
</style>
