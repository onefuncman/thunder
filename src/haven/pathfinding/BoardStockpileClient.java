package haven.pathfinding;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import org.json.JSONObject;

/** Small bounded, credential-free Unix socket client for the worker panel. */
public final class BoardStockpileClient {
    public static final long TIMEOUT_MS = 1800L;
    public static final int MAX_RESPONSE_BYTES = 4 << 20;
    private final Path socket;
    public BoardStockpileClient(Path socket) { this.socket = socket; }

    public JSONObject plan(BoardStockpileMath.Area area, int piles, int boards, String resource) throws IOException {
        return call(String.format(Locale.ROOT,
            "{\"op\":\"plan_stockpile\",\"x\":%.3f,\"y\":%.3f,\"width\":%.3f,\"height\":%.3f,\"requested_piles\":%d,\"boards_per_pile\":%d,\"board_resource\":\"%s\"}",
            area.x, area.y, area.width, area.height, piles, boards, JSONObject.quote(resource).substring(1, JSONObject.quote(resource).length()-1)));
    }

    public JSONObject execute(BoardStockpileMath.Area area, int piles, int boards, String resource) throws IOException {
        return call(String.format(Locale.ROOT,
            "{\"op\":\"execute_stockpile\",\"x\":%.3f,\"y\":%.3f,\"width\":%.3f,\"height\":%.3f,\"requested_piles\":%d,\"boards_per_pile\":%d,\"board_resource\":\"%s\",\"max_runtime_ms\":30000,\"confirm_live_stockpile\":true}",
            area.x, area.y, area.width, area.height, piles, boards, JSONObject.quote(resource).substring(1, JSONObject.quote(resource).length()-1)));
    }

    public JSONObject status(String jobId) throws IOException {
        return call("{\"op\":\"job_status\",\"job_id\":" + JSONObject.quote(jobId) + "}");
    }
    public JSONObject cancel(String jobId) throws IOException {
        return call("{\"op\":\"cancel\",\"job_id\":" + JSONObject.quote(jobId) + "}");
    }

    /** Stable request serializer used by focused nonmutating tests. */
    public static String planRequest(BoardStockpileMath.Area a, int piles, int boards, String resource) {
        return String.format(Locale.ROOT, "{\"op\":\"plan_stockpile\",\"x\":%.3f,\"y\":%.3f,\"width\":%.3f,\"height\":%.3f,\"requested_piles\":%d,\"boards_per_pile\":%d,\"board_resource\":%s}",
            a.x,a.y,a.width,a.height,piles,boards,JSONObject.quote(resource));
    }

    private JSONObject call(String request) throws IOException {
        if (socket == null) throw new IOException("worker disconnected");
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try (SocketChannel ch = SocketChannel.open()) {
            ch.configureBlocking(false);
            ch.connect(unixAddress(socket));
            while (!ch.finishConnect()) waitFor(deadline);
            ByteBuffer out = StandardCharsets.UTF_8.encode(request + "\n");
            while (out.hasRemaining()) { if (ch.write(out) == 0) waitFor(deadline); }
            ByteBuffer one = ByteBuffer.allocate(1024);
            StringBuilder text = new StringBuilder();
            while (true) {
                int n = ch.read(one);
                if (n < 0) break;
                if (n == 0) { waitFor(deadline); continue; }
                one.flip();
                while (one.hasRemaining()) {
                    char c = (char)(one.get() & 0xff);
                    if (c == '\n') return parse(text.toString());
                    text.append(c);
                    if (text.length() > MAX_RESPONSE_BYTES) throw new IOException("worker response too large");
                }
                one.clear();
            }
            throw new IOException("worker disconnected");
        } catch (IOException e) {
            if ("worker disconnected".equals(e.getMessage())) throw e;
            throw new IOException("worker disconnected", e);
        }
    }
    private static JSONObject parse(String line) throws IOException {
        try {
            JSONObject result = new JSONObject(line);
            if (!result.optBoolean("ok", false)) {
                JSONObject error = result.optJSONObject("error");
                throw new IOException(error == null ? "worker rejected request" : error.optString("message", "worker rejected request"));
            }
            return result.optJSONObject("result") == null ? result : result.optJSONObject("result");
        } catch (IOException e) { throw e; }
          catch (RuntimeException e) { throw new IOException("worker returned invalid response", e); }
    }
    /** Uses reflection so the Java-8 Thunder bootclasspath can compile while
     * running on the Java runtime's Unix-domain socket implementation. */
    private static SocketAddress unixAddress(Path path) throws IOException {
        try {
            Class<?> type = Class.forName("java.net.UnixDomainSocketAddress");
            return (SocketAddress)type.getMethod("of", Path.class).invoke(null, path);
        } catch (Exception e) { throw new IOException("worker disconnected", e); }
    }
    private static void waitFor(long deadline) throws IOException {
        if (System.currentTimeMillis() >= deadline) throw new IOException("worker disconnected");
        try { Thread.sleep(8L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("worker disconnected", e); }
    }
}
