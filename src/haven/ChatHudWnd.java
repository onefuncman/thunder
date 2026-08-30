package haven;

public class ChatHudWnd extends Window {
    public final ChatUI chat;

    public ChatHudWnd(ChatUI chat) {
	super(UI.scale(600, 180), "Chat");
	this.chat = add(chat, Coord.z);
	resize(UI.scale(600, 180));
    }

    @Override
    protected void added() {
	super.added();
	chat.move(Coord.z);
	chat.resize(csz());
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	if(chat != null)
	    chat.resize(sz);
    }

    @Override
    protected Deco makedeco() {
	return(new DecoX(this.large).dragsize(true));
    }

    @Override
    public void close() {
	chat.targetshow = false;
	Utils.setprefb("chatvis", false);
	hide();
    }
}
