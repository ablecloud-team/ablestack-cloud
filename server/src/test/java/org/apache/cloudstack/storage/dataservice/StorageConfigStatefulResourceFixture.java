// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.dataservice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.lang.reflect.Field;
import javax.persistence.Column;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
final class StorageConfigStatefulResourceFixture {
    final Map<String,Map<Long,Object>> rows=new LinkedHashMap<>();
    final Map<String,Class<?>> types=new LinkedHashMap<>();
    long next=2000;
    StorageConfigStatefulResourceFixture(StorageServiceManagerImpl manager){
        bind(manager,"storageServiceProtocolDao","storage_service_protocol",org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao.class,StorageServiceProtocolVO.class);
        bind(manager,"storageFileShareDao","storage_file_share",org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class,StorageFileShareVO.class);
        bind(manager,"storageBlockTargetDao","storage_block_target",org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class,StorageBlockTargetVO.class);
        bind(manager,"storageAccessRuleDao","storage_access_rule",org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class,StorageAccessRuleVO.class);
        bind(manager,"storageIdentityDomainDao","storage_identity_domain",org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao.class,StorageIdentityDomainVO.class);
        bind(manager,"storagePosixPolicyDao","storage_posix_directory_policy",org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao.class,StoragePosixDirectoryPolicyVO.class);
    }
    Object copy(Object value){return value==null?null:new Gson().fromJson(new Gson().toJson(value),value.getClass());}
    Object get(Object value,String method){
        try{return value.getClass().getMethod(method).invoke(value);}
        catch(ReflectiveOperationException e){throw new AssertionError("DAO fixture getter "+method,e);}
    }
    void bind(StorageServiceManagerImpl manager,String field,String table,Class<?> dao,Class<?> type){
        rows.put(table,new LinkedHashMap<>());types.put(table,type);
        Object adapter=Mockito.mock(dao,call->{
            String name=call.getMethod().getName();Object[] args=call.getArguments();Map<Long,Object> map=rows.get(table);
            if(call.getMethod().getDeclaringClass()==Object.class)return "stateful-copy-on-read-"+table;
            if(name.equals("persist")){
                Object value=copy(args[0]);long id=((Number)get(value,"getId")).longValue();
                if(id==0){id=++next;ReflectionTestUtils.setField(value,"id",id);}
                if(map.containsKey(id))throw new AssertionError("DAO duplicate "+table);
                map.put(id,copy(value));return copy(value);
            }
            if(name.equals("update")){
                long id=((Number)args[0]).longValue();if(!map.containsKey(id))throw new AssertionError("DAO unknown update "+table);
                map.put(id,copy(args[1]));return true;
            }
            if(name.equals("findById")||name.equals("findByIdIncludingRemoved"))return copy(map.get(((Number)args[0]).longValue()));
            if(name.equals("findByUuid")||name.equals("findByUuidIncludingRemoved"))return map.values().stream().filter(v->args[0].equals(get(v,"getUuid"))).findFirst().map(this::copy).orElse(null);
            if(!Set.of("listByInstanceIdAndProtocol","findByInstanceIdAndProtocol","listByInstanceId","listByInstance","findByInstanceId",
                    "listByProtocol","findByInstanceIdVolumeIdAndProtocol","listByResource","findByPath").contains(name))throw new AssertionError("Unmodelled DAO query "+table+"."+name);
            List<Object> values=new ArrayList<>();
            for(Object value:map.values()){
                boolean match;
                switch(name){
                    case "listByInstanceIdAndProtocol":case "findByInstanceIdAndProtocol":
                        match=Objects.equals(get(value,"getInstanceId"),args[0])&&Objects.equals(get(value,"getProtocol"),args[1]);break;
                    case "listByInstanceId":case "listByInstance":case "findByInstanceId":match=Objects.equals(get(value,"getInstanceId"),args[0]);break;
                    case "listByProtocol":match=Objects.equals(get(value,"getProtocol"),args[0]);break;
                    case "findByInstanceIdVolumeIdAndProtocol":match=Objects.equals(get(value,"getInstanceId"),args[0])&&Objects.equals(get(value,"getVolumeId"),args[1])&&Objects.equals(get(value,"getProtocol"),args[2]);break;
                    case "listByResource":match=Objects.equals(get(value,"getResourceType"),args[0])&&Objects.equals(get(value,"getResourceId"),args[1]);break;
                    case "findByPath":match=Objects.equals(get(value,"getInstanceId"),args[0])&&Objects.equals(get(value,"getPathKey"),args[1]);break;
                    default:throw new AssertionError("Unmodelled DAO query "+table+"."+name);
                }
                if(match)values.add(copy(value));
            }
            if(name.startsWith("find"))return values.isEmpty()?null:values.get(0);
            if(name.startsWith("list"))return values;
            throw new AssertionError("Unmodelled DAO operation "+table+"."+name);
        });
        ReflectionTestUtils.setField(manager,field,adapter);
    }
    boolean scoped(String table,Object row,long instance){
        if(!table.equals("storage_access_rule"))return ((Number)get(row,"getInstanceId")).longValue()==instance;
        String owner=get(row,"getResourceType").toString().equals("FILE_SHARE")?"storage_file_share":"storage_block_target";
        Object parent=rows.get(owner).get(((Number)get(row,"getResourceId")).longValue());
        return parent!=null&&((Number)get(parent,"getInstanceId")).longValue()==instance;
    }
    Map<String,Field> columns(Class<?> type){
        Map<String,Field> result=new LinkedHashMap<>();
        for(Class<?> at=type;at!=null;at=at.getSuperclass())for(Field field:at.getDeclaredFields()){
            Column column=field.getAnnotation(Column.class);if(column==null)continue;field.setAccessible(true);result.put(column.name(),field);
        }
        return result;
    }
    String snapshot(long instance){
        JsonObject result=new JsonObject();result.addProperty("schemaVersion",2);result.addProperty("instanceId",instance);JsonArray tables=new JsonArray();
        try{
            for(String table:rows.keySet()){
                JsonObject entry=new JsonObject();entry.addProperty("table",table);JsonArray columnNames=new JsonArray(),values=new JsonArray();
                Map<String,Field> columns=columns(types.get(table));
                for(Map.Entry<String,Field> column:columns.entrySet()){
                    JsonObject definition=new JsonObject();definition.addProperty("name",column.getKey());Class<?> type=column.getValue().getType();
                    definition.addProperty("type",java.util.Date.class.isAssignableFrom(type)?java.sql.Types.TIMESTAMP:Number.class.isAssignableFrom(type)||type==long.class||type==int.class?java.sql.Types.BIGINT:type==boolean.class||type==Boolean.class?java.sql.Types.BOOLEAN:java.sql.Types.VARCHAR);columnNames.add(definition);
                }
                for(Object object:rows.get(table).values())if(scoped(table,object,instance)){
                    JsonObject value=new JsonObject();
                    for(Map.Entry<String,Field> column:columns.entrySet()){
                        Object item=column.getValue().get(object);String key=column.getKey();
                        if(item==null)value.add(key,JsonNull.INSTANCE);
                        else if(item instanceof java.util.Date)value.addProperty(key,((java.util.Date)item).getTime());
                        else if(item instanceof Number)value.addProperty(key,(Number)item);
                        else if(item instanceof Boolean)value.addProperty(key,(Boolean)item);
                        else value.addProperty(key,item.toString());
                    }
                    values.add(value);
                }
                entry.add("columns",columnNames);entry.add("rows",values);tables.add(entry);
            }
        }catch(IllegalAccessException e){throw new AssertionError(e);}
        result.add("tables",tables);return result.toString();
    }
    void restore(long instance,String snapshot) {
        JsonObject value=com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
        if(value.get("instanceId").getAsLong()!=instance)throw new AssertionError("Foreign desired snapshot restore");
        // Only the six reversible desired tables are represented; VM/DATA never enter this fixture.
        Map<String,Map<Long,Object>> restored=new LinkedHashMap<>();
        for(com.google.gson.JsonElement table:value.getAsJsonArray("tables")) {
            JsonObject entry=table.getAsJsonObject();String name=entry.get("table").getAsString();
            if(!types.containsKey(name)||restored.containsKey(name))throw new AssertionError("Unknown or duplicate desired table");
            Map<Long,Object> result=new LinkedHashMap<>();
            try {
                for(com.google.gson.JsonElement row:entry.getAsJsonArray("rows")) {
                    Object object=new Gson().fromJson("{}",types.get(name));
                    for(Map.Entry<String,Field> column:columns(types.get(name)).entrySet()) {
                        com.google.gson.JsonElement element=row.getAsJsonObject().get(column.getKey());
                        if(element==null)throw new AssertionError("Desired snapshot column omitted");
                        Field field=column.getValue();Object item=null;
                        if(!element.isJsonNull()) {
                            Class<?> kind=field.getType();
                            if(kind==String.class)item=element.getAsString();
                            else if(kind==boolean.class||kind==Boolean.class)item=element.getAsBoolean();
                            else if(kind==long.class||kind==Long.class)item=element.getAsLong();
                            else if(kind==int.class||kind==Integer.class)item=element.getAsInt();
                            else if(java.util.Date.class.isAssignableFrom(kind))item=new java.util.Date(element.getAsLong());
                            else if(kind.isEnum())item=Enum.valueOf(kind.asSubclass(Enum.class),element.getAsString());
                            else throw new AssertionError("Unmodelled desired column type");
                        }
                        field.set(object,item);
                    }
                    long id=((Number)get(object,"getId")).longValue();
                    if(result.put(id,object)!=null)throw new AssertionError("Desired snapshot duplicate ID");
                }
            }catch(IllegalAccessException error){throw new AssertionError(error);}
            restored.put(name,result);
        }
        if(!restored.keySet().equals(rows.keySet()))throw new AssertionError("Incomplete desired snapshot");
        Map<String,Set<Long>> remove=new LinkedHashMap<>();
        for(String name:rows.keySet()) {
            Set<Long> ids=new java.util.HashSet<>();
            for(Map.Entry<Long,Object> row:rows.get(name).entrySet())if(scoped(name,row.getValue(),instance))ids.add(row.getKey());
            remove.put(name,ids);
        }
        for(String name:rows.keySet()) {
            for(Long id:remove.get(name))rows.get(name).remove(id);
            for(Map.Entry<Long,Object> row:restored.get(name).entrySet()) {
                if(rows.get(name).containsKey(row.getKey()))throw new AssertionError("Foreign desired ID collision");
                rows.get(name).put(row.getKey(),copy(row.getValue()));
            }
        }
    }
}
