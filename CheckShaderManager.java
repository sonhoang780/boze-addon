import java.io.*;
import java.net.*;
import java.util.zip.*;
public class CheckShaderManager {
    public static void main(String[] args) throws Exception {
        File jar = new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar");
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()});
        Class<?> uClass = cl.loadClass("net.minecraft.client.renderer.ShaderManager");
        for (java.lang.reflect.Field f : uClass.getDeclaredFields()) {
            System.out.println("Field: " + f.getName());
        }
    }
}
