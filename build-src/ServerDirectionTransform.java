
import java.security.MessageDigest;
import java.util.Arrays;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class ServerDirectionTransform implements Opcodes {
    private ServerDirectionTransform(){}
    public static byte[] vector(byte[] input) {
        if(!sha(input).equals("9d6c5bb38ab9dfe5eab3edf7ba02f0f2442f0d84db4d330f494f528f198b6ce9"))throw new IllegalStateException("Changed Vector2");
        ClassNode node=read(input);
        MethodNode old=find(node,"getDirection","(FF)F");
        MethodNode method=new MethodNode(old.access,old.name,old.desc,old.signature,null);
        method.instructions.add(new VarInsnNode(FLOAD,0));method.instructions.add(new VarInsnNode(FLOAD,1));
        method.instructions.add(new MethodInsnNode(INVOKESTATIC,"roy/pzcore/ServerDirectionMemo","direction","(FF)F",false));
        method.instructions.add(new InsnNode(FRETURN));
        node.methods.set(node.methods.indexOf(old),method);
        int flags=ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS;
        byte[] result=write(node,flags);ClassNode reversed=read(result);
        reversed.methods.set(reversed.methods.indexOf(find(reversed,"getDirection","(FF)F")),old);
        if(!Arrays.equals(write(read(input),flags),write(reversed,flags)))throw new AssertionError("Vector patch scope changed");
        return result;
    }
    public static byte[] serverLos(byte[] input) {
        if(!sha(input).equals("e20464c8afe47944913033ac4de0672980696ceac8e3ef59d22d24bac9ec546c"))throw new IllegalStateException("Changed ServerLOS");
        ClassNode node=read(input);
        String desc="(Lzombie/characters/IsoPlayer;)V";
        MethodNode old=find(read(input),"updateLOS",desc),method=find(node,"updateLOS",desc);
        int token=method.maxLocals,error=token+1;
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==RETURN){
            InsnList close=new InsnList();close.add(new VarInsnNode(ILOAD,token));
            close.add(new MethodInsnNode(INVOKESTATIC,"roy/pzcore/ServerDirectionMemo","leave","(I)V",false));
            method.instructions.insertBefore(instruction,close);
        }
        InsnList head=new InsnList();head.add(new InsnNode(ICONST_M1));head.add(new VarInsnNode(ISTORE,token));head.add(start);
        head.add(new MethodInsnNode(INVOKESTATIC,"roy/pzcore/ServerDirectionMemo","enter","()I",false));head.add(new VarInsnNode(ISTORE,token));
        method.instructions.insert(head);method.instructions.add(end);method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(ASTORE,error));method.instructions.add(new VarInsnNode(ILOAD,token));
        method.instructions.add(new MethodInsnNode(INVOKESTATIC,"roy/pzcore/ServerDirectionMemo","leave","(I)V",false));
        method.instructions.add(new VarInsnNode(ALOAD,error));method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
        int flags=ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS;
        byte[] result=write(node,flags);ClassNode reversed=read(result);
        reversed.methods.set(reversed.methods.indexOf(find(reversed,"updateLOS",desc)),old);
        if(!Arrays.equals(write(read(input),flags),write(reversed,flags)))throw new AssertionError("ServerLOS patch scope changed");
        if(reversed.fields.size()!=read(input).fields.size())throw new AssertionError("ServerLOS fields changed");
        return result;
    }
    public static ClassNode read(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    public static MethodNode find(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();}
    public static byte[] write(ClassNode node,int flags){ClassWriter writer=new ClassWriter(flags);node.accept(writer);return writer.toByteArray();}
    public static String sha(byte[] bytes){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new RuntimeException(e);}}
}