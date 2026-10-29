import java.security.MessageDigest;
import java.util.Arrays;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class NativeMembershipTransform implements Opcodes {
    private static final String OWNER = "zombie/popman/NetworkZombiePacker";
    private static final String HELPER = "roy/pzcore/ServerZombieMembership";
    private static final String SOURCE_SHA256 = "5b202038b360877e8a308265e6bf7ce720e34d0c3670adfb3a7c5dbddaf5197e";
    private static final String METHOD = "parseZombie";
    private static final String DESC = "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";

    private NativeMembershipTransform() {}

    public static byte[] transform(byte[] input) {
        if (!hash(input).equals(SOURCE_SHA256)) throw new IllegalStateException("Re-audit changed NetworkZombiePacker before patching");
        ClassNode node = read(input);
        if (!node.name.equals(OWNER)) throw new IllegalArgumentException("Unexpected packer class");
        var matches = node.methods.stream().filter(method -> method.name.equals(METHOD) && method.desc.equals(DESC)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one zombie parse method");
        MethodNode parse = matches.getFirst();
        int replaced = 0;
        for (AbstractInsnNode instruction : parse.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals("java/util/ArrayList")
                || !call.name.equals("contains") || !call.desc.equals("(Ljava/lang/Object;)Z")) continue;
            AbstractInsnNode load = codeBefore(call), getter = codeBefore(load);
            if (call.getOpcode() != INVOKEVIRTUAL || call.itf || !(load instanceof VarInsnNode variable)
                || variable.getOpcode() != ALOAD || variable.var != 3 || !(getter instanceof MethodInsnNode prior)
                || prior.getOpcode() != INVOKEVIRTUAL || !prior.owner.equals("zombie/iso/IsoCell")
                || !prior.name.equals("getZombieList") || !prior.desc.equals("()Ljava/util/ArrayList;"))
                throw new IllegalStateException("Unknown zombie membership site");
            call.setOpcode(INVOKESTATIC); call.owner = HELPER;
            call.desc = "(Ljava/util/ArrayList;Lzombie/characters/IsoZombie;)Z";
            replaced++;
        }
        if (replaced != 1) throw new IllegalStateException("Expected exactly one membership site");
        byte[] output = bytes(node);
        ClassNode reversed = read(output);
        int restored = 0;
        for (MethodNode method : reversed.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HELPER)) {
                if (!method.name.equals(METHOD) || !method.desc.equals(DESC)) throw new AssertionError("Unrelated method changed");
                call.setOpcode(INVOKEVIRTUAL); call.owner = "java/util/ArrayList"; call.desc = "(Ljava/lang/Object;)Z"; restored++;
            }
        }
        if (restored != 1 || !Arrays.equals(bytes(read(input)), bytes(reversed)))
            throw new AssertionError("Membership patch changed more than the one call");
        return output;
    }

    private static AbstractInsnNode codeBefore(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static ClassNode read(byte[] input) { ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node; }
    private static byte[] bytes(ClassNode node) { ClassWriter output = new ClassWriter(0); node.accept(output); return output.toByteArray(); }
    private static String hash(byte[] input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
