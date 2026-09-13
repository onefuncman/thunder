package thunder.cookbook;

import haven.*;

/** Login popup for civ.hearthworld.com, styled like the site's own /Auth page. */
public class CookbookLoginWnd extends WindowX {
    private final TextEntry user;
    private final TextEntry pass;
    private final CheckBox keep;
    private final Button login;
    private final Label error;
    private volatile boolean closed = false;

    // Set by the background login callback, applied on the next tick() --
    // AWT/Java2D text rendering (Label.settext, new Button(...), etc.) is
    // not safe to call off the main thread, so the callback itself only
    // touches these plain fields.
    private volatile boolean pendingResult = false;
    private volatile boolean pendingSuccess = false;
    private volatile String pendingError = null;

    public CookbookLoginWnd() {
        super(Coord.z, "Log in to the Cookbook");
        justclose = true;
        int fw = UI.scale(220);

        Widget prev = add(new Label("Username"), 0, 0);
        user = add(new TextEntry(fw, ""), prev.pos("bl").adds(0, 1));
        user.canactivate = true;

        prev = add(new Label("Password"), user.pos("bl").adds(0, 10));
        pass = add(new TextEntry(fw, ""), prev.pos("bl").adds(0, 1));
        pass.pw = true;
        pass.canactivate = true;

        keep = add(new CheckBox("Keep me logged in"), pass.pos("bl").adds(0, 10));
        keep.a = true;

        error = add(new Label(""), keep.pos("bl").adds(0, 10));

        login = add(new Button(fw, "Log in", this::submit), error.pos("bl").adds(0, 10));

        pack();
        setfocus(user);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close")) {
            close();
        } else if(((sender == user) || (sender == pass)) && msg.equals("activate")) {
            submit();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    private void submit() {
        String u = user.text().trim();
        String p = pass.text();
        if(u.isEmpty() || p.isEmpty()) {
            error.settext("Enter a username and password.");
            return;
        }
        login.disable(true);
        error.settext("Logging in...");
        CookbookAuth.loginAsync(u, p, keep.a, (success, err) -> {
            // Background thread: only touch plain fields here, never widgets.
            synchronized(ui) {
                if(closed) {return;}
                pendingSuccess = success;
                pendingError = err;
                pendingResult = true;
            }
        });
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(pendingResult) {
            pendingResult = false;
            login.disable(false);
            if(pendingSuccess) {
                close();
                CookbookWnd.toggle(ui, true);
            } else {
                error.settext(pendingError != null ? pendingError : "Login failed.");
            }
        }
    }

    public void close() {
        ui.destroy(this);
    }

    @Override
    public void destroy() {
        closed = true;
        if((ui != null) && (ui.gui != null) && (ui.gui.cookbookLoginWnd == this)) {ui.gui.cookbookLoginWnd = null;}
        super.destroy();
    }
}
