// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.util.Arrays;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageFileSharePathsTest {
    private StorageFileShareVO row(long id, String name, String relative, StorageServiceInstance.Protocol protocol) throws Exception {
        StorageFileShareVO row=new StorageFileShareVO(1,protocol,name,"/export/"+name,77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,
                "{\"volumeMountPath\":\"/srv/ablestack-storage/volumes/volume\",\"backingPath\":\"/srv/ablestack-storage/volumes/volume/"+relative+"\"}");
        Field field=StorageFileShareVO.class.getDeclaredField("id");field.setAccessible(true);field.set(row,id);return row;
    }
    @Test public void strictRelativePathAndComponentBoundaries() {
        assertEquals("projects/team-a",StorageFileSharePaths.normalize("projects/team-a"));
        assertTrue(StorageFileSharePaths.child("projects/team-a","projects"));
        assertFalse(StorageFileSharePaths.child("projects2/team-a","projects"));
        assertFalse(StorageFileSharePaths.child("projects","projects"));
        for(String path:Arrays.asList("","/a","a//b","a/../b","a/","a\\b","x".repeat(256))) {
            try { StorageFileSharePaths.normalize(path);fail(path); } catch(InvalidParameterValueException expected) { }
        }
    }
    @Test public void parentGuardsIncludeOtherProtocolButAllowExactAliasesAndSiblings() throws Exception {
        var parent=row(1,"parent","projects",StorageServiceInstance.Protocol.NFS);
        var alias=row(2,"alias","projects",StorageServiceInstance.Protocol.SMB);
        var sibling=row(3,"sibling","projects2",StorageServiceInstance.Protocol.SMB);
        StorageFileSharePaths.requireNoChildren(parent,Arrays.asList(parent,alias,sibling));
        var child=row(4,"child","projects/team-a",StorageServiceInstance.Protocol.SMB);
        try {StorageFileSharePaths.requireNoChildren(parent,Arrays.asList(parent,child));fail();}
        catch(InvalidParameterValueException expected) {assertTrue(expected.getMessage().contains("SMB:child"));}
        var relations=StorageFileSharePaths.relationships(parent,77L,"projects",Arrays.asList(alias,sibling,child));
        assertEquals("EXACT_ALIAS",relations.get(0).getAsJsonObject().get("relation").getAsString());
        assertEquals("SIBLING",relations.get(1).getAsJsonObject().get("relation").getAsString());
        assertEquals("DESCENDANT",relations.get(2).getAsJsonObject().get("relation").getAsString());
    }
    @Test public void legacyConfigIsUntouchedAndNewModeNeverFormatsOrUsesImplicitCreation() {
        VolumeVO volume=mock(VolumeVO.class);when(volume.getUuid()).thenReturn("volume");
        String legacy="{\"ownerUid\":1001,\"keep\":true}";
        assertEquals(legacy,StorageFileSharePaths.configure(legacy,null,volume,"FORMAT_IF_EMPTY",true,null));
        JsonObject config=JsonParser.parseString(StorageFileSharePaths.configure(legacy,"projects/team",volume,"REUSE_ATTACHED",null,null)).getAsJsonObject();
        assertFalse(config.get("createDirectory").getAsBoolean());assertTrue(config.get("keep").getAsBoolean());
        assertEquals(1001,config.get("ownerUid").getAsInt());
        assertEquals("/srv/ablestack-storage/volumes/volume/projects/team",config.get("backingPath").getAsString());
        try {StorageFileSharePaths.configure(legacy,"projects/team",volume,"FORMAT_IF_EMPTY",true,null);fail();}
        catch(InvalidParameterValueException expected) {assertEquals("DIRECTORY_REUSED_VOLUME_FORMAT_FORBIDDEN",expected.getMessage());}
    }
    @Test public void optionalSmbApiPathIsCapturedInRealCommandIntent() throws Exception {
        CreateStorageSmbShareCmd cmd=new CreateStorageSmbShareCmd();
        Field field=cmd.getClass().getDeclaredField("relativePath");field.setAccessible(true);field.set(cmd,"projects/team");
        Field create=cmd.getClass().getDeclaredField("createDirectory");create.setAccessible(true);create.set(cmd,true);
        JsonObject intent=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals("projects/team",intent.get("relativepath").getAsString());
        assertTrue(intent.get("createdirectory").getAsBoolean());
    }
}
