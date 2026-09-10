package haven.dev;

import auto.Bot;
import haven.Bootstrap;
import haven.Charlist;
import haven.Config;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Glob;
import haven.Gob;
import haven.HackThread;
import haven.LoginScreen;
import haven.Moving;
import haven.UI;
import haven.UILoop;
import haven.Widget;
import haven.Window;
import haven.AccountList.Account;
import haven.Config.Variable;
import haven.pathfinding.PfTestRunner;
import haven.pathfinding.PrototypePathfinder;
import haven.pathfinding.PfTestRunner.Run;
import haven.pathfinding.PrototypePathfinder.Scene;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DevControl {
   public static final Variable<Integer> PORT = Variable.propi("haven.dev.control.port", 0);
   public static final Variable<Boolean> AUTOLOGIN = Variable.propb("haven.autologin", false);
   public static final Variable<String> AUTOPLAY = Variable.prop("haven.autoplay", "");
   private static final DevControl inst = new DevControl();
   private volatile UILoop loop;
   private volatile ServerSocket server;
   private static final double NEARBY_RADIUS = 30.0;

   private DevControl() {
   }

   public static void attach(UILoop loop) {
      inst.loop = loop;
      inst.ensureServer();
   }

   public static boolean autologin() {
      return Boolean.TRUE.equals(AUTOLOGIN.get());
   }

   public static String autoplay() {
      String n = (String)AUTOPLAY.get();
      return n == null ? "" : n.trim();
   }

   private void ensureServer() {
      int port = (Integer)PORT.get();
      if (port > 0 && this.server == null) {
         synchronized (this) {
            if (this.server == null) {
               try {
                  this.server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
               } catch (IOException var5) {
                  System.err.println("[dev-control] bind 127.0.0.1:" + port + " failed: " + var5.getMessage());
                  return;
               }

               System.err.println("[dev-control] http://127.0.0.1:" + port + "/");
               Thread th = new HackThread(this::acceptLoop, "dev-control");
               th.setDaemon(true);
               th.start();
            }
         }
      }
   }

   private void acceptLoop() {
      try {
         while (!this.server.isClosed()) {
            try {
               Socket sock = this.server.accept();

               try {
                  this.handle(sock);
               } catch (Exception var16) {
                  Exception e = var16;

                  try {
                     reply(sock, 500, jsonErr(e));
                  } catch (IOException var15) {
                  }
               } finally {
                  try {
                     sock.close();
                  } catch (IOException var14) {
                  }
               }
            } catch (SocketException var18) {
               if (!this.server.isClosed()) {
                  continue;
               }
               break;
            }
         }
      } catch (IOException var19) {
      }
   }

   private void handle(Socket sock) throws Exception {
      sock.setSoTimeout(15000);
      InputStream in = sock.getInputStream();
      BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String req = br.readLine();
      if (req != null && !req.isEmpty()) {
         String[] parts = req.split(" ");
         if (parts.length < 2) {
            reply(sock, 400, jsonErr("bad request"));
         } else {
            String method = parts[0];
            String target = parts[1];
            int qat = target.indexOf(63);
            String path = qat < 0 ? target : target.substring(0, qat);
            Map<String, String> query = parseQuery(qat < 0 ? "" : target.substring(qat + 1));
            int contentLen = 0;

            while (true) {
               String h = br.readLine();
               if (h == null || h.isEmpty()) {
                  h = "";
                  if (contentLen > 0) {
                     char[] buf = new char[contentLen];
                     int n = 0;

                     while (n < contentLen) {
                        int r = br.read(buf, n, contentLen - n);
                        if (r < 0) {
                           break;
                        }

                        n += r;
                     }

                     h = new String(buf, 0, n);
                  }

                  if ("/".equals(path) || "/help".equals(path)) {
                     reply(sock, 200, help());
                     return;
                  } else if ("/status".equals(path) && "GET".equals(method)) {
                     reply(sock, 200, this.status(false).toString());
                     return;
                  } else if ("/state".equals(path) && "GET".equals(method)) {
                     reply(sock, 200, this.state().toString());
                     return;
                  } else if ("/occupancy".equals(path) && "GET".equals(method)) {
                     reply(sock, 200, this.status(true).toString());
                     return;
                  } else if ("/cmd".equals(path)) {
                     String cmd = h.trim();
                     if (cmd.isEmpty()) {
                        cmd = query.get("q");
                     }

                     if (cmd != null && !cmd.trim().isEmpty()) {
                        reply(sock, 200, this.runCmd(cmd.trim()).toString());
                        return;
                     } else {
                        reply(sock, 400, jsonErr("missing command"));
                        return;
                     }
                  } else if ("/login".equals(path)) {
                     reply(sock, 200, this.login(query.get("user")).toString());
                     return;
                  } else if ("/play".equals(path)) {
                     reply(sock, 200, this.play(query.get("char")).toString());
                     return;
                  } else if ("/pf/scenarios".equals(path) && "GET".equals(method)) {
                     reply(sock, 200, pfScenarios().toString());
                     return;
                  } else if ("/pf/run".equals(path)) {
                     String name = query.get("scenario");
                     if (name == null || name.trim().isEmpty()) {
                        name = h.trim();
                     }

                     if (name.isEmpty()) {
                        reply(sock, 400, jsonErr("missing scenario name (POST /pf/run?scenario=observe or body \"observe\")"));
                        return;
                     } else {
                        String verr = PfTestRunner.validateScenario(name);
                        if (verr != null) {
                           reply(sock, 400, jsonErr(verr));
                           return;
                        } else {
                           JSONObject o = PfTestRunner.start(name, this.ui());
                           reply(sock, o.optBoolean("conflict", false) ? 409 : 200, o.toString());
                           return;
                        }
                     }
                  } else if ("/pf/result".equals(path) && "GET".equals(method)) {
                     JSONObject o = PfTestRunner.result(query.get("run_id"));
                     if (o == null) {
                        reply(sock, 404, jsonErr("unknown run_id (no such run, or it was evicted from the recent-results window)"));
                        return;
                     } else {
                        reply(sock, 200, o.toString());
                        return;
                     }
                  } else if ("/pf/cancel".equals(path)) {
                     reply(sock, 200, this.pfCancel().toString());
                     return;
                  } else {
                     reply(sock, 404, jsonErr("not found"));
                     return;
                  }
               }

               int c = h.indexOf(58);
               if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                  try {
                     contentLen = Integer.parseInt(h.substring(c + 1).trim());
                  } catch (NumberFormatException var16) {
                  }
               }
            }
         }
      }
   }

   private JSONObject status(boolean occupancy) {
      JSONObject o = new JSONObject();
      o.put("ok", true);
      o.put("navigation_core_git", haven.nav.NavigationCore.gitHash());
      o.put("navigation_core_title", haven.nav.NavigationCore.TITLE);
      o.put("navigation_core_version", haven.nav.NavigationCore.version());
      UI ui = this.ui();
      if (ui == null) {
         o.put("screen", "none");
         return o;
      } else {
         synchronized (ui) {
            GameUI gui = ui.gui;
            LoginScreen login = find(ui.root, LoginScreen.class);
            Charlist chars = find(ui.root, Charlist.class);
            String screen = "unknown";
            if (gui != null && gui.map != null) {
               screen = "game";
            } else if (chars != null) {
               screen = "chars";
            } else if (login != null) {
               screen = "login";
            }

            o.put("screen", screen);
            if (gui != null) {
               o.put("chrid", gui.chrid == null ? "" : gui.chrid);
            }

            String player = Config.getPlayerName();
            o.put("player", player == null ? "" : player);
            Gob me = gui != null && gui.map != null ? gui.map.player() : null;
            if (me != null && me.rc != null) {
               o.put("x", me.rc.x);
               o.put("y", me.rc.y);
               o.put("moving", me.getattr(Moving.class) != null);
            }

            if (occupancy && gui != null) {
               try {
                  Scene scene = PrototypePathfinder.observe(gui);
                  boolean solid = false;
                  if (scene != null && scene.occupancy != null && me != null && me.rc != null) {
                     Coord c = scene.occupancy.cellOf(me.rc);
                     if (c != null) {
                        solid = scene.occupancy.at(c.x, c.y) == 1;
                     }
                  }

                  o.put("player_in_solid", solid);
               } catch (Exception var15) {
                  o.put("occupancy_error", var15.getMessage() == null ? var15.toString() : var15.getMessage());
               }
            }

            return o;
         }
      }
   }

   private JSONObject state() {
      JSONObject o = new JSONObject();
      o.put("ok", true);
      o.put("generated_at_ms", System.currentTimeMillis());
      UI ui = this.ui();
      if (ui == null) {
         o.put("connected", false);
         o.put("screen", "none");
         return o;
      } else {
         synchronized (ui) {
            o.put("connected", ui.sess != null);
            GameUI gui = ui.gui;
            LoginScreen login = find(ui.root, LoginScreen.class);
            Charlist chars = find(ui.root, Charlist.class);
            String screen = "unknown";
            if (gui != null && gui.map != null) {
               screen = "game";
            } else if (chars != null) {
               screen = "chars";
            } else if (login != null) {
               screen = "login";
            }

            o.put("screen", screen);
            if (gui == null) {
               return o;
            } else {
               String chrid = gui.chrid;
               String pname = Config.getPlayerName();
               JSONObject character = new JSONObject();
               character.put("id", chrid == null ? "" : chrid);
               character.put("name", pname == null ? "" : pname);
               o.put("character", character);
               Gob me = gui.map == null ? null : gui.map.player();
               if (me != null) {
                  JSONObject player = new JSONObject();
                  player.put("id", me.id);
                  if (me.rc != null) {
                     JSONObject pos = new JSONObject();
                     pos.put("x", me.rc.x);
                     pos.put("y", me.rc.y);
                     player.put("position", pos);
                  }

                  Moving mv = (Moving)me.getattr(Moving.class);
                  player.put("moving", mv != null);
                  if (mv != null) {
                     try {
                        player.put("speed", mv.getv());
                     } catch (Exception var20) {
                     }
                  }

                  o.put("player", player);
               }

               JSONObject bot = new JSONObject();
               bot.put("running", Bot.hasCurrent());
               o.put("active_bot", bot);
               o.put("windows", openWindows(ui.root));
               if (gui.maininv != null) {
                  try {
                     o.put("inventory_used", gui.maininv.filled());
                  } catch (Exception var19) {
                     o.put("inventory_used", -1);
                  }
               }

               Glob glob = gui.map == null ? null : gui.map.glob;
               if (glob != null && me != null && me.rc != null) {
                  try {
                     Coord2d pc = me.rc;
                     long n = glob.oc.stream().filter(g -> g.rc != null && g.rc.dist(pc) <= 30.0).count();
                     o.put("nearby_gobs", n);
                  } catch (Exception var18) {
                     o.put("nearby_gobs", -1);
                  }
               }

               return o;
            }
         }
      }
   }

   private static List<String> openWindows(Widget root) {
      LinkedHashSet<String> names = new LinkedHashSet<>();
      collectWindows(root, names);
      List<String> out = new ArrayList<>(names);
      Collections.sort(out);
      return out;
   }

   private static void collectWindows(Widget w, Set<String> names) {
      if (w != null) {
         if (w instanceof Window) {
            Window win = (Window)w;
            if (win.visible() && win.cap != null && !win.cap.isEmpty()) {
               names.add(win.cap);
            }
         }

         for (Widget ch = w.child; ch != null; ch = ch.next) {
            collectWindows(ch, names);
         }
      }
   }

   private JSONObject runCmd(String cmd) {
      if (cmd.startsWith(":")) {
         cmd = cmd.substring(1);
      }

      JSONObject o = new JSONObject();
      UI ui = this.ui();
      if (ui == null) {
         o.put("ok", false);
         o.put("error", "no ui");
         return o;
      } else {
         StringWriter buf = new StringWriter();
         PrintWriter pw = new PrintWriter(buf);
         synchronized (ui) {
            PrintWriter old = ui.cons.out;
            ui.cons.out = pw;

            try {
               ui.cons.run(ui.root, cmd);
               pw.flush();
               o.put("ok", true);
               o.put("out", buf.toString());
            } catch (Exception var14) {
               o.put("ok", false);
               o.put("error", var14.getMessage() == null ? var14.toString() : var14.getMessage());
               o.put("out", buf.toString());
            } finally {
               ui.cons.out = old;
            }
         }

         o.put("cmd", cmd);
         return o;
      }
   }

   private JSONObject login(String user) {
      JSONObject o = new JSONObject();
      UI ui = this.ui();
      if (ui == null) {
         o.put("ok", false);
         o.put("error", "no ui");
         return o;
      } else {
         synchronized (ui) {
            LoginScreen ls = find(ui.root, LoginScreen.class);
            if (ls == null) {
               o.put("ok", false);
               o.put("error", "not on login screen");
               return o;
            }

            String err = clickSavedAccount(ls, user);
            if (err != null) {
               o.put("ok", false);
               o.put("error", err);
               return o;
            }
         }

         o.put("ok", true);
         return o;
      }
   }

   private JSONObject play(String chr) {
      JSONObject o = new JSONObject();
      UI ui = this.ui();
      if (ui == null) {
         o.put("ok", false);
         o.put("error", "no ui");
         return o;
      } else {
         synchronized (ui) {
            Charlist list = find(ui.root, Charlist.class);
            if (list == null) {
               o.put("ok", false);
               o.put("error", "not on character list");
               return o;
            }

            String err = clickPlay(list, chr);
            if (err != null) {
               o.put("ok", false);
               o.put("error", err);
               return o;
            }
         }

         o.put("ok", true);
         return o;
      }
   }

   public static void tickLogin(LoginScreen ls) {
      if (autologin() && ls != null) {
         if (ls.login != null && ls.login.visible) {
            if (ls.accounts != null && !ls.accounts.accounts.isEmpty()) {
               if (!ls.autologinTried) {
                  ls.autologinTried = true;
                  clickSavedAccount(ls, (String)Bootstrap.authuser.get());
               }
            }
         }
      }
   }

   public static void tickChars(Charlist list, double dt) {
      if (autologin() && list != null && !list.autoplayed) {
         if (!list.chars.isEmpty()) {
            list.autoplayWait += dt;
            if (!(list.autoplayWait < 0.6)) {
               list.autoplayed = true;
               clickPlay(list, autoplay());
            }
         }
      }
   }

   static String clickSavedAccount(LoginScreen ls, String user) {
      if (ls.accounts == null) {
         return "no saved accounts (steam login?)";
      } else {
         Account pick = null;
         synchronized (ls.accounts.accounts) {
            for (Account a : ls.accounts.accounts) {
               if (user != null && !user.isEmpty() && user.equals(a.name)) {
                  pick = a;
                  break;
               }

               if (pick == null) {
                  pick = a;
               }
            }
         }

         if (pick == null) {
            return "no matching saved account";
         } else {
            ls.wdgmsg(ls.accounts, "account", pick.name, pick.token);
            return null;
         }
      }
   }

   static String clickPlay(Charlist list, String chr) {
      String want = chr == null ? "" : chr.trim();
      Charlist.Char pick = null;
      synchronized (list.chars) {
         for (Charlist.Char c : list.chars) {
            if (!want.isEmpty() && want.equals(c.name)) {
               pick = c;
               break;
            }

            if (pick == null) {
               pick = c;
            }
         }
      }

      if (pick == null) {
         return "no characters";
      } else {
         list.wdgmsg("play", new Object[]{pick.name});
         Config.setPlayerName(pick.name);
         return null;
      }
   }

   private UI ui() {
      UILoop lp = this.loop;
      return lp == null ? null : lp.ui;
   }

   public static <T extends Widget> T find(Widget w, Class<T> cl) {
      if (w == null) {
         return null;
      } else if (cl.isInstance(w)) {
         return cl.cast(w);
      } else {
         for (Widget ch = w.child; ch != null; ch = ch.next) {
            T r = find(ch, cl);
            if (r != null) {
               return r;
            }
         }

         return null;
      }
   }

   private static Map<String, String> parseQuery(String q) {
      Map<String, String> m = new LinkedHashMap<>();
      if (q != null && !q.isEmpty()) {
         for (String part : q.split("&")) {
            int eq = part.indexOf(61);

            try {
               if (eq < 0) {
                  m.put(URLDecoder.decode(part, "UTF-8"), "");
               } else {
                  m.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
               }
            } catch (Exception var8) {
            }
         }

         return m;
      } else {
         return m;
      }
   }

   private static String jsonErr(String msg) {
      return new JSONObject().put("ok", false).put("error", msg).toString();
   }

   private static String jsonErr(Exception e) {
      return jsonErr(e.getMessage() == null ? e.toString() : e.getMessage());
   }

   static JSONObject pfScenarios() {
      JSONObject o = new JSONObject();
      JSONArray scenarios = new JSONArray();

      for (String name : PfTestRunner.knownScenarios()) {
         scenarios.put(name);
      }

      o.put("ok", true);
      o.put("scenarios", scenarios);
      return o;
   }

   private JSONObject pfCancel() {
      JSONObject o = new JSONObject();
      Run run = PfTestRunner.cancelRun();
      o.put("ok", true);
      if (run == null) {
         o.put("cancelled", false);
         o.put("run", JSONObject.NULL);
      } else {
         o.put("cancelled", true);
         o.put("run_id", run.id);
         o.put("scenario", run.scenario);
         o.put("status", "cancelling");
      }

      return o;
   }

   private static String help() {
      JSONObject o = new JSONObject();
      o.put("ok", true);
      o.put(
         "endpoints",
         new String[]{
            "GET /status",
            "GET /occupancy",
            "GET /state",
            "POST /cmd",
            "GET /login?user=",
            "GET /play?char=",
            "GET /pf/scenarios",
            "POST /pf/run",
            "GET /pf/result?run_id=",
            "POST /pf/cancel"
         }
      );
      return o.toString();
   }

   private static void reply(Socket sock, int code, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      String reason = code == 200 ? "OK" : (code == 404 ? "Not Found" : "Error");
      OutputStream out = sock.getOutputStream();
      PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
      pw.print("HTTP/1.1 " + code + " " + reason + "\r\n");
      pw.print("Content-Type: application/json; charset=utf-8\r\n");
      pw.print("Content-Length: " + bytes.length + "\r\n");
      pw.print("Connection: close\r\n\r\n");
      pw.flush();
      out.write(bytes);
      out.flush();
   }
}
