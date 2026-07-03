package com.example.test;
public class Test {
    public static void main(String[] args) throws Exception {
        for(java.lang.reflect.Method m : net.minecraft.client.renderer.texture.DynamicTexture.class.getMethods()) {
            System.out.println(m.getName() + " -> " + m.getReturnType().getSimpleName());
        }
    }
}
