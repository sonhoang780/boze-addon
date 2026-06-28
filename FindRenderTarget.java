import java.io.*;
import java.lang.reflect.*;
import java.net.*;
public class FindRenderTarget {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar").toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> uClass = cl.loadClass("com.mojang.blaze3d.pipeline.RenderTarget");
        for (Field f : uClass.getDeclaredFields()) {
            System.out.println("Field: " + f.getName() + " type: " + f.getType().getName());
        }
        for (Method m : uClass.getDeclaredMethods()) {
            System.out.println("Method: " + m.getName() + " params: " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}
