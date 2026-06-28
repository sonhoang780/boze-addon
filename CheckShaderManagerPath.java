import java.io.*;
import java.net.*;
import java.util.zip.*;
public class CheckShaderManagerPath {
    public static void main(String[] args) throws Exception {
        File srcJar = new File("C:/Users/conng/.gradle/caches/fabric-loom/1.21.4/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2-sources.jar");
        ZipFile zip = new ZipFile(srcJar);
        ZipEntry entry = zip.getEntry("net/minecraft/client/renderer/ShaderManager.java");
        BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("post_effect")) {
                System.out.println(line);
            }
            if (line.contains("post_chain")) {
                System.out.println(line);
            }
        }
        zip.close();
    }
}
