import java.io.*;
import java.lang.reflect.*;
import java.net.*;
public class FindNativeImageUpload {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{
            new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar").toURI().toURL()
        };
        URLClassLoader cl = new URLClassLoader(urls);
        // Let's dump methods by inspecting the classfile bytes!
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar");
        java.util.zip.ZipEntry entry = zip.getEntry("com/mojang/blaze3d/platform/NativeImage.class");
        InputStream in = zip.getInputStream(entry);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) != -1) { out.write(buffer, 0, len); }
        byte[] bytes = out.toByteArray();
        String str = new String(bytes, "ISO-8859-1");
        int idx = 0;
        while ((idx = str.indexOf("upload", idx + 1)) != -1) {
            int start = Math.max(0, idx - 50);
            int end = Math.min(str.length(), idx + 50);
            System.out.println(str.substring(start, end).replaceAll("\\p{C}", "?"));
        }
    }
}
