package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SfxVolTest {
    @Test
    void exactNamesMatchTheirGroup() {
	assertEquals(SfxVol.Group.CAULDRON, SfxVol.group("sfx/terobjs/cauldron"));
	assertEquals(SfxVol.Group.QUERN, SfxVol.group("sfx/terobjs/quern"));
	assertEquals(SfxVol.Group.GRINDER, SfxVol.group("sfx/squeak"));
	assertEquals(SfxVol.Group.SAWMILL, SfxVol.group("sfx/terobjs/sawmill"));
	assertEquals(SfxVol.Group.MINING, SfxVol.group("sfx/mineout"));
    }

    @Test
    void hatPrefixMatchesAnyHat() {
	assertEquals(SfxVol.Group.HATS, SfxVol.group("sfx/items/hats/bullfest"));
	assertEquals(SfxVol.Group.HATS, SfxVol.group("sfx/items/hats/quack"));
	assertEquals(SfxVol.Group.HATS, SfxVol.group("sfx/items/bells"));
    }

    @Test
    void exactNamesDoNotMatchAsPrefixes() {
	// "sfx/terobjs/cauldron" is exact; a hypothetical sibling must not match.
	assertNull(SfxVol.group("sfx/terobjs/cauldronlid"));
	assertNull(SfxVol.group("sfx/squeaker"));
    }

    @Test
    void ungroupedAndNullResolveToNoGroup() {
	assertNull(SfxVol.group("sfx/terobjs/door"));
	assertNull(SfxVol.group(null));
    }
}
