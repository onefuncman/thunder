package thunder.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import haven.Utils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MacroStore {
    private static final String SUBDIR = "thunder";
    private static final String FILENAME = "macros.json";

    public static final Gson GSON = new GsonBuilder()
	.registerTypeAdapter(MacroStep.class, (JsonDeserializer<MacroStep>) MacroStore::deserializeStep)
	.registerTypeAdapter(MacroStep.class, (JsonSerializer<MacroStep>) MacroStore::serializeStep)
	.setPrettyPrinting()
	.create();

    private static MacroStore instance;
    private final Path file;
    private List<Macro> macros = new ArrayList<>();

    private MacroStore(Path file) {
	this.file = file;
	load();
    }

    public static synchronized MacroStore get() {
	if(instance == null) {
	    instance = new MacroStore(defaultPath());
	}
	return instance;
    }

    public static Path defaultPath() {
	String appdata = System.getenv("APPDATA");
	if(appdata == null || appdata.isEmpty()) {
	    return Utils.path(System.getProperty("user.home")).resolve(SUBDIR).resolve(FILENAME);
	}
	return Utils.path(appdata).resolve("Haven and Hearth").resolve(SUBDIR).resolve(FILENAME);
    }

    public synchronized List<Macro> list() {
	return Collections.unmodifiableList(new ArrayList<>(macros));
    }

    public synchronized Macro byName(String name) {
	for(Macro m : macros) {
	    if(m.name != null && m.name.equals(name)) return m;
	}
	return null;
    }

    public synchronized void put(Macro macro) {
	for(int i = 0; i < macros.size(); i++) {
	    if(macros.get(i).name != null && macros.get(i).name.equals(macro.name)) {
		macros.set(i, macro);
		save();
		return;
	    }
	}
	macros.add(macro);
	save();
    }

    public synchronized void remove(String name) {
	macros.removeIf(m -> m.name != null && m.name.equals(name));
	save();
    }

    private void load() {
	if(!Files.exists(file)) {
	    macros = new ArrayList<>();
	    return;
	}
	try {
	    String json = new String(Files.readAllBytes(file));
	    Wrapper w = GSON.fromJson(json, new TypeToken<Wrapper>() {}.getType());
	    macros = (w != null && w.macros != null) ? w.macros : new ArrayList<>();
	} catch(IOException | RuntimeException e) {
	    new haven.Warning(e, "macros: failed to load " + file).issue();
	    macros = new ArrayList<>();
	}
    }

    private void save() {
	try {
	    Files.createDirectories(file.getParent());
	    Wrapper w = new Wrapper();
	    w.macros = macros;
	    Files.write(file, GSON.toJson(w).getBytes());
	} catch(IOException e) {
	    new haven.Warning(e, "macros: failed to save " + file).issue();
	}
    }

    private static JsonElement serializeStep(MacroStep step, Type tt, JsonSerializationContext ctx) {
	JsonElement el = ctx.serialize(step, step.getClass());
	JsonObject obj = el.getAsJsonObject();
	obj.addProperty("type", step.type().name());
	return obj;
    }

    private static MacroStep deserializeStep(JsonElement el, Type tt, JsonDeserializationContext ctx)
	throws JsonParseException {
	JsonObject obj = el.getAsJsonObject();
	JsonElement typeEl = obj.get("type");
	if(typeEl == null) throw new JsonParseException("MacroStep missing 'type' field");
	MacroStep.Type type = MacroStep.Type.valueOf(typeEl.getAsString());
	switch(type) {
	case ITEM_ACT:      return ctx.deserialize(obj, MacroStep.ItemAct.class);
	case GOB_ACT:       return ctx.deserialize(obj, MacroStep.GobAct.class);
	case INV_DROP:      return ctx.deserialize(obj, MacroStep.InvDrop.class);
	case FLOWER_CHOICE: return ctx.deserialize(obj, MacroStep.FlowerChoice.class);
	case WAIT:          return ctx.deserialize(obj, MacroStep.Wait.class);
	case SLEEP:         return ctx.deserialize(obj, MacroStep.Sleep.class);
	case CMD:           return ctx.deserialize(obj, MacroStep.Cmd.class);
	default: throw new JsonParseException("Unknown MacroStep type: " + type);
	}
    }

    private static class Wrapper {
	List<Macro> macros;
    }
}
