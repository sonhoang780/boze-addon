import java.io.*;
import java.net.*;
import java.util.zip.*;
public class CheckVanillaPostChain {
    public static void main(String[] args) throws Exception {
        File jar = new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar");
        ZipFile zip = new ZipFile(jar);
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith("assets/minecraft/post_")) {
                System.out.println(entry.getName());
                break;
            }
        }
        zip.close();
    }
}
