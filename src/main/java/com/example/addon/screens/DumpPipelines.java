package com.example.addon.screens;
import net.minecraft.client.renderer.RenderPipelines;
import java.lang.reflect.Field;
public class DumpPipelines {
    public static void main(String[] args) {
        for (Field f : RenderPipelines.class.getDeclaredFields()) {
            System.out.println("PIPELINE: " + f.getName());
        }
    }
}
