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

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
public class FaultInjector {
 public static void main(String[] args) throws Exception {
  byte[] bytes=Files.readAllBytes(Path.of(args[0])); ClassReader reader=new ClassReader(bytes);
  ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
  reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
   @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] ex){
    MethodVisitor delegate=super.visitMethod(a,n,d,s,ex);
    boolean before=n.equals("provisionKubernetesClusterControlVm");
    boolean legacy=n.equals("recordNodeProvisioningFailure");
    boolean async=n.equals("addUserDataDetailsToCommand");
    if(!before&&!legacy&&!async)return delegate;
    return new MethodVisitor(Opcodes.ASM9,delegate){
     @Override public void visitCode(){super.visitCode(); Label pass=new Label();
      if(async){
       visitVarInsn(Opcodes.ALOAD,2); visitJumpInsn(Opcodes.IFNULL,pass);
       visitVarInsn(Opcodes.ALOAD,2); visitLdcInsn("k1410-fault");
       visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","contains","(Ljava/lang/CharSequence;)Z",false);
      }else{
       visitVarInsn(Opcodes.ALOAD,0);
       visitFieldInsn(Opcodes.GETFIELD,"com/cloud/kubernetes/cluster/actionworkers/KubernetesClusterActionWorker","kubernetesCluster","Lcom/cloud/kubernetes/cluster/KubernetesCluster;");
       visitMethodInsn(Opcodes.INVOKEINTERFACE,"com/cloud/kubernetes/cluster/KubernetesCluster","getName","()Ljava/lang/String;",true);
       visitLdcInsn(legacy?"k1410-legacy":"k1410-before");
       visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/String","startsWith","(Ljava/lang/String;)Z",false);
      }
      visitJumpInsn(Opcodes.IFEQ,pass);
      if(legacy)visitInsn(Opcodes.RETURN);
      else{
       visitTypeInsn(Opcodes.NEW,"java/lang/StringIndexOutOfBoundsException"); visitInsn(Opcodes.DUP);
       visitLdcInsn(async?"K1410_FIXTURE_ONLY_ASYNC_START":"K1410_FIXTURE_ONLY_BEFORE_NODE");
       visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/StringIndexOutOfBoundsException","<init>","(Ljava/lang/String;)V",false);visitInsn(Opcodes.ATHROW);
      }
      visitLabel(pass);visitFrame(Opcodes.F_SAME,0,null,0,null);
     }
    };
   }
  },0); Files.createDirectories(Path.of(args[1]).getParent()); Files.write(Path.of(args[1]),writer.toByteArray());
 }
}
