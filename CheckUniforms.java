import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

public class CheckUniforms {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{
            new File("C:/Users/conng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2/minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar").toURI().toURL()
        };
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> pcClass = cl.loadClass("net.minecraft.client.renderer.PostPass");
        Field f = pcClass.getDeclaredField("uniforms");
        System.out.println("Generic type: " + f.getGenericType());
    }
}
