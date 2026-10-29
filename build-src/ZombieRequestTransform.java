import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ZombieRequestTransform implements Opcodes {
    private static final String OWNER="zombie/popman/NetworkZombiePacker";
    private static final String HELPER="roy/pzcore/ZombieRequestIndex";
    private static final Set<String> INPUTS=Set.of(
        "5b202038b360877e8a308265e6bf7ce720e34d0c3670adfb3a7c5dbddaf5197e",
        "d3322082b94e018abde4a648591dcbae4743814378083f0962c21e02cd47434a");
    private ZombieRequestTransform() {}

    public static byte[] transform(byte[] input) {
        if(!INPUTS.contains(hash(input)))throw new IllegalStateException("Unknown Packer source");
        ClassNode node=read(input);
        if(!node.name.equals(OWNER))throw new IllegalStateException("Unexpected class");
        MethodNode method=node.methods.stream().filter(m->m.name.equals("postupdate")&&m.desc.equals("()V")).findFirst().orElseThrow();
        MethodNode original=copy(method);
        MethodInsnNode contains=null,iterator=null;
        int containsCount=0,iteratorCount=0,addCount=0;
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals(HELPER))throw new IllegalStateException("Already transformed");
            if(call.owner.equals("java/util/LinkedList")&&call.name.equals("contains")&&call.desc.equals("(Ljava/lang/Object;)Z")){contains=call;containsCount++;}
            if(call.owner.equals("java/util/ArrayList")&&call.name.equals("iterator")&&call.desc.equals("()Ljava/util/Iterator;")){iterator=call;iteratorCount++;}
            if(call.owner.equals("java/util/LinkedList")&&call.name.equals("add")&&call.desc.equals("(Ljava/lang/Object;)Z"))addCount++;
        }
        if(containsCount!=1||iteratorCount!=1||addCount!=1)throw new IllegalStateException("Unexpected auth loop shape");
        AbstractInsnNode value=before(contains),field=before(value),base=before(field);
        requireLoad(value,11);requireLoad(base,8);
        requireField(field,"zombie/popman/NetworkZombieList$NetworkZombie","zombies","Ljava/util/LinkedList;");
        AbstractInsnNode branch=after(contains);
        if(!(branch instanceof JumpInsnNode jump)||jump.getOpcode()!=IFNE)throw new IllegalStateException("Missing append branch");
        AbstractInsnNode appendBase=after(branch),appendField=after(appendBase),appendValue=after(appendField),appendCall=after(appendValue);
        requireLoad(appendBase,8);requireLoad(appendValue,11);
        requireField(appendField,"zombie/popman/NetworkZombieList$NetworkZombie","zombies","Ljava/util/LinkedList;");
        if(!(appendCall instanceof MethodInsnNode add)||!add.owner.equals("java/util/LinkedList")||!add.name.equals("add")
            ||!add.desc.equals("(Ljava/lang/Object;)Z")||after(add).getOpcode()!=POP)throw new IllegalStateException("Append was changed");
        AbstractInsnNode authField=before(iterator),authBase=before(authField);
        requireLoad(authBase,3);
        requireField(authField,"zombie/network/packets/character/ZombieListPacket","zombiesAuth","Ljava/util/ArrayList;");
        int local=method.maxLocals;
        method.maxLocals++;
        InsnList prepare=new InsnList();
        prepare.add(new VarInsnNode(ALOAD,8));
        prepare.add(new FieldInsnNode(GETFIELD,"zombie/popman/NetworkZombieList$NetworkZombie","zombies","Ljava/util/LinkedList;"));
        prepare.add(new VarInsnNode(ALOAD,3));
        prepare.add(new FieldInsnNode(GETFIELD,"zombie/network/packets/character/ZombieListPacket","zombiesAuth","Ljava/util/ArrayList;"));
        prepare.add(new MethodInsnNode(INVOKEVIRTUAL,"java/util/ArrayList","size","()I",false));
        prepare.add(new MethodInsnNode(INVOKESTATIC,HELPER,"prepare","(Ljava/util/LinkedList;I)Ljava/util/IdentityHashMap;",false));
        prepare.add(new VarInsnNode(ASTORE,local));
        method.instructions.insertBefore(authBase,prepare);
        method.instructions.insertBefore(contains,new VarInsnNode(ALOAD,local));
        contains.setOpcode(INVOKESTATIC);contains.owner=HELPER;contains.name="containsOrRemember";
        contains.desc="(Ljava/util/LinkedList;Ljava/lang/Object;Ljava/util/IdentityHashMap;)Z";
        byte[] output=bytes(node);
        ClassNode reversed=read(output);
        for(int i=0;i<reversed.methods.size();i++)if(reversed.methods.get(i).name.equals("postupdate")&&reversed.methods.get(i).desc.equals("()V"))reversed.methods.set(i,original);
        if(!Arrays.equals(bytes(read(input)),bytes(reversed)))throw new AssertionError("An unrelated class member changed");
        return output;
    }

    private static MethodNode copy(MethodNode method){MethodNode result=new MethodNode(ASM9,method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(result);return result;}
    private static void requireLoad(AbstractInsnNode node,int local){if(!(node instanceof VarInsnNode v)||v.getOpcode()!=ALOAD||v.var!=local)throw new IllegalStateException("Unknown local load");}
    private static void requireField(AbstractInsnNode node,String owner,String name,String desc){if(!(node instanceof FieldInsnNode f)||f.getOpcode()!=GETFIELD||!f.owner.equals(owner)||!f.name.equals(name)||!f.desc.equals(desc))throw new IllegalStateException("Unknown auth field");}
    private static AbstractInsnNode before(AbstractInsnNode node){do{node=node.getPrevious();}while(node!=null&&node.getOpcode()<0);return node;}
    private static AbstractInsnNode after(AbstractInsnNode node){do{node=node.getNext();}while(node!=null&&node.getOpcode()<0);return node;}
    private static ClassNode read(byte[] input){ClassNode n=new ClassNode();new ClassReader(input).accept(n,0);return n;}
    private static byte[] bytes(ClassNode node){ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(w);return w.toByteArray();}
    private static String hash(byte[] input){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));}catch(Exception e){throw new IllegalStateException(e);}}
}
