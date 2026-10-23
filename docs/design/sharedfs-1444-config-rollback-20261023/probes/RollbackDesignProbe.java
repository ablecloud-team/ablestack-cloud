package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.util.*;
import org.mockito.Mockito;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
public class RollbackDesignProbe extends StorageServiceManagerImpl {
  final StorageServiceInstanceVO instance = new StorageServiceInstanceVO("design-fixture","",1,1,1,1L,"SHAREDFSVM");
  final StorageFileShareVO nfs = new StorageFileShareVO(1,StorageServiceInstance.Protocol.NFS,"original","/export/original",1L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{}");
  final StorageFileShareVO smb = new StorageFileShareVO(1,StorageServiceInstance.Protocol.SMB,"original","/export/original",1L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{}");
  final StorageBlockTargetVO iscsi = new StorageBlockTargetVO(1,StorageServiceInstance.Protocol.ISCSI,"iqn.fixture","0",1L,StorageServiceInstance.ResourceState.Ready,"{}");
  final StorageBlockTargetVO nvme = new StorageBlockTargetVO(1,StorageServiceInstance.Protocol.NVME_OF,"nqn.fixture",null,null,StorageServiceInstance.ResourceState.Ready,"{}");
  boolean deleted=false, applyObservedDeleted=false;
  static void put(Object target,String name,Object value) throws Exception {Field f=StorageServiceManagerImpl.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
  RollbackDesignProbe() throws Exception {
    StorageFileShareDao fs=Mockito.mock(StorageFileShareDao.class);
    StorageBlockTargetDao bs=Mockito.mock(StorageBlockTargetDao.class);
    StorageAccessRuleDao acl=Mockito.mock(StorageAccessRuleDao.class);
    Mockito.when(acl.listByResource(Mockito.any(),Mockito.anyLong())).thenReturn(Collections.emptyList());
    Mockito.when(fs.remove(Mockito.anyLong())).thenAnswer(x->{deleted=true;return true;});
    Mockito.when(bs.remove(Mockito.anyLong())).thenAnswer(x->{deleted=true;return true;});
    put(this,"storageFileShareDao",fs);put(this,"storageBlockTargetDao",bs);put(this,"storageAccessRuleDao",acl);
    put(this,"storageServiceProtocolDao",Mockito.mock(StorageServiceProtocolDao.class));
  }
  @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
  @Override protected StorageFileShareVO requireNfsExport(Long id){return nfs;}
  @Override protected StorageFileShareVO requireSmbShare(Long id){return smb;}
  @Override protected StorageBlockTargetVO requireBlockTarget(Long id,StorageServiceInstance.Protocol protocol){return iscsi;}
  @Override protected StorageBlockTargetVO requireNvmeOfSubsystem(Long id){return nvme;}
  @Override protected void validateNvmeOfSubsystemCanBeDeleted(StorageBlockTargetVO value){}
  void fail(){applyObservedDeleted=deleted;throw new IllegalStateException("INJECTED_APPLY_FAILURE");}
  @Override protected void applyNfsDesiredState(StorageServiceInstanceVO i){fail();}
  @Override protected void applySmbDesiredState(StorageServiceInstanceVO i){fail();}
  @Override protected void applyIscsiDesiredState(StorageServiceInstanceVO i){fail();}
  @Override protected void applyNvmeOfDesiredState(StorageServiceInstanceVO i){fail();}
  public static void main(String[] args)throws Exception{
    List<Map<String,Object>> results=new ArrayList<>();
    for(String protocol:Arrays.asList("NFS","SMB","ISCSI","NVME_OF")){
      RollbackDesignProbe p=new RollbackDesignProbe();String error="";
      try{
        switch(protocol){
          case "NFS":p.deleteStorageNfsExport(Mockito.mock(DeleteStorageNfsExportCmd.class));break;
          case "SMB":p.deleteStorageSmbShare(Mockito.mock(DeleteStorageSmbShareCmd.class));break;
          case "ISCSI":p.deleteStorageIscsiTarget(Mockito.mock(DeleteStorageIscsiTargetCmd.class));break;
          default:p.deleteStorageNvmeOfSubsystem(Mockito.mock(DeleteStorageNvmeOfSubsystemCmd.class));
        }
      }catch(IllegalStateException ex){error=ex.getMessage();}
      if(!p.deleted||!p.applyObservedDeleted||!"INJECTED_APPLY_FAILURE".equals(error))throw new AssertionError(protocol);
      Map<String,Object> r=new LinkedHashMap<>();r.put("protocol",protocol);r.put("realManagerDeleteBeforeApply",p.applyObservedDeleted);r.put("rowStillRemovedAfterFailure",p.deleted);r.put("injectedFailurePreserved",error);results.add(r);
    }
    RollbackDesignProbe p=new RollbackDesignProbe();StorageServiceProtocolVO v=new StorageServiceProtocolVO(1,StorageServiceInstance.Protocol.NFS,true,"10.0.0.8",2050);v.setConfigJson("{\"protocolMode\":\"V3V4_DUAL\"}");
    p.rollbackProtocolEnable(v,false,true,"10.0.0.7",2049,StorageServiceInstance.ResourceState.Ready);
    if(!v.getConfigJson().contains("V3V4_DUAL")||v.getPort()!=2049)throw new AssertionError("protocol rollback fixture");
    Map<String,Object> r=new LinkedHashMap<>();r.put("case","existingProtocolRollback");r.put("endpointFieldsRestored",true);r.put("newConfigJsonRemains",true);results.add(r);
    System.out.println(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));
  }
}
