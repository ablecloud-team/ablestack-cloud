// Licensed under the Apache License, Version 2.0. See NOTICE.
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.api.response.ApiResponseSerializer;
import org.apache.cloudstack.api.command.user.storage.dataservice.ResumeStorageServiceBootRecoveryCmd;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StorageBootRecoveryTest {
    static final String INSTANCE="73626423-1526-4849-8efe-269c215a306a";
    static final String BOOT="ea790444-84bc-4ad0-ae7f-7590fd1b81b8";
    static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        Long checked;
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { checked=id; return instance; }
    }
    Manager manager; StorageServiceGuestCommandDispatcher dispatcher;
    @Before public void before() {
        manager=new Manager();dispatcher=mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);
        when(manager.instance.getId()).thenReturn(7L);when(manager.instance.getVmId()).thenReturn(19L);
        when(manager.instance.getUuid()).thenReturn(INSTANCE);
        when(manager.instance.getActiveConfigurationOperationId()).thenReturn(null);
    }
    ResumeStorageServiceBootRecoveryCmd request() {
        var cmd=new ResumeStorageServiceBootRecoveryCmd();
        ReflectionTestUtils.setField(cmd,"instanceId",7L);
        ReflectionTestUtils.setField(cmd,"bootId",BOOT);
        ReflectionTestUtils.setField(cmd,"expectedDesiredHash","a".repeat(64));
        return cmd;
    }
    @Test public void authorizedInstanceAndFixedOperationAreUsedWithoutConfigurationMutation() {
        when(dispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"admitted",
            "{\"success\":true,\"bootReconcile\":{\"schemaVersion\":1,\"state\":\"STARTING\"}}"));
        manager.resumeStorageServiceBootRecovery(request());
        assertEquals(Long.valueOf(7),manager.checked);
        var capture=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
        verify(dispatcher).dispatch(capture.capture());
        var command=capture.getValue();
        assertEquals("boot-reconcile resume",command.getOperation());
        JsonObject payload=JsonParser.parseString(command.getPayload()).getAsJsonObject();
        assertEquals(Set.of("instanceUuid","bootId","expectedDesiredHash","protocol"),payload.keySet());
        assertEquals(INSTANCE,payload.get("instanceUuid").getAsString());
        assertEquals("NFS",payload.get("protocol").getAsString());
    }
    @Test public void protectedOrMissingVmNeverDispatches() {
        when(manager.instance.getActiveConfigurationOperationId()).thenReturn(31L);
        try {manager.resumeStorageServiceBootRecovery(request());fail();}catch(InvalidParameterValueException expected) {}
        verifyNoInteractions(dispatcher);
        when(manager.instance.getActiveConfigurationOperationId()).thenReturn(null);
        when(manager.instance.getVmId()).thenReturn(null);
        try {manager.resumeStorageServiceBootRecovery(request());fail();}catch(InvalidParameterValueException expected) {}
        verifyNoInteractions(dispatcher);
    }
    @Test public void arbitraryProtocolInvalidHashAndInvalidBootAreRejected() {
        for(String field:List.of("protocol","bootId","expectedDesiredHash")) {
            var cmd=request();ReflectionTestUtils.setField(cmd,field,field.equals("protocol")?"SMB":"invalid");
            try {manager.resumeStorageServiceBootRecovery(cmd);fail(field);}catch(InvalidParameterValueException expected) {}
        }
        verifyNoInteractions(dispatcher);
    }
    @Test public void actualSerializerPreservesStringResultAndUsesInheritedJobId() throws Exception {
        String nativeResult="{\"success\":true,\"bootReconcile\":{\"schemaVersion\":1,\"state\":\"PAUSED\",\"phase\":\"EXPORT_VISIBILITY\",\"bootId\":\""+BOOT+"\",\"desiredHash\":\""+"a".repeat(64)+"\",\"canResume\":true,\"firstFailure\":{\"code\":\"NFS_PROBE_TIMEOUT\"},\"protocols\":{\"NFS\":{\"state\":\"WAITING\",\"completed\":18,\"total\":20},\"SMB\":{\"state\":\"READY\"}},\"exports\":[]}}";
        when(dispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(true,"admitted",nativeResult));
        StorageServiceRuntimeResponse response=manager.resumeStorageServiceBootRecovery(request());
        response.setResponseName("resumestorageservicebootrecoveryresponse");response.setJobId("job-1459");
        String wire=ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject outer=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("resumestorageservicebootrecoveryresponse");
        JsonObject row=outer.getAsJsonObject("storageserviceruntime");
        assertTrue(row.get("resultjson").isJsonPrimitive());
        assertEquals(JsonParser.parseString(nativeResult),JsonParser.parseString(row.get("resultjson").getAsString()));
        assertEquals(1,wire.split("\"jobid\"", -1).length-1);
        String out=System.getProperty("boot.recovery.wire");
        if(out!=null)Files.writeString(Path.of(out),wire+"\n");
    }
    @Test public void failedGuestAdmissionPreservesSafeFailureCode() {
        when(dispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(false,"failure",
            "{\"success\":false,\"code\":\"BOOT_OBSERVATION_CHANGED\"}"));
        try {manager.resumeStorageServiceBootRecovery(request());fail();}
        catch(com.cloud.utils.exception.CloudRuntimeException expected) {assertEquals("BOOT_OBSERVATION_CHANGED",expected.getMessage());}
    }
}
