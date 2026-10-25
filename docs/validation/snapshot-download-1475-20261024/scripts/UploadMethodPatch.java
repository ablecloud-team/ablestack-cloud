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
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public class UploadMethodPatch {
 static String proof(MethodNode m) { ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"Proof",null,"java/lang/Object",null);m.accept(w);w.visitEnd();return Base64.getEncoder().encodeToString(w.toByteArray()); }
 public static void main(String[] args)throws Exception {
  String cls="org/apache/cloudstack/storage/template/UploadManagerImpl";String path=cls+".class";
  ClassNode old=new ClassNode(),fresh=new ClassNode();
  try(JarFile jar=new JarFile(args[0])){new ClassReader(jar.getInputStream(jar.getJarEntry(path)).readAllBytes()).accept(old,0);}
  new ClassReader(Files.readAllBytes(Path.of(args[1],path))).accept(fresh,0);
  if(old.fields.stream().noneMatch(f->f.name.equals("BASE_EXTRACT_PATH")))old.fields.add(new FieldNode(Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"BASE_EXTRACT_PATH","Ljava/lang/String;",null,"/var/www/html/userdata/"));
  if(old.fields.stream().noneMatch(f->f.name.equals("EXTRACT_USERDATA_DIR")))old.fields.add(new FieldNode(Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"EXTRACT_USERDATA_DIR","Ljava/lang/String;",null,"userdata"));
  Set<String> names=Set.of("handleCreateEntityURLCommand","createSnapshotDownload","snapshotDownloadSource","snapshotExtractRoot","snapshotStorageRoot","checkAndStartApache","deleteSnapshotDownload");
  Map<String,String> unchanged=new HashMap<>();
  for(MethodNode m:old.methods)if(!names.contains(m.name)&&!m.name.equals("handleDeleteEntityDownloadURLCommand"))unchanged.put(m.name+m.desc,proof(m));
  old.methods.removeIf(m->names.contains(m.name));for(MethodNode m:fresh.methods)if(names.contains(m.name))old.methods.add(m);
  MethodNode deletion=old.methods.stream().filter(m->m.name.equals("handleDeleteEntityDownloadURLCommand")).findFirst().orElseThrow();
  String type="com/cloud/storage/Upload$Type",cmd="com/cloud/agent/api/storage/DeleteEntityDownloadURLCommand";
  InsnList prefix=new InsnList();LabelNode legacy=new LabelNode();
  prefix.add(new FieldInsnNode(Opcodes.GETSTATIC,type,"SNAPSHOT","L"+type+";"));prefix.add(new VarInsnNode(Opcodes.ALOAD,1));
  prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,cmd,"getType","()L"+type+";",false));
  prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,type,"equals","(Ljava/lang/Object;)Z",false));prefix.add(new JumpInsnNode(Opcodes.IFEQ,legacy));
  prefix.add(new VarInsnNode(Opcodes.ALOAD,0));prefix.add(new VarInsnNode(Opcodes.ALOAD,1));
  prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,cls,"deleteSnapshotDownload","(L"+cmd+";)Lcom/cloud/agent/api/Answer;",false));prefix.add(new InsnNode(Opcodes.ARETURN));
  prefix.add(legacy);prefix.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));deletion.instructions.insert(prefix);
  ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);old.accept(writer);byte[] bytes=writer.toByteArray();
  ClassNode verify=new ClassNode();new ClassReader(bytes).accept(verify,0);
  for(MethodNode m:verify.methods)if(unchanged.containsKey(m.name+m.desc)&&!unchanged.get(m.name+m.desc).equals(proof(m)))throw new IllegalStateException(m.name);
  Path out=Path.of(args[2],path);Files.createDirectories(out.getParent());Files.write(out,bytes);
  System.out.println("Snapshot export methods replaced; original non-snapshot deletion body retained; other methods verified="+unchanged.size());
 }
}
