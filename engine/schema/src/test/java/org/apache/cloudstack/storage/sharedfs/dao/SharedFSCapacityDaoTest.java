// Licensed under the Apache License, Version 2.0. See NOTICE.
package org.apache.cloudstack.storage.sharedfs.dao;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.Column;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDaoImpl;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Actual GenericDao persistence/read regression, restricted to a disposable loopback schema. */
public class SharedFSCapacityDaoTest {
    MysqlDataSource database;
    DataSource previous;
    Field source;
    TransactionLegacy transaction;
    VolumeDaoImpl dao;
    @Before public void open() throws Exception {
        String url=System.getProperty("sharedfs.capacity.dao.url");
        Assume.assumeTrue("Disposable loopback database is required",url!=null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13358/sharedfs1458_dao_test?"));
        database=new MysqlDataSource();database.setURL(url);database.setUser("root");
        List<String> columns=new ArrayList<>();
        for(Class<?> type=VolumeVO.class;type!=null;type=type.getSuperclass())for(Field f:type.getDeclaredFields()){
            Column c=f.getAnnotation(Column.class);if(c==null)continue;
            String name=c.name().isEmpty()?f.getName():c.name();
            if(name.equals("id")){columns.add("id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY");continue;}
            Class<?> value=f.getType();
            String sql=value==boolean.class||value==Boolean.class?"TINYINT":Number.class.isAssignableFrom(value)||value.isPrimitive()?"BIGINT":java.util.Date.class.isAssignableFrom(value)?"DATETIME(3)":"TEXT";
            columns.add(((char)96)+name+((char)96)+" "+sql+" NULL");
        }
        String ddl="CREATE TABLE IF NOT EXISTS volumes ("+String.join(",",columns)+")";
        try(Connection c=database.getConnection();Statement s=c.createStatement()){s.execute(ddl);s.execute("DELETE FROM volumes");}
        source=TransactionLegacy.class.getDeclaredField("s_ds");source.setAccessible(true);previous=(DataSource)source.get(null);source.set(null,database);
        transaction=TransactionLegacy.open(TransactionLegacy.CLOUD_DB);dao=new VolumeDaoImpl();
        Field reservation=VolumeDaoImpl.class.getDeclaredField("reservationDao");reservation.setAccessible(true);
        reservation.set(dao,org.mockito.Mockito.mock(org.apache.cloudstack.reservation.dao.ReservationDao.class));
    }
    @After public void close() throws Exception {if(transaction!=null)transaction.close();if(source!=null)source.set(null,previous);}
    VolumeVO save(String name,Long vm,Volume.Type type,boolean display,Volume.State state)throws Exception{
        VolumeVO v=new VolumeVO(type,name,1,1,2,1,Storage.ProvisioningType.SPARSE,10737418240L,null,null,null);
        v.setInstanceId(vm);v.setDisplayVolume(display);v.setState(state);
        return dao.persist(v);
    }
    @Test public void realDaoIncludesHiddenAndUnreferencedDataWithoutLosingIdentity() throws Exception {
        VolumeVO hidden=save("hidden",9L,Volume.Type.DATADISK,false,Volume.State.Ready);
        VolumeVO unused=save("unreferenced",9L,Volume.Type.DATADISK,true,Volume.State.Ready);
        save("root",9L,Volume.Type.ROOT,true,Volume.State.Ready);
        save("other-vm",10L,Volume.Type.DATADISK,true,Volume.State.Ready);
        save("detached",null,Volume.Type.DATADISK,true,Volume.State.Ready);
        List<VolumeVO> found=dao.listCurrentDataByVmIds(List.of(9L,9L));
        assertEquals(2,found.size());
        Set<String> ids=new HashSet<>();found.forEach(v->ids.add(v.getUuid()));
        assertEquals(Set.of(hidden.getUuid(),unused.getUuid()),ids);
        assertFalse(dao.findById(hidden.getId()).isDisplayVolume());
        assertNull(dao.findById(hidden.getId()).getAttached()); // Supported legacy Ready membership.
    }
    @Test public void removalDetachAndTransitionStatesRoundTripThroughTheDao()throws Exception{
        VolumeVO volume=save("attached",9L,Volume.Type.DATADISK,false,Volume.State.Ready);
        volume=dao.findById(volume.getId());volume.setInstanceId(null);assertTrue(dao.update(volume.getId(),volume));
        assertTrue(dao.listCurrentDataByVmIds(List.of(9L)).isEmpty());
        volume=dao.findById(volume.getId());volume.setInstanceId(9L);volume.setState(Volume.State.Attaching);dao.update(volume.getId(),volume);
        assertEquals(Volume.State.Attaching,dao.listCurrentDataByVmIds(List.of(9L)).get(0).getState());
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("UPDATE volumes SET removed=NOW() WHERE id=?")){s.setLong(1,volume.getId());assertEquals(1,s.executeUpdate());}
        assertTrue(dao.listCurrentDataByVmIds(List.of(9L)).isEmpty());
    }
}
