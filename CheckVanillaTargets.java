import java.io.*;
import java.net.*;
import java.util.zip.*;
public class CheckVanillaTargets {
    public static void main(String[] args) throws Exception {
        File jar = new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar");
        ZipFile zip = new ZipFile(jar);
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith("assets/minecraft/post_effect/") && entry.getName().endsWith(".json")) {
                BufferedReader br = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                if (sb.toString().contains("width")) {
                    System.out.println("FOUND width in: " + entry.getName());
                }
            }
        }
        zip.close();
    }
}
