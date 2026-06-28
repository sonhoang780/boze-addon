import net.minecraft.client.renderer.LevelTargetBundle;
import java.lang.reflect.Field;
public class PrintTargets {
    public static void main(String[] args) throws Exception {
        Field f = LevelTargetBundle.class.getDeclaredField("MAIN_TARGETS");
        f.setAccessible(true);
        System.out.println(f.get(null));
    }
}
