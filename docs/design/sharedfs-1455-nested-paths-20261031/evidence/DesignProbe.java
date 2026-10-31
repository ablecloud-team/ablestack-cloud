package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.cloud.serializer.Param;
import com.cloud.storage.VolumeVO;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsExportCmd;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.apache.cloudstack.storage.dataservice.dao.*;
import static org.mockito.Mockito.*;
public class DesignProbe {
 static void inject(Object target,String key,Object value)throws Exception{Field f=StorageServiceManagerImpl.class.getDeclaredField(key);f.setAccessible(true);f.set(target,value);}
 static class Harness extends StorageServiceManagerImpl {
  StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
  StorageFileShareVO parent=mock(StorageFileShareVO.class);
  int applyCount;
  @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
  @Override protected StorageFileShareVO requireNfsExport(Long id){return parent;}
  @Override protected void applyNfsDesiredState(StorageServiceInstanceVO value){applyCount++;}
 }
 static class Preview extends StorageServiceConfigurationPreflightResponse {
  @SerializedName("pathpreview") @Param(description="Design-only typed path witness") private JsonObject pathpreview;
  Preview(JsonObject report,JsonObject preview){super(report);pathpreview=preview;}
 }
 public static void main(String[] args)throws Exception {
  Harness h=new Harness();when(h.instance.getId()).thenReturn(91L);
  VolumeVO volume=mock(VolumeVO.class);when(volume.getUuid()).thenReturn("volume-example");
  JsonObject result=new JsonObject();
  h.validateNfsExportPath("/export/team-a","team-a");
  try{h.validateNfsExportPath("/export/projects/team-a","team-a");throw new AssertionError();}
  catch(com.cloud.exception.InvalidParameterValueException expected){result.addProperty("flatClientAliasEnforced",true);}
  String current="{\"relativeSharePath\":\"projects/team-a\",\"backingPath\":\"/srv/ablestack-storage/volumes/volume-example/projects/team-a\"}";
  JsonObject stored=JsonParser.parseString(h.buildFileShareDirectoryConfigJson(current,volume,"REUSE_ATTACHED",false)).getAsJsonObject();
  if(stored.has("relativeSharePath"))throw new AssertionError();
  result.addProperty("currentBuilderDropsRelativePath",true);
  JsonObject reused=JsonParser.parseString(h.buildManagedFileShareVolumeReuseConfigJson(current,"REUSE_ATTACHED",volume,"/export/team-a")).getAsJsonObject();
  result.addProperty("currentReuseBackingPath",reused.get("backingPath").getAsString());
  result.addProperty("currentNormalizerAcceptsLeadingSlash",h.normalizeRelativeSharePath("/projects/team-a"));
  StorageFileShareDao files=mock(StorageFileShareDao.class);StorageAccessRuleDao rules=mock(StorageAccessRuleDao.class);
  StorageBlockTargetDao blocks=mock(StorageBlockTargetDao.class);
  StorageFileShareVO child=mock(StorageFileShareVO.class);when(child.getVolumeId()).thenReturn(355L);when(child.getName()).thenReturn("child");
  when(h.parent.getId()).thenReturn(10L);when(h.parent.getInstanceId()).thenReturn(91L);when(h.parent.getVolumeId()).thenReturn(355L);when(h.parent.getName()).thenReturn("parent");
  when(files.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.NFS)).thenReturn(Arrays.asList(h.parent,child));
  when(files.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(Collections.emptyList());
  when(rules.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,10L)).thenReturn(Collections.emptyList());
  when(blocks.listByInstanceIdAndProtocol(anyLong(),any(StorageServiceInstance.Protocol.class))).thenReturn(Collections.emptyList());
  inject(h,"storageFileShareDao",files);inject(h,"storageAccessRuleDao",rules);inject(h,"storageBlockTargetDao",blocks);
  try{h.validateBackingVolumeUnused(h.instance,355L);throw new AssertionError();}
  catch(com.cloud.exception.InvalidParameterValueException expected){if(!expected.getMessage().contains("parent")||!expected.getMessage().contains("child"))throw expected;result.addProperty("existingVolumeReferenceGuardBlocksBoth",true);}
  DeleteStorageNfsExportCmd cmd=mock(DeleteStorageNfsExportCmd.class);when(cmd.getId()).thenReturn(10L);
  Method delete=StorageServiceManagerImpl.class.getDeclaredMethod("deleteStorageNfsExportCandidate",DeleteStorageNfsExportCmd.class);delete.setAccessible(true);
  delete.invoke(h,cmd);verify(files).remove(10L);if(h.applyCount!=1)throw new AssertionError();
  result.addProperty("currentCandidateDoesNotCheckPhysicalChildren",true);
  JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":26,\"checks\":[],\"observedat\":3500000000,\"expiresat\":3500000030,\"fingerprint\":\"design-only\"}").getAsJsonObject();
  JsonObject path=JsonParser.parseString("{\"relativepath\":\"projects/team-a\",\"exists\":true,\"inode\":12345,\"mode\":\"0775\",\"ancestors\":[{\"name\":\"projects\"}],\"descendants\":[]}").getAsJsonObject();
  path.addProperty("owneruid",65534);path.addProperty("ownergid",65534);
  Preview response=new Preview(report,path);response.setResponseName("preflightstorageserviceconfigurationresponse");
  String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
  JsonObject decoded=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight").getAsJsonObject("pathpreview");
  if(!decoded.get("mode").getAsString().equals("0775")||!decoded.get("exists").getAsBoolean())throw new AssertionError(wire);
  Files.writeString(Path.of(args[0],"path-preview-wire.json"),wire+"\n");
  Files.writeString(Path.of(args[0],"java-contract-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
  System.out.println(result);
 }
}
