import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class FindRenderTargetBundle {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar").toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> clazz = cl.loadClass("net.minecraft.client.renderer.RenderTargetProvider");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println("Method: " + m.getName() + " params: " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}
