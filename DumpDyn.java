import java.io.*;
public class DumpDyn {
    public static void main(String[] args) throws Exception {
        Process p = Runtime.getRuntime().exec("javap -c -p temp_dyn/net/minecraft/client/renderer/texture/DynamicTexture.class");
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.contains("upload")) System.out.println(line);
        }
    }
}
