import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
import com.cloud.vm.NicVO;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceManagerImpl;
import org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.mockito.Mockito;
public class CandidateProbe1449 {
 static final StorageServiceInstance.Protocol SMB=StorageServiceInstance.Protocol.SMB;
 static class Harness extends StorageServiceManagerImpl {
  final boolean proposed; boolean fail; final List<StorageServiceProtocolVO> rows=new ArrayList<>();
  final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
  final NicVO nic=Mockito.mock(NicVO.class);
  Harness(boolean proposed) throws Exception {
   this.proposed=proposed; Mockito.when(instance.getId()).thenReturn(8L);Mockito.when(instance.getVmId()).thenReturn(132L);
   Mockito.when(nic.getIPv4Address()).thenReturn("10.144.9.2");
   StorageServiceProtocolDao dao=Mockito.mock(StorageServiceProtocolDao.class);
   Mockito.when(dao.findByInstanceIdAndProtocol(8L,SMB)).thenAnswer(i->rows.isEmpty()?null:rows.get(0));
   Mockito.when(dao.listByInstanceIdAndProtocol(8L,SMB)).thenAnswer(i->new ArrayList<>(rows));
   Mockito.when(dao.persist(Mockito.any(StorageServiceProtocolVO.class))).thenAnswer(i->{
    StorageServiceProtocolVO row=i.getArgument(0);Field id=StorageServiceProtocolVO.class.getDeclaredField("id");id.setAccessible(true);id.setLong(row,16000L+rows.size());rows.add(row);return row;
   });
   Mockito.when(dao.remove(Mockito.anyLong())).thenAnswer(i->rows.removeIf(v->v.getId()==(Long)i.getArgument(0)));
   Field field=StorageServiceManagerImpl.class.getDeclaredField("storageServiceProtocolDao");field.setAccessible(true);field.set(this,dao);
  }
  @Override protected boolean isEndpointProtocol(StorageServiceInstance.Protocol p){return proposed&&p==SMB||super.isEndpointProtocol(p);}
  @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
  @Override protected NicVO resolveProtocolListenAddress(StorageServiceInstanceVO i,String ip){return nic;}
  @Override protected boolean registerProtocolListenAddress(StorageServiceInstanceVO i,String ip,NicVO n){return false;}
  @Override protected void ensureGuestProtocolListenAddress(StorageServiceInstanceVO i,String ip,NicVO n,Integer port){}
  @Override protected void applyStorageServiceProtocolDesiredState(StorageServiceInstanceVO i,StorageServiceInstance.Protocol p){if(fail)throw new IllegalStateException("CONTROLLED_CANDIDATE_FAILURE");}
  @Override protected StorageServiceProtocolResponse createProtocolResponse(StorageServiceProtocolVO row){StorageServiceProtocolResponse r=new StorageServiceProtocolResponse();r.setId(row.getUuid());return r;}
  void activate(String ip) throws Exception {
   EnableStorageServiceProtocolCmd c=Mockito.mock(EnableStorageServiceProtocolCmd.class);
   Mockito.when(c.getInstanceId()).thenReturn(8L);Mockito.when(c.getProtocol()).thenReturn("SMB");Mockito.when(c.getListenIp()).thenReturn(ip);Mockito.when(c.getPort()).thenReturn(445);
   Method m=StorageServiceManagerImpl.class.getDeclaredMethod("enableStorageServiceProtocolCandidate",EnableStorageServiceProtocolCmd.class);m.setAccessible(true);m.invoke(this,c);
  }
  boolean deleteUnsupported(){try{deleteStorageServiceEndpoint(instance,SMB,"10.144.9.3",445);return false;}catch(com.cloud.exception.InvalidParameterValueException e){return e.getMessage().contains("NFS protocol endpoints only");}}
  List<String> tuples(){List<String> out=new ArrayList<>();for(StorageServiceProtocolVO row:rows)out.add(row.getUuid()+" "+row.getListenIp()+":"+row.getPort());return out;}
 }
 public static void main(String[] args)throws Exception{
  Harness current=new Harness(false);current.activate("10.144.9.2");String first=current.rows.get(0).getUuid();current.activate("10.144.9.3");
  boolean overwritten=current.rows.size()==1&&first.equals(current.rows.get(0).getUuid())&&"10.144.9.3".equals(current.rows.get(0).getListenIp());
  Harness proposed=new Harness(true);proposed.activate("10.144.9.2");proposed.activate("10.144.9.3");List<String> before=proposed.tuples();proposed.activate("10.144.9.3");
  boolean reused=before.equals(proposed.tuples());proposed.fail=true;boolean rejected=false;try{proposed.activate("10.144.9.4");}catch(java.lang.reflect.InvocationTargetException e){rejected=e.getCause().getMessage().contains("CONTROLLED");}
  java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("actualCandidateWithDaoStub",true);result.put("currentSmbEndpointDeleteUnsupported",current.deleteUnsupported());result.put("currentOverwritesSameUuidAndLosesFirstTuple",overwritten);result.put("prototypeAddsDifferentTuple",before.size()==2);result.put("repeatReusesSameUuid",reused);result.put("controlledFailurePreservesEarlierRows",rejected&&before.equals(proposed.tuples()));System.out.println(new Gson().toJson(result));
 }
}