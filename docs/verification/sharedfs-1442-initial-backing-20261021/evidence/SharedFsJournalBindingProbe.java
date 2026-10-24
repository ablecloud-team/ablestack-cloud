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
import java.lang.reflect.*;
import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.persistence.Column;
import com.cloud.utils.db.Attribute;
import com.cloud.utils.db.GenericDaoBase;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDaoImpl;
public class SharedFsJournalBindingProbe {
 public static void main(String[] args) throws Exception {
  Field field=SharedFSVO.class.getDeclaredField("creationContext");
  Attribute attr=new Attribute(SharedFSVO.class,null,field,"shared_filesystem",false,false);
  AtomicReference<byte[]> captured=new AtomicReference<>();
  PreparedStatement statement=(PreparedStatement)Proxy.newProxyInstance(SharedFsJournalBindingProbe.class.getClassLoader(),new Class[]{PreparedStatement.class},(proxy,m,a)->{
   if(m.getName().equals("setBytes")) captured.set((byte[])a[1]);
   return null;
  });
  Method bind=GenericDaoBase.class.getDeclaredMethod("prepareAttribute",int.class,PreparedStatement.class,Attribute.class,Object.class);
  bind.setAccessible(true);
  String input="{\"payload\":\""+"x".repeat(512)+"\"}";
  bind.invoke(new SharedFSDaoImpl(),1,statement,attr,input);
  String stored=new String(captured.get(),StandardCharsets.UTF_8);
  boolean valid=true;try{com.google.gson.JsonParser.parseString(stored);}catch(RuntimeException e){valid=false;}
  if(stored.length()!=255||valid)throw new IllegalStateException("Expected deployed truncation was not reproduced");
  System.out.println("{\"annotationLength\":"+field.getAnnotation(Column.class).length()+",\"inputLength\":"+input.length()+",\"boundLength\":"+stored.length()+",\"validJson\":"+valid+",\"databaseWrites\":0}");
 }
}
