import java.io.*;
import java.util.zip.*;

public class ViewSource {
    public static void main(String[] args) throws Exception {
        String jarPath = "C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2-sources.jar";
        ZipFile zip = new ZipFile(jarPath);
        ZipEntry entry = zip.getEntry("com/mojang/blaze3d/platform/NativeImage.java");
        BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("public void upload")) {
                System.out.println(line);
                System.out.println(reader.readLine());
                System.out.println(reader.readLine());
            }
        }
    }
}
