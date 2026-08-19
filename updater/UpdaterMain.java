/*
 * Thunder self-updater, run by Thunder.bat / thunder.sh before launching the
 * client. Checks the latest GitHub release, and if the local VERSION file
 * disagrees, downloads the release zip, verifies its SHA-256 digest, and
 * applies it with backup/rollback. Modeled on Nightdawg's Hurricane-Updater
 * (https://github.com/Nightdawg/Hurricane-Updater), adapted from its
 * raw-manifest scheme to GitHub release assets.
 *
 * Design constraints:
 *  - Fail open: any failure leaves the install untouched (or restored) and
 *    exits 0 so the launcher still starts the game.
 *  - The running JVM locks updater.jar, and cmd.exe re-reads a running .bat
 *    by byte offset, so updater.jar / Thunder.bat / thunder.sh are never
 *    replaced in place; changed versions are staged as "<name>.new" and the
 *    launcher swaps them in on the next start.
 *  - The bundled Windows jre/ is never touched (it is running this program);
 *    JRE upgrades require a manual re-download, which the updater points out.
 */
package updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class UpdaterMain {
    private static final String OWNER = "onefuncman";
    private static final String REPO = "thunder";
    private static final String ASSET = "Thunder-cross-platform.zip";
    private static final String ZIP_ROOT = "Thunder/";
    private static final String VERSION_FILE = "VERSION";
    private static final int BACKUPS_TO_KEEP = 2;
    /* Never extracted: the Windows jre runs this program, and README.txt is
     * platform-specific (the cross-platform zip carries the wrong one). */
    private static final Set<String> SKIP_PREFIXES = Set.of("jre/");
    private static final Set<String> SKIP_FILES = Set.of("README.txt");
    /* In use while the updater runs; staged as "<name>.new" instead. */
    private static final Set<String> STAGE_AS_NEW = Set.of("updater.jar", "Thunder.bat", "thunder.sh");

    private final Path installDir;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public static void main(String[] args) {
        if("1".equals(System.getenv("THUNDER_NO_UPDATE"))) {
            System.out.println("[updater] THUNDER_NO_UPDATE=1, skipping update check.");
            return;
        }
        Path dir = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        try {
            new UpdaterMain(dir).run();
        } catch(Exception e) {
            System.out.println("[updater] Update check failed, launching current version: " + rootMessage(e));
        }
    }

    private UpdaterMain(Path installDir) {
        this.installDir = installDir;
    }

    private void run() throws Exception {
        String local = localVersion();
        Map<?, ?> release = fetchLatestRelease();
        String tag = (String)release.get("tag_name");
        if(tag == null || tag.isBlank())
            throw new IOException("latest release has no tag_name");
        if(tag.equals(local)) {
            System.out.println("[updater] Up to date (" + tag + ").");
            return;
        }
        Map<?, ?> asset = findAsset(release);
        System.out.println("[updater] Updating " + (local == null ? "(unknown version)" : local) + " -> " + tag);

        Path tmp = installDir.resolve(".updater-tmp");
        deleteRecursively(tmp);
        Files.createDirectories(tmp);
        try {
            Path zip = tmp.resolve(ASSET);
            download(asset, zip);
            Path stage = tmp.resolve("stage");
            List<String> changed = extractChanged(zip, stage);
            apply(changed, stage, local, tag);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private String localVersion() throws IOException {
        Path f = installDir.resolve(VERSION_FILE);
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
    }

    private Map<?, ?> fetchLatestRelease() throws IOException, InterruptedException {
        URI uri = URI.create("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "thunder-updater")
            .timeout(Duration.ofSeconds(15))
            .GET().build();
        HttpResponse<String> rsp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(rsp.statusCode() != 200)
            throw new IOException("release check got HTTP " + rsp.statusCode());
        Object root = new Json(rsp.body()).parse();
        if(!(root instanceof Map<?, ?> map))
            throw new IOException("unexpected release JSON");
        return map;
    }

    private Map<?, ?> findAsset(Map<?, ?> release) throws IOException {
        if(release.get("assets") instanceof List<?> assets) {
            for(Object o : assets) {
                if(o instanceof Map<?, ?> a && ASSET.equals(a.get("name")))
                    return a;
            }
        }
        throw new IOException("latest release has no " + ASSET + " asset");
    }

    private void download(Map<?, ?> asset, Path zip) throws Exception {
        String url = (String)asset.get("browser_download_url");
        long size = asset.get("size") instanceof Number n ? n.longValue() : -1;
        String digest = asset.get("digest") instanceof String d && d.startsWith("sha256:")
            ? d.substring("sha256:".length()).toLowerCase(Locale.ROOT) : null;
        System.out.println("[updater] Downloading " + ASSET + (size > 0 ? " (" + formatBytes(size) + ")..." : "..."));

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "thunder-updater")
            .GET().build();
        HttpResponse<InputStream> rsp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if(rsp.statusCode() != 200)
            throw new IOException("download got HTTP " + rsp.statusCode());
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long done = 0;
        int lastPct = -1;
        try(InputStream in = rsp.body(); var out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                int pct = size > 0 ? (int)(done * 100 / size) : -1;
                if(pct >= 0 && pct / 10 > lastPct / 10 && pct < 100) {
                    lastPct = pct;
                    System.out.println("[updater]   " + pct + "% (" + formatBytes(done) + " / " + formatBytes(size) + ")");
                }
            }
        }
        if(size > 0 && done != size)
            throw new IOException("download size mismatch: expected " + size + ", got " + done);
        if(digest != null) {
            String got = hex(sha.digest());
            if(!got.equals(digest))
                throw new IOException("download SHA-256 mismatch");
            System.out.println("[updater] Download verified (SHA-256 OK).");
        } else {
            System.out.println("[updater] Downloaded (no digest published, skipping hash check).");
        }
    }

    /* Extract entries that differ from the installed files into the stage
     * dir, mirroring install-relative paths. Returns their relative paths. */
    private List<String> extractChanged(Path zip, Path stage) throws IOException {
        List<String> changed = new ArrayList<>();
        try(ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if(entry.isDirectory())
                    continue;
                String name = entry.getName();
                if(!name.startsWith(ZIP_ROOT))
                    continue;
                String rel = name.substring(ZIP_ROOT.length());
                validateRelativePath(rel);
                if(SKIP_FILES.contains(rel) || SKIP_PREFIXES.stream().anyMatch(rel::startsWith))
                    continue;
                Path staged = stage.resolve(rel);
                Files.createDirectories(staged.getParent());
                try(InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
                }
                Path target = installDir.resolve(rel);
                if(Files.isRegularFile(target) && Files.mismatch(staged, target) == -1) {
                    Files.delete(staged);
                    continue;
                }
                changed.add(rel);
            }
        }
        return changed;
    }

    private void apply(List<String> changed, Path stage, String fromVer, String toVer) throws IOException {
        Path backupDir = installDir.resolve(".updater-backup")
            .resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                     + "-" + (fromVer == null ? "unknown" : fromVer));
        List<Path> installed = new ArrayList<>();
        List<Path> backedUp = new ArrayList<>();
        boolean stagedLauncher = false;
        try {
            for(String rel : changed) {
                Path staged = stage.resolve(rel);
                Path target = installDir.resolve(rel);
                if(STAGE_AS_NEW.contains(rel)) {
                    Path pending = installDir.resolve(rel + ".new");
                    Files.move(staged, pending, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[updater] Staged " + rel + " (applied on next launch).");
                    stagedLauncher = true;
                    continue;
                }
                if(Files.exists(target)) {
                    Path backup = backupDir.resolve(rel);
                    Files.createDirectories(backup.getParent());
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    backedUp.add(target);
                }
                Files.createDirectories(target.getParent());
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                installed.add(target);
                System.out.println("[updater] Installed " + rel);
            }
            Files.writeString(installDir.resolve(VERSION_FILE), toVer + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            for(Path target : installed) {
                if(!backedUp.contains(target))
                    try { Files.deleteIfExists(target); } catch(IOException ignored) {}
            }
            for(Path target : backedUp) {
                try {
                    Files.move(backupDir.resolve(installDir.relativize(target).toString()),
                               target, StandardCopyOption.REPLACE_EXISTING);
                } catch(IOException restoreFailed) {
                    System.out.println("[updater] WARNING: could not restore " + target + " from " + backupDir);
                }
            }
            throw new IOException("update failed while applying files; previous version restored", e);
        }
        cleanupBackups();
        System.out.println("[updater] Updated to " + toVer + " (" + changed.size() + " file(s))."
                           + (stagedLauncher ? " Launcher/updater changes apply on next start." : ""));
    }

    private void cleanupBackups() throws IOException {
        Path root = installDir.resolve(".updater-backup");
        if(!Files.isDirectory(root))
            return;
        List<Path> dirs = new ArrayList<>();
        try(var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for(int i = BACKUPS_TO_KEEP; i < dirs.size(); i++)
            deleteRecursively(dirs.get(i));
    }

    private static void validateRelativePath(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if(normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*"))
            throw new IOException("zip entry path must be relative: " + path);
        for(String part : normalized.split("/")) {
            if(part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("zip entry path contains an unsafe segment: " + path);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if(!Files.exists(root))
            return;
        List<Path> paths = new ArrayList<>();
        try(var stream = Files.walk(root)) {
            stream.forEach(paths::add);
        }
        for(int i = paths.size() - 1; i >= 0; i--)
            Files.deleteIfExists(paths.get(i));
    }

    private static String hex(byte[] bytes) {
        StringBuilder buf = new StringBuilder(bytes.length * 2);
        for(byte b : bytes)
            buf.append(String.format("%02x", b));
        return buf.toString();
    }

    private static String formatBytes(long bytes) {
        if(bytes < 1024)
            return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while(value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String rootMessage(Throwable t) {
        while(t.getCause() != null)
            t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    /* Minimal JSON parser (objects, arrays, strings, numbers, literals) so
     * the updater has no dependencies. Same approach as Hurricane-Updater. */
    private static final class Json {
        private final String in;
        private int at;

        private Json(String in) {
            this.in = in;
        }

        private Object parse() {
            Object v = value();
            ws();
            if(at != in.length())
                throw err("trailing content");
            return v;
        }

        private Object value() {
            ws();
            if(at >= in.length())
                throw err("unexpected end");
            char c = in.charAt(at);
            if(c == '{') return object();
            if(c == '[') return array();
            if(c == '"') return string();
            if(c == '-' || Character.isDigit(c)) return number();
            if(lit("true")) return Boolean.TRUE;
            if(lit("false")) return Boolean.FALSE;
            if(lit("null")) return null;
            throw err("unexpected value");
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if(peek('}')) { at++; return m; }
            while(true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if(peek('}')) { at++; return m; }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if(peek(']')) { at++; return l; }
            while(true) {
                l.add(value());
                ws();
                if(peek(']')) { at++; return l; }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while(at < in.length()) {
                char c = in.charAt(at++);
                if(c == '"')
                    return b.toString();
                if(c == '\\') {
                    if(at >= in.length())
                        throw err("bad escape");
                    char e = in.charAt(at++);
                    switch(e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if(at + 4 > in.length())
                                throw err("bad unicode escape");
                            b.append((char)Integer.parseInt(in.substring(at, at + 4), 16));
                            at += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else {
                    b.append(c);
                }
            }
            throw err("unterminated string");
        }

        private Number number() {
            int start = at;
            if(peek('-')) at++;
            while(at < in.length() && Character.isDigit(in.charAt(at))) at++;
            boolean dec = false;
            if(at < in.length() && in.charAt(at) == '.') {
                dec = true;
                at++;
                while(at < in.length() && Character.isDigit(in.charAt(at))) at++;
            }
            if(at < in.length() && (in.charAt(at) == 'e' || in.charAt(at) == 'E')) {
                dec = true;
                at++;
                if(at < in.length() && (in.charAt(at) == '+' || in.charAt(at) == '-')) at++;
                while(at < in.length() && Character.isDigit(in.charAt(at))) at++;
            }
            String s = in.substring(start, at);
            return dec ? Double.parseDouble(s) : Long.parseLong(s);
        }

        private boolean lit(String t) {
            if(in.startsWith(t, at)) {
                at += t.length();
                return true;
            }
            return false;
        }

        private void ws() {
            while(at < in.length() && Character.isWhitespace(in.charAt(at)))
                at++;
        }

        private boolean peek(char c) {
            return at < in.length() && in.charAt(at) == c;
        }

        private void expect(char c) {
            if(!peek(c))
                throw err("expected '" + c + "'");
            at++;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON: " + msg + " at offset " + at);
        }
    }
}
