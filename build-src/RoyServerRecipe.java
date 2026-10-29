import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class RoyServerRecipe implements Opcodes {
    private static final String CELL = "zombie/iso/IsoCell.class";
    private static final String CACHE = "zombie/network/PacketsCache.class";
    private static final String PACKER = "zombie/popman/NetworkZombiePacker.class";
    private static final String VECTOR = "zombie/iso/Vector2.class";
    private static final String LOS = "zombie/network/ServerLOS.class";
    private static final String[] CLASSES = {CELL, CACHE, PACKER, VECTOR, LOS};

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--hashes")) {
            try (ZipFile source = new ZipFile(args[1])) {
                System.out.print("{");
                for (int i=0; i<CLASSES.length; i++) {
                    if (i>0) System.out.print(",");
                    System.out.print("\""+CLASSES[i]+"\":\""+hash(transform(CLASSES[i],read(source,CLASSES[i])))+"\"");
                }
                System.out.println("}");
            }
            return;
        }
        if (args.length != 4+CLASSES.length*2) throw new IllegalArgumentException("Expected game, helpers, output, and eleven hashes");
        Path game = Path.of(args[0]), helpers = Path.of(args[1]), output = Path.of(args[2]);
        expect(hash(Files.readAllBytes(game)), args[3], "game jar");
        TreeMap<String, byte[]> entries = new TreeMap<>();
        try (ZipFile source = new ZipFile(game.toFile()); ZipFile own = new ZipFile(helpers.toFile())) {
            for (int i=0; i<CLASSES.length; i++) {
                String name=CLASSES[i];
                byte[] original=read(source,name);
                expect(hash(original),args[4+i],name);
                byte[] patched=transform(name,original);
                expect(hash(patched),args[4+CLASSES.length+i],"patched "+name);
                entries.put(name,patched);
            }
            var iterator = own.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (!entry.getName().startsWith("roy/pzcore/") || entry.getName().contains("PacketInbox"))
                    throw new IllegalStateException("Unexpected helper " + entry.getName());
                entries.put(entry.getName(), read(own, entry.getName()));
            }
        }
        if (entries.size() != 12) throw new IllegalStateException("Expected five local game classes and seven own helper classes");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0, 0));
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        System.out.print("{\"artifact_sha256\":\"" + hash(Files.readAllBytes(output)) + "\",\"entries\":{");
        boolean first = true;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!first) System.out.print(",");
            first = false;
            System.out.print("\"" + entry.getKey() + "\":\"" + hash(entry.getValue()) + "\"");
        }
        System.out.println("},\"game_server_transformed\":false,\"packet_inbox_included\":false}");
    }

    private static byte[] transform(String name, byte[] source) {
        return switch (name) {
            case CELL -> IsoCellTransform.transform(source);
            case CACHE -> transformRates(source);
            case PACKER -> ZombieRequestTransform.transform(NativeMembershipTransform.transform(source));
            case VECTOR -> ServerDirectionTransform.vector(source);
            case LOS -> ServerDirectionTransform.serverLos(source);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IllegalStateException("Missing class " + name);
        try (var input = zip.getInputStream(entry)) { return input.readAllBytes(); }
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void expect(String actual, String expected, String name) {
        if (!actual.equals(expected)) throw new IllegalStateException("Hash mismatch: " + name);
    }

    static byte[] transformRates(byte[] input) {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!node.name.equals("zombie/network/PacketsCache")) throw new IllegalStateException("Unexpected cache class");
        if (node.fields.stream().anyMatch(f -> f.name.equals("royRateWindows"))) throw new IllegalStateException("Already transformed");
        node.fields.add(new FieldNode(ACC_PRIVATE, "royRateWindows", "Lroy/pzcore/PacketRateWindows;", null, null));
        int hooks = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("isLimitExceeded") || !method.desc.equals("(Lzombie/network/PacketTypes$PacketType;)Z")) continue;
            if ((method.access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE)) != 0) throw new IllegalStateException("Unexpected rate method access");
            InsnList head = new InsnList();
            LabelNode original = new LabelNode(), initialized = new LabelNode();
            head.add(new FieldInsnNode(GETSTATIC, "roy/pzcore/CoreSettings", "PACKET_RATE_WINDOWS", "Z"));
            head.add(new JumpInsnNode(IFEQ, original));
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new FieldInsnNode(GETFIELD, node.name, "royRateWindows", "Lroy/pzcore/PacketRateWindows;"));
            head.add(new JumpInsnNode(IFNONNULL, initialized));
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new TypeInsnNode(NEW, "roy/pzcore/PacketRateWindows"));
            head.add(new InsnNode(DUP));
            head.add(new MethodInsnNode(INVOKESPECIAL, "roy/pzcore/PacketRateWindows", "<init>", "()V", false));
            head.add(new FieldInsnNode(PUTFIELD, node.name, "royRateWindows", "Lroy/pzcore/PacketRateWindows;"));
            head.add(initialized);
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new FieldInsnNode(GETFIELD, node.name, "royRateWindows", "Lroy/pzcore/PacketRateWindows;"));
            head.add(new VarInsnNode(ALOAD, 1));
            head.add(new MethodInsnNode(INVOKESTATIC, "roy/pzcore/RoyPacketLimits", "exceeded", "(Lroy/pzcore/PacketRateWindows;Lzombie/network/PacketTypes$PacketType;)Z", false));
            head.add(new InsnNode(IRETURN));
            head.add(original);
            method.instructions.insert(head);
            hooks++;
        }
        if (hooks != 1) throw new IllegalStateException("Unexpected hook count");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
