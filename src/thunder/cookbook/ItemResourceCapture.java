package thunder.cookbook;

import haven.Config;
import haven.GItem;
import haven.Loading;
import haven.Resource;

import java.io.File;
import java.io.FileWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive capture of real (display name -> resource path) pairs for items
 * actually seen in inventory/container windows during live play -- same
 * "no offline database exists, observe it live" idea as RecipeCapture, but
 * for plain item resources instead of crafting recipes. Exists because
 * several Cookbook ingredients (Chantrelles, Blueberries, Seaberries) have
 * no discoverable static icon via brodgar.io's resource browser, even
 * though the user's Hurricane-client screenshots prove a real flat icon
 * resource exists somewhere for them -- this reads the ground truth
 * straight from a live game session instead of guessing paths. Hooked from
 * WItem.draw() (haven/WItem.java): thunder.cookbook.ItemResourceCapture.consider(item);
 * See docs/cookbook-integration.md.
 */
public class ItemResourceCapture {
    private static final Set<String> logged = ConcurrentHashMap.newKeySet();

    public static void consider(GItem item) {
        Resource res;
        try {
            res = item.getres();
        } catch(Loading l) {
            return;
        } catch(Exception e) {
            return;
        }
        if(res == null) {return;}
        if(!logged.add(res.name)) {return;}
        String name = "???";
        try {
            Resource.Tooltip tt = res.layer(Resource.tooltip);
            if(tt != null) {name = tt.t;}
        } catch(Exception ignored) {}
        try {
            File f = Config.getFile("cookbook-item-resources.log");
            try(FileWriter fw = new FileWriter(f, true)) {
                fw.write(name + " -> " + res.name + "\n");
            }
        } catch(Exception ignored) {}
    }
}
