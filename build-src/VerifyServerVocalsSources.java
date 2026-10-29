import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class VerifyServerVocalsSources implements Opcodes {
    private static final String ZOMBIE = "zombie/characters/IsoZombie";

    private record Shape(String parent, boolean hasVocals, int access, ZipFile source, String entry) {}

    private VerifyServerVocalsSources() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected game jar and optimizer jar or -");
        Path stockPath = Path.of(args[0]);
        Path optPath = args[1].equals("-") ? null : Path.of(args[1]);
        try (ZipFile stock = new ZipFile(stockPath.toFile());
             ZipFile opt = optPath == null ? null : new ZipFile(optPath.toFile())) {
            Map<String, Shape> shapes = new HashMap<>();
            inventory(stock, shapes);
            List<String> stockChecked = verifyGraph(shapes);
            String stockZombie = hash(read(stock, ZOMBIE + ".class"));
            if (opt != null) inventory(opt, shapes);
            List<String> activeChecked = verifyGraph(shapes);
            Shape active = shapes.get(ZOMBIE);
            String activeZombie = hash(read(active.source, active.entry));
            System.out.println("{\"verified\":true,\"stock_classes_checked\":" + stockChecked.size()
                + ",\"active_classes_checked\":" + activeChecked.size()
                + ",\"stock_overrides\":" + json(stockChecked.toString())
                + ",\"active_overrides\":" + json(activeChecked.toString())
                + ",\"stock_zombie_sha256\":" + json(stockZombie)
                + ",\"active_zombie_sha256\":" + json(activeZombie)
                + ",\"game_sha256\":" + json(hash(Files.readAllBytes(stockPath)))
                + ",\"optimizer_sha256\":" + (optPath == null ? "null" : json(hash(Files.readAllBytes(optPath))))
                + ",\"scope\":\"Current stock and optimizer class hierarchies; no unknown runtime-generated overrides\"}");
        }
    }

    private static void inventory(ZipFile source, Map<String, Shape> shapes) throws Exception {
        var entries = source.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")) continue;
            ClassNode node = new ClassNode();
            new ClassReader(read(source, entry.getName())).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean vocals = node.methods.stream().anyMatch(method -> method.name.equals("updateVocalProperties")
                && method.desc.equals("()V") && (method.access & ACC_ABSTRACT) == 0);
            shapes.put(node.name, new Shape(node.superName, vocals, node.access, source, entry.getName()));
        }
    }

    private static List<String> verifyGraph(Map<String, Shape> shapes) throws Exception {
        if (!shapes.containsKey(ZOMBIE)) throw new IllegalStateException("Missing zombie base class");
        List<String> checked = new ArrayList<>();
        for (Map.Entry<String, Shape> entry : shapes.entrySet()) {
            String name = entry.getKey();
            Shape shape = entry.getValue();
            if (!shape.hasVocals || !isZombie(name, shapes)) continue;
            ClassNode node = new ClassNode();
            new ClassReader(read(shape.source, shape.entry)).accept(node, 0);
            MethodNode method = node.methods.stream().filter(item -> item.name.equals("updateVocalProperties")
                && item.desc.equals("()V")).findFirst().orElseThrow();
            verifyNoServerEffects(name, method);
            checked.add(name);
        }
        checked.sort(String::compareTo);
        return checked;
    }

    private static boolean isZombie(String name, Map<String, Shape> shapes) {
        HashSet<String> visited = new HashSet<>();
        while (name != null && visited.add(name)) {
            if (name.equals(ZOMBIE)) return true;
            Shape shape = shapes.get(name);
            if (shape == null) return false;
            name = shape.parent;
        }
        return false;
    }

    private static void verifyNoServerEffects(String name, MethodNode method) {
        if (!method.tryCatchBlocks.isEmpty() || (method.access & (ACC_STATIC | ACC_SYNCHRONIZED | ACC_NATIVE)) != 0)
            throw new IllegalStateException("Unknown vocals method shape: " + name);
        AbstractInsnNode first = nextCode(method.instructions.getFirst());
        if (!(first instanceof FieldInsnNode field) || field.getOpcode() != GETSTATIC
            || !field.owner.equals("zombie/network/GameServer") || !field.name.equals("server") || !field.desc.equals("Z"))
            throw new IllegalStateException("Vocals lacks a leading server gate: " + name);
        AbstractInsnNode branch = nextCode(first.getNext());
        if (!(branch instanceof JumpInsnNode jump)) throw new IllegalStateException("Unknown server branch: " + name);
        AbstractInsnNode taken;
        if (branch.getOpcode() == IFEQ) taken = nextCode(branch.getNext());
        else if (branch.getOpcode() == IFNE) taken = nextCode(jump.label);
        else throw new IllegalStateException("Unknown server condition: " + name);
        if (taken.getOpcode() != RETURN) throw new IllegalStateException("Server vocals has effects: " + name);
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        if (node == null) throw new IllegalStateException("Missing instruction");
        return node;
    }

    private static byte[] read(ZipFile source, String entry) throws Exception {
        try (var input = source.getInputStream(source.getEntry(entry))) { return input.readAllBytes(); }
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
