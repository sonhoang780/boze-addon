import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class FindPostPassFields {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{
            new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar").toURI().toURL(),
            new File("C:/Users/conng/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8/5ef23bfb354ccba24cc54d7ddf7b0a33990ee3dd/joml-1.10.8.jar").toURI().toURL()
        };
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> pcClass = cl.loadClass("net.minecraft.client.renderer.PostPass");
        for (Field f : pcClass.getDeclaredFields()) {
            System.out.println("Field: " + f.getName() + " type: " + f.getType().getName());
        }
        for (Method m : pcClass.getDeclaredMethods()) {
            System.out.println("Method: " + m.getName() + " params: " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}
