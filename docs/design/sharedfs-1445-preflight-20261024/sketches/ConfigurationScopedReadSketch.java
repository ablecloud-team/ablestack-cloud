package org.apache.cloudstack.storage.dataservice;
import java.util.*;
import java.lang.reflect.Field;
import org.apache.cloudstack.storage.dataservice.dao.*;
import com.google.gson.*;
import static org.mockito.Mockito.*;
public class ConfigurationScopedReadSketch {
 static void id(Object v,long n)throws Exception{Field f=v.getClass().getDeclaredField("id");f.setAccessible(true);f.set(v,n);}
 static StorageFileShareVO fs(long inst,long id)throws Exception{StorageFileShareVO v=new StorageFileShareVO(inst,StorageServiceInstance.Protocol.NFS,"p"+id,"/export/p"+id,8L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{}");id(v,id);return v;}
 static StorageBlockTargetVO bs(long inst,long id)throws Exception{StorageBlockTargetVO v=new StorageBlockTargetVO(inst,StorageServiceInstance.Protocol.ISCSI,"iqn.2026-10.local.storage:p"+id,"0",9L,StorageServiceInstance.ResourceState.Ready,"{}");id(v,id);return v;}
 static StorageAccessRuleVO rule(long target,long id)throws Exception{StorageAccessRuleVO v=new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,target,StorageServiceInstance.PrincipalType.CIDR,"10.10.0.0/16",StorageServiceInstance.Permission.READ_WRITE,StorageServiceInstance.ResourceState.Ready,"{}");id(v,id);return v;}
 public static void main(String[] args)throws Exception{
  StorageServiceProtocolDao p=mock(StorageServiceProtocolDao.class);
  StorageFileShareDao f=mock(StorageFileShareDao.class);
  StorageBlockTargetDao b=mock(StorageBlockTargetDao.class);
  StorageAccessRuleDao a=mock(StorageAccessRuleDao.class);
  List<StorageFileShareVO> allF=new ArrayList<>();List<StorageBlockTargetVO> allB=new ArrayList<>();List<StorageAccessRuleVO> allA=new ArrayList<>();
  for(int n=100;n<200;n++){allF.add(fs(10,n));allB.add(bs(10,n));allA.add(rule(n,n));}
  StorageFileShareVO wantedF=fs(9,7);StorageBlockTargetVO wantedB=bs(9,8);StorageAccessRuleVO wantedA=rule(7,9);
  allF.add(wantedF);allB.add(wantedB);allA.add(wantedA);
  when(p.listByInstanceId(9)).thenReturn(Collections.emptyList());
  when(f.listAll()).thenReturn(allF);when(b.listAll()).thenReturn(allB);when(a.listAll()).thenReturn(allA);
  StorageServiceConfigurationPlan current=new StorageServiceConfigurationPlan(9,p,f,b,a,text->{});
  List<StorageFileShareVO> selectedF=new ArrayList<>();List<StorageBlockTargetVO> selectedB=new ArrayList<>();List<StorageAccessRuleVO> selectedA=new ArrayList<>();
  when(f.listByInstanceIdAndProtocol(9,StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.singletonList(wantedF));
  when(f.listByInstanceIdAndProtocol(9,StorageServiceInstance.Protocol.SMB)).thenReturn(Collections.emptyList());
  when(b.listByInstanceIdAndProtocol(9,StorageServiceInstance.Protocol.ISCSI)).thenReturn(Collections.singletonList(wantedB));
  when(b.listByInstanceIdAndProtocol(9,StorageServiceInstance.Protocol.NVME_OF)).thenReturn(Collections.emptyList());
  when(a.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,7)).thenReturn(Collections.singletonList(wantedA));
  when(a.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET,8)).thenReturn(Collections.emptyList());
  for(StorageServiceInstance.Protocol proto:Arrays.asList(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB))selectedF.addAll(f.listByInstanceIdAndProtocol(9,proto));
  for(StorageServiceInstance.Protocol proto:Arrays.asList(StorageServiceInstance.Protocol.ISCSI,StorageServiceInstance.Protocol.NVME_OF))selectedB.addAll(b.listByInstanceIdAndProtocol(9,proto));
  for(StorageFileShareVO v:selectedF)selectedA.addAll(a.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,v.getId()));
  for(StorageBlockTargetVO v:selectedB)selectedA.addAll(a.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET,v.getId()));
  StorageFileShareDao sf=mock(StorageFileShareDao.class);StorageBlockTargetDao sb=mock(StorageBlockTargetDao.class);StorageAccessRuleDao sa=mock(StorageAccessRuleDao.class);
  when(sf.listAll()).thenReturn(selectedF);when(sb.listAll()).thenReturn(selectedB);when(sa.listAll()).thenReturn(selectedA);
  StorageServiceConfigurationPlan scoped=new StorageServiceConfigurationPlan(9,p,sf,sb,sa,text->{});
  if(!JsonParser.parseString(current.snapshot()).equals(JsonParser.parseString(scoped.snapshot())))throw new AssertionError("snapshot changed");
  verify(f,times(1)).listAll();verify(b,times(1)).listAll();verify(a,times(1)).listAll();
  System.out.println("{\"globalRowsLoaded\":303,\"scopedRowsLoaded\":3,\"snapshotsEqual\":true,\"actualDatabaseWrites\":0,\"mockDaoOnly\":true}");
 }
}
