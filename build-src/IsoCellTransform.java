import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class IsoCellTransform implements Opcodes {
    private static final String OWNER = "zombie/iso/IsoCell";
    private static final String METHOD = "ProcessRemoveItems";
    private static final String DESC = "(Ljava/util/Iterator;)V";

    private IsoCellTransform() {}

    public static byte[] transform(byte[] input) {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!OWNER.equals(node.name)) fail("Unexpected class " + node.name);

        List<MethodNode> matches = node.methods.stream()
            .filter(method -> METHOD.equals(method.name) && DESC.equals(method.desc)).toList();
        if (matches.size() != 1) fail("Expected exactly one removal method");
        MethodNode method = matches.getFirst();
        if ((method.access & (ACC_PRIVATE | ACC_STATIC)) != ACC_PRIVATE || !method.tryCatchBlocks.isEmpty()) {
            fail("Unexpected removal method access or handlers");
        }

        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) code.add(instruction);
        }
        if (code.size() != 19) fail("Expected 19 removal instructions, found " + code.size());
        checkRemoval(code, 0, "processItems", "processItemsRemove");
        checkRemoval(code, 6, "processWorldItems", "processWorldItemsRemove");
        checkClear(code, 12, "processItemsRemove");
        checkClear(code, 15, "processWorldItemsRemove");
        if (code.get(18).getOpcode() != RETURN) fail("Expected removal return");

        guard(method, code.get(0), code.get(5), "processItemsRemove");
        guard(method, code.get(6), code.get(11), "processWorldItemsRemove");
        guardServerVocals(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void guardServerVocals(ClassNode node) {
        List<MethodNode> matches = node.methods.stream()
            .filter(method -> method.name.equals("updateZombieVocals") && method.desc.equals("()V")).toList();
        if (matches.size() != 1) fail("Expected one zombie vocals method");
        MethodNode method = matches.getFirst();
        if ((method.access & (ACC_PRIVATE | ACC_STATIC)) != ACC_PRIVATE || !method.tryCatchBlocks.isEmpty()) {
            fail("Unexpected zombie vocals method access or handlers");
        }
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) code.add(instruction);
        }
        int[] expected = {ICONST_0, ISTORE, ILOAD, ALOAD, GETFIELD, INVOKEVIRTUAL, IF_ICMPGE,
            ALOAD, GETFIELD, ILOAD, INVOKEVIRTUAL, CHECKCAST, ASTORE, ALOAD, INVOKEVIRTUAL,
            IINC, GOTO, RETURN};
        if (code.size() != expected.length) fail("Zombie vocals instruction count changed");
        for (int index = 0; index < expected.length; index++) {
            if (code.get(index).getOpcode() != expected[index]) fail("Zombie vocals opcode changed at " + index);
        }
        checkField(code.get(4), "zombieList", "Ljava/util/ArrayList;");
        checkField(code.get(8), "zombieList", "Ljava/util/ArrayList;");
        checkCall(code.get(5), INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false);
        checkCall(code.get(10), INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false);
        checkCall(code.get(14), INVOKEVIRTUAL, "zombie/characters/IsoZombie", "updateVocalProperties", "()V", false);
        LabelNode original = new LabelNode();
        InsnList head = new InsnList();
        head.add(new FieldInsnNode(GETSTATIC, "roy/pzcore/CoreSettings", "SERVER_ZOMBIE_VOCALS_GUARD", "Z"));
        head.add(new JumpInsnNode(IFEQ, original));
        head.add(new FieldInsnNode(GETSTATIC, "zombie/network/GameServer", "server", "Z"));
        head.add(new JumpInsnNode(IFEQ, original));
        head.add(new InsnNode(RETURN));
        head.add(original);
        head.add(new FrameNode(F_SAME, 0, null, 0, null));
        method.instructions.insert(head);
    }

    private static void checkRemoval(List<AbstractInsnNode> code, int offset, String list, String removals) {
        checkThis(code.get(offset));
        checkField(code.get(offset + 1), list, "Ljava/util/ArrayList;");
        checkThis(code.get(offset + 2));
        checkField(code.get(offset + 3), removals, "Ljava/util/Set;");
        checkCall(code.get(offset + 4), INVOKEVIRTUAL, "java/util/ArrayList", "removeAll", "(Ljava/util/Collection;)Z", false);
        if (code.get(offset + 5).getOpcode() != POP) fail("Expected discarded removeAll result");
    }

    private static void checkClear(List<AbstractInsnNode> code, int offset, String removals) {
        checkThis(code.get(offset));
        checkField(code.get(offset + 1), removals, "Ljava/util/Set;");
        checkCall(code.get(offset + 2), INVOKEINTERFACE, "java/util/Set", "clear", "()V", true);
    }

    private static void checkThis(AbstractInsnNode instruction) {
        if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 0) {
            fail("Expected this receiver");
        }
    }

    private static void checkField(AbstractInsnNode instruction, String name, String descriptor) {
        if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != GETFIELD
            || !OWNER.equals(field.owner) || !name.equals(field.name) || !descriptor.equals(field.desc)) {
            fail("Unexpected field " + name);
        }
    }

    private static void checkCall(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor, boolean itf) {
        if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != opcode || !owner.equals(call.owner)
            || !name.equals(call.name) || !descriptor.equals(call.desc) || call.itf != itf) {
            fail("Unexpected call " + name);
        }
    }

    private static void guard(MethodNode method, AbstractInsnNode first, AbstractInsnNode last, String removals) {
        LabelNode original = new LabelNode();
        LabelNode skip = new LabelNode();
        InsnList head = new InsnList();
        head.add(new FieldInsnNode(GETSTATIC, "roy/pzcore/CoreSettings", "EMPTY_REMOVAL_GUARD", "Z"));
        head.add(new JumpInsnNode(IFEQ, original));
        head.add(new VarInsnNode(ALOAD, 0));
        head.add(new FieldInsnNode(GETFIELD, OWNER, removals, "Ljava/util/Set;"));
        head.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", true));
        head.add(new JumpInsnNode(IFNE, skip));
        head.add(original);
        head.add(new FrameNode(F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(first, head);
        InsnList tail = new InsnList();
        tail.add(skip);
        tail.add(new FrameNode(F_SAME, 0, null, 0, null));
        method.instructions.insert(last, tail);
    }

    private static void fail(String message) {
        throw new IllegalStateException("IsoCell shape mismatch: " + message);
    }
}
