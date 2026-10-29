import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class VerifyNativeMembershipSources implements Opcodes {
    private static final String ZOMBIE = "zombie/characters/IsoZombie";
    private static final String PACKER = "zombie/popman/NetworkZombiePacker.class";

    private record Identity(List<String> chain, String rootHash) {}

    private VerifyNativeMembershipSources() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected stock jar and optimizer jar or -");
        byte[] arrayList;
        try (var input = java.util.ArrayList.class.getResourceAsStream("ArrayList.class")) { arrayList = input.readAllBytes(); }
        verifyArrayList(arrayList);
        Path stockPath = Path.of(args[0]), optPath = args[1].equals("-") ? null : Path.of(args[1]);
        try (ZipFile stock = new ZipFile(stockPath.toFile()); ZipFile opt = optPath == null ? null : new ZipFile(optPath.toFile())) {
            Identity baseline = identity(stock, null), active = identity(stock, opt);
            byte[] stockPacker = read(stock, PACKER);
            ZipFile activeSource = opt != null && opt.getEntry(PACKER) != null ? opt : stock;
            byte[] activePacker = read(activeSource, PACKER);
            NativeMembershipTransform.transform(stockPacker);
            byte[] generated = NativeMembershipTransform.transform(activePacker);
            System.out.println("{\"verified\":true,\"game_sha256\":" + json(hash(Files.readAllBytes(stockPath)))
                + ",\"optimizer_sha256\":" + (optPath == null ? "null" : json(hash(Files.readAllBytes(optPath))))
                + ",\"stock_packer_sha256\":" + json(hash(stockPacker)) + ",\"active_packer_sha256\":" + json(hash(activePacker))
                + ",\"generated_packer_sha256\":" + json(hash(generated))
                + ",\"stock_zombie_sha256\":" + json(baseline.rootHash) + ",\"active_zombie_sha256\":" + json(active.rootHash)
                + ",\"stock_identity_chain\":[" + String.join(",", baseline.chain) + "],\"active_identity_chain\":[" + String.join(",", active.chain)
                + "],\"jdk_arraylist_sha256\":" + json(hash(arrayList))
                + ",\"arraylist_contains_indexof_equivalent\":true,\"only_one_membership_call_changed\":true,\"online_id_signed_short\":true,"
                + "\"scope\":\"Known native packet source and exact stock/optimizer equality hierarchy; no unknown Java instrumentation\"}");
        }
    }

    private static Identity identity(ZipFile stock, ZipFile optimizer) throws Exception {
        List<String> chain = new ArrayList<>(); HashSet<String> visited = new HashSet<>(); String rootHash = null;
        for (String name = ZOMBIE; !name.equals("java/lang/Object");) {
            if (!visited.add(name)) throw new IllegalStateException("Zombie inheritance cycle");
            String entry = name + ".class";
            ZipFile source = optimizer != null && optimizer.getEntry(entry) != null ? optimizer : stock;
            byte[] input = read(source, entry); ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!node.name.equals(name) || node.superName == null) throw new IllegalStateException("Unknown zombie class hierarchy");
            if (name.equals(ZOMBIE)) {
                rootHash = hash(input);
                if ((node.access & ACC_FINAL) == 0) throw new IllegalStateException("Zombie class is no longer final");
                if (node.fields.stream().noneMatch(field -> field.name.equals("onlineId") && field.desc.equals("S")
                    && (field.access & (ACC_PUBLIC | ACC_STATIC)) == ACC_PUBLIC))
                    throw new IllegalStateException("Zombie onlineId field changed");
            }
            if (node.methods.stream().anyMatch(method -> method.name.equals("equals") && method.desc.equals("(Ljava/lang/Object;)Z")))
                throw new IllegalStateException("Zombie equality is not Object identity: " + name);
            chain.add("{\"class\":" + json(name) + ",\"origin\":" + json(source == optimizer ? "optimizer" : "stock") + ",\"sha256\":" + json(hash(input)) + "}");
            name = node.superName;
        }
        return new Identity(chain, rootHash);
    }

    private static void verifyArrayList(byte[] input) {
        ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
        MethodNode contains = node.methods.stream().filter(method -> method.name.equals("contains") && method.desc.equals("(Ljava/lang/Object;)Z")).findFirst().orElseThrow();
        if (!contains.tryCatchBlocks.isEmpty() || (contains.access & (ACC_STATIC | ACC_SYNCHRONIZED | ACC_NATIVE)) != 0)
            throw new IllegalStateException("Unknown ArrayList.contains implementation");
        List<Integer> opcodes = new ArrayList<>(); List<AbstractInsnNode> code = new ArrayList<>(); MethodInsnNode lookup = null;
        for (AbstractInsnNode instruction : contains.instructions) if (instruction.getOpcode() >= 0) {
            opcodes.add(instruction.getOpcode()); code.add(instruction); if (instruction instanceof MethodInsnNode call) lookup = call;
        }
        if (!opcodes.equals(List.of(ALOAD, ALOAD, INVOKEVIRTUAL, IFLT, ICONST_1, GOTO, ICONST_0, IRETURN))
            || lookup == null || !lookup.owner.equals("java/util/ArrayList") || !lookup.name.equals("indexOf")
            || !lookup.desc.equals("(Ljava/lang/Object;)I") || lookup.itf)
            throw new IllegalStateException("Re-audit JDK ArrayList.contains delegation");
        if (!(code.get(0) instanceof VarInsnNode receiver) || receiver.var != 0
            || !(code.get(1) instanceof VarInsnNode argument) || argument.var != 1
            || nextCode(((JumpInsnNode) code.get(3)).label) != code.get(6)
            || nextCode(((JumpInsnNode) code.get(5)).label) != code.get(7))
            throw new IllegalStateException("ArrayList.contains no longer returns indexOf >= 0");
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    private static byte[] read(ZipFile source, String entry) throws Exception {
        if (source.getEntry(entry) == null) throw new IllegalStateException("Missing native source " + entry);
        try (var input = source.getInputStream(source.getEntry(entry))) { return input.readAllBytes(); }
    }
    private static String hash(byte[] input) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
    private static String json(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
