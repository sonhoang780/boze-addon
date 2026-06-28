import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

public class ReadVanilla {
    public static void main(String[] args) throws Exception {
        try (ZipFile zf = new ZipFile("C:\Users\conng\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2\minecraft-merged-1.21.4-loom.mappings.1_21_4.layered+hash.2028763108-v2.jar")) {
            ZipEntry e = zf.getEntry("assets/minecraft/post_effect/creeper.json");
            if (e != null) {
                try (InputStream is = zf.getInputStream(e)) {
                    System.out.println(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            } else {
                System.out.println("Not found");
            }
        }
    }
}
