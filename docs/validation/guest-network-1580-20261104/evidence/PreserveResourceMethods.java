import java.nio.file.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import java.util.*;
public class PreserveResourceMethods {
 public static void main(String[] a) throws Exception {
  Set<String> replace=Set.of("getBroadcastUriFromBridge", "getVlanIdFromBridgeName");
  ClassReader base=new ClassReader(Files.readAllBytes(Path.of(a[0])));
  ClassNode update=new ClassNode();new ClassReader(Files.readAllBytes(Path.of(a[1]))).accept(update,0);
  ClassWriter output=new ClassWriter(base,0);
  ClassVisitor visitor=new ClassVisitor(Opcodes.ASM8,output) {
   public MethodVisitor visitMethod(int acc,String name,String desc,String sig,String[] ex) {
    return replace.contains(name) ? null : super.visitMethod(acc,name,desc,sig,ex);
   }
   public void visitEnd() {
    for(MethodNode method:update.methods) if(replace.contains(method.name)) method.accept(output);
    super.visitEnd();
   }
  };
  base.accept(visitor,0);Files.write(Path.of(a[2]),output.toByteArray());
 }
}