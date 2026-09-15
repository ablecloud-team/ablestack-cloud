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

// Read-only installed-JAR regression probe; executes in a separate JVM.
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.storage.dao.GuestOSDaoImpl;
import org.apache.cloudstack.ha.task.BaseHATask;
import org.apache.cloudstack.ha.provider.HAProvider;
import org.apache.cloudstack.ha.HAResource;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
public class HaDbSmoke {
 static void query() {
  TransactionLegacy tx=TransactionLegacy.currentTxn();
  if(tx==null) throw new AssertionError("HA executor missing DB context");
  try(Statement st=tx.getConnection().createStatement(); ResultSet rs=st.executeQuery("SELECT 1")) {
   if(!rs.next() || rs.getInt(1)!=1) throw new AssertionError("SELECT 1");
  } catch(SQLException e) { throw new IllegalStateException(e); }
 }
 public static void main(String[] args) {
  int code=1;
  try {
   Field f=TransactionLegacy.class.getDeclaredField("s_ds"); f.setAccessible(true);
   HikariDataSource ds=(HikariDataSource)f.get(null);
   int baseline=ds.getHikariPoolMXBean().getActiveConnections();
   HAResource resource=(HAResource)Proxy.newProxyInstance(HaDbSmoke.class.getClassLoader(),new Class[]{HAResource.class},(p,m,a)->m.getName().equals("toString")?"readonly-probe":null);
   HAProvider provider=(HAProvider)Proxy.newProxyInstance(HaDbSmoke.class.getClassLoader(),new Class[]{HAProvider.class},(p,m,a)->m.getName().equals("getConfigValue")?1L:null);
   if(args.length>0 && args[0].equals("dao")) {
    GuestOSDaoImpl dao=new GuestOSDaoImpl();
    for(int i=0;i<100;i++) {dao.findDoubleNames();if(ds.getHikariPoolMXBean().getActiveConnections()!=baseline)throw new AssertionError("DAO leaked connection at iteration "+i);}
    System.out.println("SMOKE DAO PASS iterations=100 active="+baseline);
   } else {
    for(int i=0;i<100;i++) {
     final int mode=i%25==24?3:i%3;
     BaseHATask task=new BaseHATask(resource,provider,null,null,null) {
      public boolean performAction() {
       query();
       if(mode==1)throw new IllegalStateException("expected action failure");
       if(mode==3)try {new CountDownLatch(1).await();}catch(InterruptedException expected){Thread.currentThread().interrupt();}
       return true;
      }
      public void processResult(boolean result,Throwable error) {query();if(mode==2)throw new IllegalStateException("expected result failure");}
     };
     try {boolean result=task.call(); if(result!=(mode==0))throw new AssertionError("result mode="+mode);}
     catch(IllegalStateException e){if(mode!=2 || !e.getMessage().equals("expected result failure"))throw e;}
     for(int n=0;n<100 && ds.getHikariPoolMXBean().getActiveConnections()!=baseline;n++)Thread.sleep(20);
     if(ds.getHikariPoolMXBean().getActiveConnections()!=baseline)throw new AssertionError("HA leaked at iteration "+i);
    }
    System.out.println("SMOKE HA PASS iterations=100 success/action-error/result-error/timeout active="+baseline);
   }
   ds.close();code=0;
  }catch(Throwable t){System.out.println("SMOKE FAIL "+t.getClass().getSimpleName()+": "+t.getMessage());}
  System.exit(code);
 }
}
