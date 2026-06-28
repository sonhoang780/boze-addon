import java.io.*;
import java.net.*;
import java.lang.reflect.*;

public class PrintPipelines {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("net.minecraft.client.renderer.RenderPipelines");
        for (Field f : clazz.getDeclaredFields()) {
            System.out.println("Field: " + f.getName());
        }
    }
}
