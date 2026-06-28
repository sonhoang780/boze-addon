import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class DumpMethods {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{new File("temp_classes").toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> clazz = cl.loadClass("net.minecraft.client.renderer.PostChain");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println(m.toString());
        }
    }
}
