import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class DumpTargets {
    public static void main(String[] args) throws Exception {
        URL[] urls = new URL[]{new File("temp_targets").toURI().toURL()};
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> clazz = cl.loadClass("net.minecraft.client.renderer.LevelTargetBundle");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.println(m.toString());
        }
        System.out.println("Interfaces:");
        for (Class<?> iface : clazz.getInterfaces()) {
            System.out.println(iface.toString());
        }
    }
}
