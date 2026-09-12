package thunder.cookbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Re-implementation of the filter syntax parsed by civ.hearthworld.com's
 * cookbook page (see its "How-to" help popup, and `filterPredicate` in the
 * site's main-es2015 bundle). Conditions are separated by ';' and all must
 * match (AND):
 *
 *   name:text        - name contains text (case-insensitive)
 *   name:"text"       - name equals text exactly
 *   from:text         - some ingredient contains text
 *   from:"text"       - some ingredient equals text exactly
 *   -name:..., -from:...  - negate the above
 *   attr[2]>N, <N, =N     - raw FEP value comparison, e.g. "str>50", "int2=10"
 *   attr[2]>N%, <N%, =N%  - FEP percentage-of-total comparison, e.g. "str>50%"
 *
 * attr is one of str/agi/int/con/per/cha/dex/wil/psy (see CookbookItem.ATTR_ORDER).
 * A condition with N == 0 is treated as a no-op, matching the site's own behavior.
 */
public class CookbookQuery {
    private final List<Condition> conditions;

    private CookbookQuery(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public static CookbookQuery parse(String query) {
        List<Condition> out = new ArrayList<>();
        String q = (query == null) ? "" : query.toLowerCase() + ";";

        StringBuilder key = new StringBuilder();
        StringBuilder val = new StringBuilder();
        String mode = "pred"; // pred | name | from | fep
        char op = 0;
        boolean negate = false;
        boolean exact = false;

        for(int c = 0; c < q.length(); c++) {
            char ch = q.charAt(c);
            if(ch == '-' && key.length() == 0 && mode.equals("pred")) {
                negate = true;
            } else if(ch == ';') {
                if(exact && val.length() > 0 && val.charAt(val.length() - 1) == '"') {
                    val.setLength(val.length() - 1);
                }
                switch(mode) {
                    case "fep":
                        addFepCondition(out, key.toString(), op, val.toString());
                        break;
                    case "name":
                        out.add(new Condition(Type.NAME, negate, exact, val.toString()));
                        break;
                    case "from":
                        out.add(new Condition(Type.FROM, negate, exact, val.toString()));
                        break;
                    default:
                        break; // bare text with no prefix is a no-op, matching the site
                }
                key.setLength(0);
                val.setLength(0);
                mode = "pred";
                op = 0;
                negate = false;
                exact = false;
            } else if(ch == ':' && mode.equals("pred")) {
                String k = key.toString();
                if(k.equals("from")) {mode = "from";}
                else if(k.equals("name")) {mode = "name";}
                key.setLength(0);
            } else if((ch == '>' || ch == '<' || ch == '=') && mode.equals("pred")) {
                op = ch;
                mode = "fep";
            } else if(mode.equals("pred")) {
                key.append(ch);
            } else if(mode.equals("fep")) {
                val.append(ch);
            } else { // name | from
                if(val.length() == 0 && ch == '"') {
                    exact = true;
                } else {
                    val.append(ch);
                }
            }
        }

        return new CookbookQuery(out);
    }

    private static void addFepCondition(List<Condition> out, String attr, char op, String rawVal) {
        boolean percent = rawVal.endsWith("%");
        String numStr = percent ? rawVal.substring(0, rawVal.length() - 1) : rawVal;
        double num;
        try {
            num = Double.parseDouble(numStr);
        } catch(NumberFormatException e) {
            return;
        }
        if(num == 0) {return;}
        out.add(new Condition(percent ? Type.FEP_PERCENT : Type.FEP_RAW, false, false, attr, op, num));
    }

    public boolean matches(CookbookItem item) {
        for(Condition c : conditions) {
            if(!c.test(item)) {return false;}
        }
        return true;
    }

    private enum Type {NAME, FROM, FEP_RAW, FEP_PERCENT}

    private static class Condition {
        final Type type;
        final boolean negate;
        final boolean exact;
        final String text;
        final char op;
        final double num;

        Condition(Type type, boolean negate, boolean exact, String text) {
            this(type, negate, exact, text, (char) 0, 0);
        }

        Condition(Type type, boolean negate, boolean exact, String text, char op, double num) {
            this.type = type;
            this.negate = negate;
            this.exact = exact;
            this.text = text;
            this.op = op;
            this.num = num;
        }

        boolean test(CookbookItem item) {
            switch(type) {
                case NAME: {
                    boolean m = exact ? item.name.equalsIgnoreCase(text) : item.name.toLowerCase().contains(text);
                    return negate != m;
                }
                case FROM: {
                    boolean any = false;
                    for(CookbookItem.Ingredient ig : item.ingredients) {
                        boolean m = exact ? ig.name.equalsIgnoreCase(text) : ig.name.toLowerCase().contains(text);
                        if(m) {any = true; break;}
                    }
                    return negate != any;
                }
                case FEP_RAW:
                    return compare(item.feps.get(text));
                case FEP_PERCENT:
                    return compare(item.fepPercent.get(text));
                default:
                    return true;
            }
        }

        private boolean compare(Double actual) {
            if(actual == null) {return false;}
            switch(op) {
                case '>': return actual >= num;
                case '<': return actual <= num;
                case '=': return actual == num;
                default: return true;
            }
        }
    }
}
