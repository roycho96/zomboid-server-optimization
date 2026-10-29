import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class VerifyZombieRequestSources implements Opcodes {
    private static final String PACKER="zombie/popman/NetworkZombiePacker.class";
    private static final String ZOMBIE="zombie/characters/IsoZombie";
    private static final Map<String,String> CONTRACT=Map.ofEntries(
        Map.entry("zombie/network/GameServer.class","308ee5247dc8bba4df2283d40d753c6839497767b9342a4adeea60e7c08cfacc"),
        Map.entry("zombie/core/raknet/UdpEngine.class","385c8fa80a6b24c8a7fe1770cae1e5c9ddb0d23e614297c6c0c05283e51adf36"),
        Map.entry("zombie/network/PacketTypes$PacketType.class","aa6718ed4e2e33b199734c8cfa4b85a64c193ccc307b2cc4deafe18e0a43d5f5"),
        Map.entry("zombie/network/ServerMap.class","5a3e449c6b7f3ea237939502c5b43ab0dd490a3752fd675611aade7ae79c548d"),
        Map.entry("zombie/network/packets/character/ZombieRequestPacket.class","448ca7fc6bf7b0ae6b267255eddcd4d5182b64771569ac4b34ca05a630877db9"),
        Map.entry("zombie/popman/NetworkZombieManager.class","27af8b12cc9ab2af778cbce03ed546168c69976e63a905b16279445234d89ad8"),
        Map.entry("zombie/popman/NetworkZombieList.class","3d034748587fd16651032b399a314b5c53f4f5dd42dd56a16ae281f183de06c7"),
        Map.entry("zombie/popman/NetworkZombieList$NetworkZombie.class","438fd2243660ba9083a6bd128bd86d4f3e3d0b4a7b0f0754e055a600ab0ba47f"),
        Map.entry("zombie/network/IsoObjectID.class","07bda0283a8d09bf08f10711c1809cb6d74dc933daea33e2c21c692c6a708145"));
    private static final Set<String> QUEUE_READERS=Set.of(
        "zombie/network/packets/character/ZombieRequestPacket",
        "zombie/popman/NetworkZombieList$NetworkZombie",
        "zombie/popman/NetworkZombieManager","zombie/popman/NetworkZombiePacker");
    private static final Set<String> DISPATCH=Set.of(
        "zombie/network/ServerMap.postupdate()V -> zombie/popman/NetworkZombiePacker.postupdate()V",
        "zombie/popman/NetworkZombiePacker.postupdate()V -> zombie/popman/NetworkZombiePacker.send(Lzombie/core/raknet/UdpConnection;)V",
        "zombie/popman/NetworkZombiePacker.send(Lzombie/core/raknet/UdpConnection;)V -> zombie/popman/NetworkZombiePacker.getZombieData(Lzombie/core/raknet/UdpConnection;Lzombie/network/packets/character/ZombieSynchronizationPacket;)I");
    private VerifyZombieRequestSources() {}

    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Expected stock, optimizer or -, and optional loose class root or -");
        byte[] linkedList;try(var stream=LinkedList.class.getResourceAsStream("LinkedList.class")){linkedList=stream.readAllBytes();}
        ClassNode jdk=node(linkedList,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        check(jdkShape(jdk,"contains","(Ljava/lang/Object;)Z"),"60ebffa9f410930d0e84bee6f9eeb03e6ddb300b714ef226c3323075df74d97c","JDK LinkedList.contains");
        check(jdkShape(jdk,"indexOf","(Ljava/lang/Object;)I"),"26cab2f161e65eec67a5ed05c30ace561e5f4466f358ef9a67145c47653bcdf4","JDK LinkedList.indexOf");
        Path looseRoot=args.length==3&&!args[2].equals("-")?Path.of(args[2]).toAbsolutePath().normalize():null;
        try(var stock=new ZipFile(args[0]);var optimizer=args[1].equals("-")?null:new ZipFile(args[1])){
            Sources sources=new Sources(stock,optimizer,looseRoot);
            var verified=new TreeMap<String,String>();var origins=new TreeMap<String,String>();
            for(var entry:CONTRACT.entrySet()){
                check(hash(read(stock,entry.getKey())),entry.getValue(),"stock "+entry.getKey());
                String current=hash(sources.read(entry.getKey()));
                check(current,entry.getValue(),"effective "+entry.getKey());verified.put(entry.getKey(),current);
                origins.put(entry.getKey(),sources.origin(entry.getKey()));
            }
            verifyIdentity(new Sources(stock,null,null));verifyIdentity(sources);
            Set<String> readers=new TreeSet<>(),dispatch=new TreeSet<>();
            for(String name:sources.names()){
                ClassNode current=node(sources.read(name),ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                for(MethodNode method:current.methods)for(AbstractInsnNode instruction:method.instructions){
                    if(instruction instanceof FieldInsnNode field
                        &&(field.owner.equals("zombie/popman/NetworkZombiePacker")&&field.name.equals("zombiesRequest")
                        ||field.owner.equals("zombie/popman/NetworkZombieList$NetworkZombie")&&field.name.equals("zombies")))readers.add(current.name);
                    if(instruction instanceof MethodInsnNode call&&call.owner.equals("zombie/popman/NetworkZombiePacker")
                        &&Set.of("send","getZombieData","postupdate").contains(call.name))dispatch.add(current.name+"."+method.name+method.desc+" -> "+call.owner+"."+call.name+call.desc);
                }
            }
            if(!readers.equals(QUEUE_READERS)||!dispatch.equals(DISPATCH))throw new IllegalStateException("Unreviewed auth queue reference or dispatch caller");
            byte[] composed=ZombieRequestTransform.transform(NativeMembershipTransform.transform(read(stock,PACKER)));
            System.out.print("{\"verified\":true,\"method_local_only\":true,\"native_request_writer_main_loop\":true,"
                +"\"network_disconnect_owns_registry_is_distinct\":true,\"identity_equals_verified\":true,\"jdk_linkedlist_verified\":true,"
                +"\"external_concurrent_direct_mutation_supported\":false,\"generated_composed_packer_sha256\":"+q(hash(composed))+",\"source_contract\":");
            printMap(verified);System.out.print(",\"effective_source_origins\":");printMap(origins);
            System.out.print(",\"loose_root_checked\":"+(looseRoot!=null)+",\"loose_class_count\":"+sources.loose.size()
                +",\"unloadable_relative_name_count\":"+sources.mismatchedRelativeNames+",\"loose_catalog_sha256\":"+q(sources.catalogHash())+",\"source_precedence\":\"optimizer,loose,stock\"");
            System.out.println(",\"scope\":"+q("Exact known source/thread dispatch and final Object.equals hierarchy; "
                +(looseRoot==null?"only stock+optimizer checked, no loose class directory; ":"explicit loose class directory checked; ")
                +"unknown Java agents/reflection are outside this contract")+"}");
        }
    }

    private static final class Sources{
        final ZipFile stock,optimizer;final Map<String,Path> loose=new TreeMap<>();int mismatchedRelativeNames;
        Sources(ZipFile stock,ZipFile optimizer,Path looseRoot)throws Exception{
            this.stock=stock;this.optimizer=optimizer;
            if(looseRoot!=null){
                if(!Files.isDirectory(looseRoot))throw new IllegalStateException("Missing loose class root");
                try(var files=Files.walk(looseRoot)){
                    for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).toList()){
                        String name=looseRoot.relativize(file).toString().replace('\\','/');
                        if(!name.equals(new ClassReader(Files.readAllBytes(file)).getClassName()+".class")){
                            mismatchedRelativeNames++;continue;
                        }
                        if(loose.put(name,file)!=null)throw new IllegalStateException("Duplicate loose class name");
                    }
                }
            }
        }
        Set<String> names(){
            var names=new TreeSet<String>();
            stock.stream().filter(e->e.getName().endsWith(".class")).forEach(e->names.add(e.getName()));
            if(optimizer!=null)optimizer.stream().filter(e->e.getName().endsWith(".class")).forEach(e->names.add(e.getName()));
            names.addAll(loose.keySet());return names;
        }
        String origin(String name){
            if(optimizer!=null&&optimizer.getEntry(name)!=null)return "optimizer";
            return loose.containsKey(name)?"loose":"stock";
        }
        byte[] read(String name)throws Exception{
            if(optimizer!=null&&optimizer.getEntry(name)!=null)return VerifyZombieRequestSources.read(optimizer,name);
            if(loose.containsKey(name))return Files.readAllBytes(loose.get(name));
            return VerifyZombieRequestSources.read(stock,name);
        }
        String catalogHash()throws Exception{
            var digest=MessageDigest.getInstance("SHA-256");
            for(var entry:loose.entrySet()){
                digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);
                digest.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(entry.getValue())));
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private static void verifyIdentity(Sources sources)throws Exception{
        var seen=new HashSet<String>();
        for(String name=ZOMBIE;!name.equals("java/lang/Object");){
            if(!seen.add(name))throw new IllegalStateException("Cyclic hierarchy");
            ClassNode current=node(sources.read(name+".class"),ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            if(name.equals(ZOMBIE)&&(current.access&ACC_FINAL)==0)throw new IllegalStateException("Zombie is no longer final");
            if(current.methods.stream().anyMatch(m->m.name.equals("equals")&&m.desc.equals("(Ljava/lang/Object;)Z")))throw new IllegalStateException("Zombie equality changed: "+name);
            name=current.superName;
        }
    }
    private static String jdkShape(ClassNode source,String name,String desc)throws Exception{
        MethodNode method=source.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
        ClassNode normalized=new ClassNode();normalized.version=V1_8;normalized.access=ACC_PUBLIC;normalized.name="roy/JdkLinkedListShape";normalized.superName="java/lang/Object";normalized.methods.add(method);
        ClassWriter writer=new ClassWriter(0);normalized.accept(writer);return hash(writer.toByteArray());
    }
    private static ClassNode node(byte[] bytes,int flags){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,flags);return node;}
    private static byte[] read(ZipFile source,String name)throws Exception{if(source.getEntry(name)==null)throw new IllegalStateException("Missing source "+name);try(var stream=source.getInputStream(source.getEntry(name))){return stream.readAllBytes();}}
    private static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static void check(String current,String expected,String label){if(!current.equals(expected))throw new IllegalStateException("Re-audit changed "+label);}
    private static void printMap(Map<String,String> values){
        System.out.print("{");boolean first=true;for(var entry:values.entrySet()){if(!first)System.out.print(",");first=false;System.out.print(q(entry.getKey())+":"+q(entry.getValue()));}System.out.print("}");
    }
    private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
