import java.io.*;
import java.net.*;
import java.lang.reflect.*;

public class CheckRenderSystem {
    public static void main(String[] args) throws Exception {
        Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
        for (Method m : rs.getDeclaredMethods()) {
            if (m.getName().contains("recordRenderCall")) {
                System.out.println("FOUND: " + m);
            }
        }
    }
}
