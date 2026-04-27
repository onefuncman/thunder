package haven;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// Re-spawns hafen.jar as a detached subprocess so Steam's overlay hook stays on this proxy, not the render process.
public class SteamProxyLauncher {
    public static void main(String[] args) {
        try {
            String jar = new File(SteamProxyLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getPath();
            Path baseDir = Paths.get(jar).getParent();

            List<String> cmd = new ArrayList<>();
            cmd.add(getJavaExecutable());
            cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
            cmd.add("-jar");
            cmd.add(jar);
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(baseDir.toFile());
            File log = baseDir.resolve("thunder-log.txt").toFile();
            pb.redirectError(log);
            pb.redirectOutput(log);
            pb.start();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    private static String getJavaExecutable() {
        String exe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return System.getProperty("os.name").toLowerCase().contains("win") ? exe + ".exe" : exe;
    }
}
