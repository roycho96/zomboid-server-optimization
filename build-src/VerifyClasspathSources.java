import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;
public final class VerifyClasspathSources {
    private static final Set<String> SERVER_PATCH=Set.of(
        "zombie/iso/IsoCell.class",
        "zombie/network/PacketsCache.class",
        "zombie/popman/NetworkZombiePacker.class");
    private VerifyClasspathSources() {}
    private static final class Audit {
        int loose,optimizer,server,equal;
        boolean rootPresent,treePresent;
        final List<String> changed=new ArrayList<>(),unknown=new ArrayList<>();
        void inspect(Path file,String name,ZipFile stock,ZipFile opt)throws IOException {
            loose++;
            // New source targets must not inherit the older patch exemptions.
            if(SERVER_PATCH.contains(name)){server++;return;}
            if(opt!=null&&opt.getEntry(name)!=null){optimizer++;return;}
            ZipEntry entry=stock.getEntry(name);
            if(entry==null||entry.isDirectory()){unknown.add(name);return;}
            byte[] original;
            try(var input=stock.getInputStream(entry)){original=input.readAllBytes();}
            if(Arrays.equals(original,Files.readAllBytes(file)))equal++;
            else changed.add(name);
        }
        String json() {
            int rejected=changed.size()+unknown.size();
            return "{\"verified\":"+(rejected==0)+
                ",\"loose_class_count\":"+loose+
                ",\"shadowed_by_optimizer_count\":"+optimizer+
                ",\"shadowed_by_server_patch_count\":"+server+
                ",\"unshadowed_stock_equal_count\":"+equal+
                ",\"unshadowed_changed_count\":"+changed.size()+
                ",\"unshadowed_unknown_count\":"+unknown.size()+
                ",\"rejected_count\":"+rejected+
                ",\"loose_root_present\":"+rootPresent+
                ",\"zombie_tree_present\":"+treePresent+
                ",\"server_patch_shadowed_names\":"+names(new TreeSet<>(SERVER_PATCH))+
                ",\"changed_names\":"+names(changed)+",\"unknown_names\":"+names(unknown)+
                ",\"scope\":\"Own server patch (three named classes), optimizer, loose zombie tree, stock. Unshadowed loose classes must be byte-identical to stock; new purity targets are not exempted as own patches.\"}";
        }
    }
    private static BasicFileAttributes attributes(Path path)throws IOException {
        try{return Files.readAttributes(path,BasicFileAttributes.class);}
        catch(NoSuchFileException missing){return null;}
    }
    private static void scan(Path root,ZipFile stock,ZipFile opt,Audit audit)throws IOException {
        BasicFileAttributes rootAttributes=attributes(root);
        if(rootAttributes==null)return;
        audit.rootPresent=true;
        if(!rootAttributes.isDirectory())throw new IOException("Loose root is not a directory");
        Path tree=root.resolve("zombie");
        BasicFileAttributes treeAttributes=attributes(tree);
        if(treeAttributes==null)return;
        audit.treePresent=true;
        if(!treeAttributes.isDirectory())throw new IOException("Loose zombie tree is not a directory");
        Files.walkFileTree(tree,EnumSet.of(FileVisitOption.FOLLOW_LINKS),Integer.MAX_VALUE,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")){
                    if(!attrs.isRegularFile())throw new IOException("Non-regular loose class: "+file);
                    String name=root.relativize(file).toString().replace(File.separatorChar,'/');
                    audit.inspect(file,name,stock,opt);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static String quote(String value) {
        StringBuilder result=new StringBuilder("\"");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\"'||c=='\\')result.append('\\').append(c);
            else if(c<0x20)result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
    private static String names(Collection<String> names) {
        List<String> sorted=new ArrayList<>(names);Collections.sort(sorted);
        StringJoiner result=new StringJoiner(",","[","]");
        for(String name:sorted)result.add(quote(name));
        return result.toString();
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Expected stockJar optimizerJarOrDash looseRoot");
        Audit audit=new Audit();
        try(ZipFile stock=new ZipFile(args[0]);ZipFile opt=args[1].equals("-")?null:new ZipFile(args[1])){
            scan(Path.of(args[2]).toAbsolutePath().normalize(),stock,opt,audit);
        }
        System.out.println(audit.json());
        if(!audit.changed.isEmpty()||!audit.unknown.isEmpty())System.exit(1);
    }
}
