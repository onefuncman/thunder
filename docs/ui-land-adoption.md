# ui/land adoption (claim window)

`src/haven/res/ui/land/Landwindow.java` is a local copy of the server's `ui/land`
resource, pinned with `@FromResource(name = "ui/land", version = 51)`. It exists
for one line.

## Why

The claim window keeps per-kin-group permission flags in `bflags[]`, which the
published source sizes at 8, the number of kin groups that have a colour. The
server accepts kin groups 0..254, and third-party clients assign groups past 8.
The window's `"shared"` uimsg writes `bflags[g]` straight from the wire, so when
a claim's permissions had been granted to such a group from another client, the
replay on open threw `ArrayIndexOutOfBoundsException` inside resource code.
`bflags` is private to the resource; there is no client-side seam. Same fix as
irongete/brodgar-io-client commit 715251e.

## The change

`int bflags[] = new int[255];` (was 8). Nothing else differs from the served
source. `updflags` reads the table, `PermBox.changed` sends and writes it, and
the `"shared"` uimsg writes it; all key off the same index, so the wider table
is the whole fix.

## Maintenance

- The pin means a newer published `ui/land` sidelines this copy and the 8-slot
  table returns. `java -cp bin/hafen.jar haven.Resource find-updates` reports
  it. When it does: `haven.Resource get-code -o <staging> ui/land`, re-apply the
  one line, bump the version.
- Related: `BuddyWnd.gc` is 255 long for the same reason (see its comment);
  `ui/vlg` and `ui/realm` index it bare and are not adopted.
