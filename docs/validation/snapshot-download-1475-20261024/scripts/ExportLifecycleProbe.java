// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
public class ExportLifecycleProbe {
 public static void main(String[] args)throws Exception {
  Class<?> type=Class.forName("com.cloud.storage.Upload$Type");Object snapshot=null;for(Object v:type.getEnumConstants())if(v.toString().equals("SNAPSHOT"))snapshot=v;
  Class<?> command=Class.forName("com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand");
  Object cmd=command.getConstructor(String.class,type,String.class,String.class).newInstance(args[1],snapshot,args[2],args[0]);
  Class<?> manager=Class.forName("org.apache.cloudstack.storage.template.UploadManagerImpl");Object target=manager.getConstructor().newInstance();
  Object answer=manager.getMethod("handleDeleteEntityDownloadURLCommand",command).invoke(target,cmd);
  Object gson=Class.forName("com.google.gson.Gson").getConstructor().newInstance();System.out.println("PROBE_RESULT="+gson.getClass().getMethod("toJson",Object.class).invoke(gson,answer));
 }
}
