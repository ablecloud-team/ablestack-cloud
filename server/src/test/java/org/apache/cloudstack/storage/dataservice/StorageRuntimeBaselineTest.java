// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageRuntimeBaselineTest {
    private void inject(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    @Test public void signedTemplateBaselineIsMappedByExactVersionAndVerifiedOrigin() throws Exception {
        StorageServiceRuntimeUpgradeManagerImpl manager=new StorageServiceRuntimeUpgradeManagerImpl();
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);
        StorageServiceRuntimeBundleDao bundles=mock(StorageServiceRuntimeBundleDao.class);
        StorageServiceRuntimeUpgradeDao upgrades=mock(StorageServiceRuntimeUpgradeDao.class);
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(70L);when(instance.getUuid()).thenReturn("instance");
        when(instances.findById(70L)).thenReturn(instance);
        inject(manager,"instanceDao",instances);inject(manager,"bundleDao",bundles);inject(manager,"upgradeDao",upgrades);
        StorageServiceRuntimeUpgradeVO operation=new StorageServiceRuntimeUpgradeVO(70L,99L,null,"request",2L);
        operation.setPreflightJson("{\"previousVersion\":\"4.23-template-42\"}");
        Method finish=manager.getClass().getDeclaredMethod("finishRollback",StorageServiceInstanceVO.class,StorageServiceRuntimeUpgradeVO.class,com.google.gson.JsonObject.class);
        finish.setAccessible(true);
        finish.invoke(manager,instance,operation,JsonParser.parseString("{\"currentVersion\":\"4.23-template-42\",\"currentOrigin\":\"template-baseline\"}").getAsJsonObject());
        assertEquals(StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK,operation.getState());
        verify(instance).setCurrentRuntimeBundleId(null);
        verify(instance).setPreviousRuntimeBundleId(99L);
        operation.setState(StorageServiceRuntimeUpgradeVO.State.COMPLETE);
        try {
            finish.invoke(manager,instance,operation,JsonParser.parseString("{\"currentVersion\":\"4.23-template-unknown\",\"currentOrigin\":\"template-baseline\"}").getAsJsonObject());
            fail("Unmapped version must not be accepted merely because it claims template origin");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause().getMessage().contains("verified template baseline"));
        }
    }
}
