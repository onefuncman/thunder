package haven.nav;

import java.io.InputStream;
import java.util.Properties;

public final class NavigationCore {
   public static final String TITLE = "HavenNavigationCore";
   private static final String HASH;
   private static final String VERSION;

   static {
      String hash = "unknown";
      String version = "unknown";
      InputStream in = NavigationCore.class.getResourceAsStream("buildinfo.properties");
      if (in != null) {
         try {
            Properties p = new Properties();
            p.load(in);
            hash = p.getProperty("git.hash", "unknown");
            version = p.getProperty("version", "unknown");
            in.close();
         } catch (Exception ignored) {
         }
      }
      HASH = hash;
      VERSION = version;
   }

   private NavigationCore() {
   }

   public static String gitHash() {
      return HASH;
   }

   public static String version() {
      return VERSION;
   }
}
