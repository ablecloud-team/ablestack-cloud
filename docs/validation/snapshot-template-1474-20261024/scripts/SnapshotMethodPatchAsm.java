import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
public class SnapshotMethodPatchAsm {
 static String text(MethodNode m){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"Proof",null,"java/lang/Object",null);m.accept(w);w.visitEnd();return Base64.getEncoder().encodeToString(w.toByteArray());}
 public static void main(String[] args)throws Exception{
  String path=(args.length>3?args[3]:"org.apache.cloudstack.storage.resource.NfsSecondaryStorageResource").replace('.','/')+".class";
  byte[] oldBytes;try(JarFile jar=new JarFile(args[0])){oldBytes=jar.getInputStream(jar.getJarEntry(path)).readAllBytes();}
  ClassNode old=new ClassNode(),fresh=new ClassNode();new ClassReader(oldBytes).accept(old,0);new ClassReader(Files.readAllBytes(Path.of(args[1],path))).accept(fresh,0);
  Set<String> names=args.length>4?Set.of(args[4].split(",")):Set.of("copySnapshotToTemplateFromNfsToNfs","inspectSnapshotImage","copySnapshotImage","removeConvertedSnapshotIfNeeded","getSnapshotImageFile");
  Map<String,String> unchanged=new HashMap<>();for(MethodNode m:old.methods)if(!names.contains(m.name))unchanged.put(m.name+m.desc,text(m));
  old.methods.removeIf(m->names.contains(m.name));for(MethodNode m:fresh.methods)if(names.contains(m.name))old.methods.add(m);
  ClassWriter writer=new ClassWriter(0);old.accept(writer);byte[] bytes=writer.toByteArray();ClassNode verify=new ClassNode();new ClassReader(bytes).accept(verify,0);
  for(MethodNode m:verify.methods)if(unchanged.containsKey(m.name+m.desc)&&!unchanged.get(m.name+m.desc).equals(text(m)))throw new IllegalStateException(m.name);
  Path out=Path.of(args[2],path);Files.createDirectories(out.getParent());Files.write(out,bytes);System.out.println("ASM method patch; unchanged methods including bootstrap recipes="+unchanged.size());
 }
}
