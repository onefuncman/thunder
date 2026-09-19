package thunder.cellar;

import haven.GItem;
import haven.ItemInfo;
import haven.Loading;
import haven.WItem;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact stone and ore names produced by chipping cellar bumlings. */
final class CellarDiggerMaterials {
    private static final String STACK_SUFFIX = ", stack of";
    private static final Set<String> ROCK_NAMES;

    static {
        Set<String> names = new HashSet<>(Arrays.asList(
            "Alabaster", "Apatite", "Arkose", "Basalt", "Bat Rock", "Black Coal", "Breccia",
            "Cat Gold", "Chert", "Diabase", "Diorite", "Dolomite", "Dross", "Eclogite",
            "Feldspar", "Flint", "Fluorospar", "Gabbro", "Gneiss", "Granite", "Graywacke",
            "Greenschist", "Hornblende", "Jasper", "Korund", "Kyanite", "Lava Rock",
            "Limestone", "Marble", "Mica", "Microlite", "Obsidian", "Olivine", "Orthoclase",
            "Pegmatite", "Porphyry", "Pumice", "Quarryartz", "Quartz", "Rhyolite",
            "Rock Crystal", "Rock Salt", "Sandstone", "Schist", "Serpentine",
            "Shard of Conch", "Slag", "Slate", "Soapstone", "Sodalite", "Sunstone", "Zincspar",
            "Black Ore", "Bloodstone", "Cassiterite", "Chalcopyrite", "Cinnabar", "Direvein",
            "Galena", "Heavy Earth", "Horn Silver", "Iron Ochre", "Lead Glance", "Leaf Ore",
            "Malachite", "Meteorite", "Peacock Ore", "Schrifterz", "Silvershine", "Wine Glance"
        ));
        ROCK_NAMES = Collections.unmodifiableSet(names);
    }

    private CellarDiggerMaterials() {}

    static boolean isRockMaterial(WItem item) {
        return item != null && isRockMaterial(item.item);
    }

    static boolean isRockMaterial(GItem item) {
        return item != null && isRockMaterialName(itemName(item));
    }

    static boolean isRockMaterialName(String name) {
        if(name == null) return false;
        String normalized = name.endsWith(STACK_SUFFIX)
            ? name.substring(0, name.length() - STACK_SUFFIX.length()) : name;
        return ROCK_NAMES.contains(normalized);
    }

    private static String itemName(GItem item) {
        try {
            List<ItemInfo> info = item.info();
            if(info == null) return null;
            ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, info);
            return name == null ? null : name.original;
        } catch(Loading loading) {
            return null;
        }
    }
}
