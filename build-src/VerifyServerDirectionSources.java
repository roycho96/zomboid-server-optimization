
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class VerifyServerDirectionSources implements Opcodes {
    private static final String MATH="zombie/core/math/PZMath";
    private static final Set<String> PURE=Set.of("wrap(FFF)F","wrap(FF)F","max(FF)F","min(FF)F","frac(F)F","floor(F)F","fastfloor(F)I");
    private static int instructions;
    private static void pure(ClassNode node,MethodNode method,boolean vector) {
        if((method.access&(ACC_STATIC|ACC_NATIVE|ACC_SYNCHRONIZED|ACC_ABSTRACT))!=ACC_STATIC||!method.tryCatchBlocks.isEmpty())
            throw new IllegalStateException("Unexpected math method "+method.name+method.desc);
        for(AbstractInsnNode instruction:method.instructions) {
            if(instruction.getOpcode()<0)continue;
            instructions++;
            if(instruction instanceof FieldInsnNode||instruction instanceof TypeInsnNode||instruction instanceof InvokeDynamicInsnNode||
                instruction instanceof MultiANewArrayInsnNode||instruction.getOpcode()==NEWARRAY||instruction.getOpcode()==MONITORENTER||
                instruction.getOpcode()==MONITOREXIT||instruction.getOpcode()==ATHROW)
                throw new IllegalStateException("Stateful math operation "+method.name);
            if(instruction instanceof MethodInsnNode call) {
                boolean allowed=call.getOpcode()==INVOKESTATIC&&
                    (call.owner.equals(MATH)&&PURE.contains(call.name+call.desc)||
                    vector&&call.owner.equals("java/lang/Math")&&call.name.equals("atan2")&&call.desc.equals("(DD)D"));
                if(!allowed)throw new IllegalStateException("Unknown math call "+call.owner+"."+call.name+call.desc);
            }
        }
    }
    private static byte[] read(ZipFile jar,String name)throws Exception {return jar.getInputStream(jar.getEntry(name+".class")).readAllBytes();}
    public static void main(String[] args)throws Exception {
        try(ZipFile stock=new ZipFile(args[0]);ZipFile opt=args[1].equals("-")?null:new ZipFile(args[1])){
            byte[] vector=read(stock,"zombie/iso/Vector2"),math=read(stock,MATH),los=read(stock,"zombie/network/ServerLOS");
            if(opt!=null&&opt.getEntry("zombie/iso/Vector2.class")!=null&&!Arrays.equals(vector,read(opt,"zombie/iso/Vector2")))throw new AssertionError("Vector2 override");
            if(opt!=null&&opt.getEntry(MATH+".class")!=null&&!Arrays.equals(math,read(opt,MATH)))throw new AssertionError("PZMath override");
            if(opt!=null&&opt.getEntry("zombie/network/ServerLOS.class")!=null&&!Arrays.equals(los,read(opt,"zombie/network/ServerLOS")))throw new AssertionError("ServerLOS override");
            if(!ServerDirectionTransform.sha(math).equals("1108e80daab0ccb6ca30f4ac16f70daca508a7271604b73af0f88895adab3fb1"))throw new AssertionError("Changed math source");
            ClassNode v=ServerDirectionTransform.read(vector),m=ServerDirectionTransform.read(math),s=ServerDirectionTransform.read(los);
            pure(v,ServerDirectionTransform.find(v,"getDirection","(FF)F"),true);
            for(String signature:PURE){int p=signature.indexOf('(');pure(m,ServerDirectionTransform.find(m,signature.substring(0,p),signature.substring(p)),false);}
            ServerDirectionTransform.vector(vector);ServerDirectionTransform.serverLos(los);
            System.out.println("{\"verified\":true,\"passed\":true,\"math_closure_methods\":8,\"math_instructions_reviewed\":"+instructions+
                ",\"pure_math_no_fields_or_allocations\":true,\"vector_optimizer_override\":false,\"math_optimizer_override\":false,\"los_optimizer_override\":false,"+
                "\"vector_sha256\":\""+ServerDirectionTransform.sha(vector)+"\",\"math_sha256\":\""+ServerDirectionTransform.sha(math)+"\",\"los_sha256\":\""+ServerDirectionTransform.sha(los)+"\","+
                "\"scope\":\"Exact native classes and known PZOpt only; arbitrary reflection/instrumentation is outside the contract\"}");
        }
    }
}