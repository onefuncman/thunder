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

            String javaExe = getJavaExecutable();
            registerGpuPreference(javaExe);

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
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

    // Register the spawned JVM for the high-performance GPU (the per-app
    // profile from Settings > System > Display > Graphics), keyed on the
    // exact java.exe that runs the client. Written only when no entry exists
    // yet, so a choice made in the Settings UI is never overridden. Matters
    // on hybrid-GPU laptops, which otherwise put the client on the iGPU.
    // Mirrors the same registration in etc/release/Thunder.bat.
    private static void registerGpuPreference(String javaExe) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
            return;
        try {
            String key = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
            int found = new ProcessBuilder("reg", "query", key, "/v", javaExe)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start().waitFor();
            if (found != 0)
                new ProcessBuilder("reg", "add", key, "/v", javaExe, "/t", "REG_SZ", "/d", "GpuPreference=2;")
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start().waitFor();
        } catch (Exception e) {
            // Best effort only: a registry hiccup must never block the launch.
        }
    }

    private static String getJavaExecutable() {
        String exe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return System.getProperty("os.name").toLowerCase().contains("win") ? exe + ".exe" : exe;
    }
}
