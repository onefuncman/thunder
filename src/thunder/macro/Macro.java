package thunder.macro;

import java.util.ArrayList;
import java.util.List;

public class Macro {
    public String name;
    public int defaultRepeat = 1;
    public List<MacroStep> steps = new ArrayList<>();

    public Macro() {}

    public Macro(String name) {
	this.name = name;
    }
}
