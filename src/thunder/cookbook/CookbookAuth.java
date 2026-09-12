package thunder.cookbook;

import haven.Config;
import haven.Defer;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session handling for civ.hearthworld.com (the "Haven & Hearth Automap"
 * site). Standard ASP.NET Core cookie-auth form: GET /Auth for an
 * antiforgery cookie + hidden __RequestVerificationToken, then POST
 * username/password/persistent/token to /Auth/Login. On success the server
 * sets a `.AspNetCore.Cookies` cookie (14-day expiry when persistent=true)
 * which we keep and replay. See docs/cookbook-integration.md.
 */
public class CookbookAuth {
    public static final String BASE = "https://civ.hearthworld.com";
    private static final String SESSION_FILE = "cookbook-session.json";
    private static final Pattern TOKEN_RX =
        Pattern.compile("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]*)\"");

    private static volatile String cookieName;
    private static volatile String cookieValue;
    private static volatile String username;

    static {load();}

    public static boolean isLoggedIn() {return cookieValue != null;}

    public static String username() {return username;}

    /** "name=value" for the Cookie header, or null if not logged in. */
    public static synchronized String cookieHeader() {
        return (cookieValue != null) ? (cookieName + "=" + cookieValue) : null;
    }

    public interface LoginCallback {
        void done(boolean success, String error);
    }

    public static void loginAsync(String user, String pass, boolean keepLoggedIn, LoginCallback cb) {
        Defer.later(() -> {
            String error;
            try {
                doLogin(user, pass, keepLoggedIn);
                error = null;
            } catch(Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : e.toString();
            }
            cb.done(error == null, error);
        }, null);
    }

    public static synchronized void logout() {
        cookieName = null;
        cookieValue = null;
        username = null;
        Config.saveFile(SESSION_FILE, "{}");
    }

    private static void doLogin(String user, String pass, boolean keepLoggedIn) throws IOException {
        HttpURLConnection get = (HttpURLConnection) new URL(BASE + "/Auth").openConnection();
        get.setRequestProperty("User-Agent", "Thunder Client");
        String antiforgery;
        String html;
        try {
            antiforgery = firstCookie(get.getHeaderFields());
            try(BufferedReader r = new BufferedReader(new InputStreamReader(get.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while((line = r.readLine()) != null) {sb.append(line).append('\n');}
                html = sb.toString();
            }
        } finally {
            get.disconnect();
        }
        if(antiforgery == null) {throw new IOException("Could not start a login session (no antiforgery cookie).");}
        Matcher m = TOKEN_RX.matcher(html);
        if(!m.find()) {throw new IOException("Could not find login form token.");}
        String token = m.group(1);

        StringBuilder body = new StringBuilder();
        appendField(body, "username", user);
        appendField(body, "password", pass);
        appendField(body, "persistent", keepLoggedIn ? "true" : "false");
        appendField(body, "__RequestVerificationToken", token);

        HttpURLConnection post = (HttpURLConnection) new URL(BASE + "/Auth/Login").openConnection();
        post.setInstanceFollowRedirects(false);
        post.setRequestMethod("POST");
        post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        post.setRequestProperty("Cookie", antiforgery);
        post.setRequestProperty("User-Agent", "Thunder Client");
        post.setDoOutput(true);
        try {
            try(OutputStream out = post.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = post.getResponseCode();
            if(code != 302) {
                throw new IOException("Invalid username or password.");
            }
            String authCookie = firstCookie(post.getHeaderFields(), ".AspNetCore.Cookies");
            if(authCookie == null) {throw new IOException("Login did not return a session cookie.");}
            String[] parsed = splitCookie(authCookie);
            synchronized(CookbookAuth.class) {
                cookieName = parsed[0];
                cookieValue = parsed[1];
                username = user;
            }
            save();
        } finally {
            post.disconnect();
        }
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        if(sb.length() > 0) {sb.append('&');}
        try {
            sb.append(name).append('=').append(URLEncoder.encode(value, "UTF-8"));
        } catch(java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e); // UTF-8 is always available
        }
    }

    private static String firstCookie(Map<String, List<String>> headers) {
        return firstCookie(headers, null);
    }

    /** First Set-Cookie header value (as "name=value"), optionally filtered by cookie name prefix. */
    private static String firstCookie(Map<String, List<String>> headers, String namePrefix) {
        for(Map.Entry<String, List<String>> e : headers.entrySet()) {
            if(e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) {continue;}
            for(String v : e.getValue()) {
                if(namePrefix == null || v.startsWith(namePrefix + "=")) {
                    int semi = v.indexOf(';');
                    return (semi < 0) ? v : v.substring(0, semi);
                }
            }
        }
        return null;
    }

    private static String[] splitCookie(String nameEqValue) {
        int eq = nameEqValue.indexOf('=');
        return new String[]{nameEqValue.substring(0, eq), nameEqValue.substring(eq + 1)};
    }

    private static synchronized void save() {
        JSONObject o = new JSONObject();
        o.put("cookieName", cookieName);
        o.put("cookieValue", cookieValue);
        o.put("username", username);
        Config.saveFile(SESSION_FILE, o.toString());
    }

    private static synchronized void load() {
        try {
            String data = Config.loadFile(SESSION_FILE);
            if(data == null || data.isEmpty()) {return;}
            JSONObject o = new JSONObject(data);
            cookieName = o.optString("cookieName", null);
            cookieValue = o.optString("cookieValue", null);
            username = o.optString("username", null);
        } catch(Exception ignored) {}
    }
}
