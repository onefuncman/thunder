# Haven & Hearth Game Protocol

This document describes the wire protocol between the Hafen/Haven client (this codebase) and the game server. The protocol is authored by loftar; this document is a client-side reverse/forward reference written against the code that implements it in `src/haven/`. Numbers and field layouts cited here come from that code, not from any external spec -- when server behaviour diverges from what's described, the code is authoritative.

Protocol version: `Session.PVER = 30` (`src/haven/Session.java:41`).

---

## 1. Transport layer

The transport is **UDP** with a custom reliability layer implemented in `src/haven/Connection.java`. Payloads travel as `DatagramChannel` packets (unfragmented at the UDP level -- the protocol fragments large messages itself; see section 6).

Three concepts matter:

1. **Packet** -- a single UDP datagram. Starts with a 1-byte message-type tag (`MSG_*`), described in section 2.
2. **Reliable stream** -- an ordered, gap-filled stream of `RMessage`s carried inside `MSG_REL` packets, acked with `MSG_ACK`. Each has a 16-bit sequence number.
3. **Object delta stream** -- gob state updates. Carried in `MSG_OBJDATA` with per-object frame numbers and cumulative acks (`MSG_OBJACK`). Not part of the reliable stream -- it has its own resend / ack model driven by gob ID and frame number.

The `Connection.Main` task (`src/haven/Connection.java:451`) runs a single-threaded event loop that selects on the socket, retransmits pending reliable messages with exponential-ish backoff (`Connection.java:631-664`: 0, 0.08s, 0.20s, 0.62s, 2.00s), coalesces `MSG_OBJACK` payloads, and sends `MSG_BEAT` every 5s of idle.

`Transport` (`src/haven/Transport.java`) is the abstract interface over that loop; `Session` talks to it via `Transport.Callback` (`Transport.java:39-43`), which receives three kinds of event: `handle(PMessage)` for reliable rmsgs, `handle(OCache.ObjDelta)` for object deltas, and `mapdata(Message)` for map fragments.

---

## 2. Packet types (MSG_*)

Defined at `src/haven/Session.java:44-53`. The first byte of every packet is one of these:

| Tag | Value | Dir | Purpose |
|---|---|---|---|
| `MSG_SESS`    | 0 | both  | Session open / accept. Only seen at handshake. |
| `MSG_REL`     | 1 | both  | Carries one or more `RMessage`s on the reliable stream. |
| `MSG_ACK`     | 2 | both  | Cumulative ack for the reliable stream (one uint16 seq). |
| `MSG_BEAT`    | 3 | C->S  | Keepalive, sent after 5s idle. |
| `MSG_MAPREQ`  | 4 | C->S  | Client requests a map grid by coord. |
| `MSG_MAPDATA` | 5 | S->C  | Fragment of a map grid payload. |
| `MSG_OBJDATA` | 6 | S->C  | One or more object deltas (gob updates). |
| `MSG_OBJACK`  | 7 | C->S  | Cumulative acks for object frames. |
| `MSG_CLOSE`   | 8 | both  | Terminate the session cleanly. |
| `MSG_CRYPT`   | 9 | both  | Encrypted wrapper for any of the above (HCrypt mode). |

### Session open (`MSG_SESS`)

Plain-text form (`Connection.java:349-361`):

```
uint8   MSG_SESS
uint16  2                       // protocol marker
string  "Hafen" | "Hafen/<confid>"
uint16  PVER                    // 30
string  username
uint16  cookie_len
bytes   cookie
list    login_args              // see section 8 (type-tagged list)
```

Encrypted (HCrypt) form (`Connection.java:362-377`): same outer frame advertises `"HCrypt"` instead of `"Hafen"`, includes a 16-byte salt, then carries the real payload encrypted under AES-GCM derived from `cookie + salt`. After accept, every further packet is wrapped in `MSG_CRYPT`.

Server replies with `MSG_SESS { uint8 error }`. `error == 0` means accepted. Non-zero error codes are in `Session.java:54-59`:

- `SESSERR_AUTH = 1` (bad cookie)
- `SESSERR_BUSY = 2`
- `SESSERR_CONN = 3`
- `SESSERR_PVER = 4` (protocol version mismatch)
- `SESSERR_EXPR = 5`
- `SESSERR_MESG = 6` (followed by a string)

### Reliable carrier (`MSG_REL`)

Layout (`Connection.java:597-609`):

```
uint8   MSG_REL
uint16  first_seq                     // seq of the first RMessage in this packet
repeat:
  uint8  type                         // RMSG type; high bit set = length-prefixed
  if (type & 0x80):
    uint16 len
    bytes  payload[len]
  else:
    bytes  payload                    // to end of packet
```

Multiple `RMessage`s can share a single `MSG_REL` packet. Each gets `seq = first_seq + index`. Gaps are buffered (`Connection.Main.waiting`, `Connection.java:452`) until the hole fills; once contiguous, they dispatch in order and an ack is scheduled. Acks are coalesced with a 30 ms hold (`ACK_HOLD`, `Connection.java:40`).

### Object data (`MSG_OBJDATA`)

Packed list of deltas (`Connection.java:541-590`):

```
uint8   MSG_OBJDATA
repeat until EOM:
  uint8   delta_flags               // see below
  uint32  gob_id
  int32   frame                     // signed; may be negative during session init
  if (flags & 1): frame is also the initframe
  if (flags & 8): int32 initframe
  repeat until OD_END:
    attr header (see section 4)
```

Delta flags (`Connection.java:543`):

- `0x01` -- **initial frame** (same int is both frame and initframe)
- `0x02` -- **virtual** (`OD_VIRTUAL`, relevant only for rendering)
- `0x04` -- **old** (replayed/stale)
- `0x08` -- **initframe present** as a separate int32

Each delta acks cumulatively: `ObjAck` (`Connection.java:439-449`) tracks the newest frame per gob seen; acks are flushed when a gob has been quiet for `OBJACK_HOLD = 0.08s` or forced after `OBJACK_HOLD_MAX = 0.5s` (`Connection.java:41`, `666-691`).

### Map data (`MSG_MAPDATA`)

One fragment of a grid (client-assembled). Payload layout (`ProtoDecoder.decodeMapData`, `src/haven/proto/ProtoDecoder.java:490-519`):

```
uint8   MSG_MAPDATA
int32   pktid                         // identifies the grid being sent
uint16  off                           // byte offset into the assembled grid
uint16  len                           // total grid size in bytes
bytes   fragment                      // this fragment's bytes (size - 8)
```

Client accumulates fragments by `pktid` in `MCache`; once `off + fragment_size == len` the grid is decoded. `MCache` requests grids via `MSG_MAPREQ { coord }` (`src/haven/MCache.java:1289-1292`). A grid request is retried every 1000 ticks up to 5 times before it's dropped.

### Close and heartbeat

- `MSG_CLOSE`: either side can send. Receiving it ends the loop (`Connection.java:715`).
- `MSG_BEAT`: client-originated keepalive, sent when 5 seconds pass with no outbound traffic (`Connection.java:737-739`).

---

## 3. Reliable messages (RMSG_*)

Defined at `src/haven/RMessage.java:30-47`. These are the body of `MSG_REL` packets; they're delivered to `Session.handlerel` (`Session.java:323-343`):

| Tag | Value | Dir | Purpose |
|---|---|---|---|
| `RMSG_NEWWDG`   | 0  | S->C | Create a widget. |
| `RMSG_WDGMSG`   | 1  | both | Deliver a message to a widget. Most in-game events are carried by this. |
| `RMSG_DSTWDG`   | 2  | S->C | Destroy a widget. |
| `RMSG_MAPIV`    | 3  | S->C | Map invalidation (drop cached grid(s)). |
| `RMSG_GLOBLOB`  | 4  | S->C | Global state blob (astronomy, world time, etc.). |
| `RMSG_PAGINAE`  | 5  | -    | Deprecated. |
| `RMSG_RESID`    | 6  | S->C | Bind a numeric resource id to a resource name+version. |
| `RMSG_PARTY`    | 7  | -    | Deprecated (now carried as WDGMSGs). |
| `RMSG_SFX`      | 8  | -    | Deprecated. |
| `RMSG_CATTR`    | 9  | -    | Deprecated. |
| `RMSG_MUSIC`    | 10 | -    | Deprecated. |
| `RMSG_TILES`    | 11 | -    | Deprecated. |
| `RMSG_BUFF`     | 12 | -    | Deprecated. |
| `RMSG_SESSKEY`  | 13 | both | Session-key exchange (HMAC signing key). |
| `RMSG_FRAGMENT` | 14 | both | Fragment of a larger RMessage. See section 6. |
| `RMSG_ADDWDG`   | 15 | S->C | Reparent an existing widget to a new parent. |
| `RMSG_WDGBAR`   | 16 | S->C | Widget dependency barrier (see below). |
| `RMSG_USERAGENT`| 17 | both | User-agent info. |

### NEWWDG / ADDWDG / DSTWDG / WDGMSG / WDGBAR

From `ProtoDecoder.decodeRel` cases at `src/haven/proto/ProtoDecoder.java:105-161`:

**RMSG_NEWWDG** (0):
```
int32   widget_id
string  type                          // widget class name ("chatwnd", "inv", ...)
int32   parent_id
list    pargs                         // placement/position args (parent-meaningful)
list    cargs                         // constructor args (widget-meaningful)
```

**RMSG_ADDWDG** (15):
```
int32   widget_id
int32   new_parent_id
list    pargs
```

**RMSG_WDGMSG** (1) -- workhorse of the UI layer:
```
int32   widget_id
string  msg_name                      // "set", "tip", "glut", "prog", "tt", ...
list    args
```
`WDGMSG` is how the server sends a stream of piecemeal updates to an existing widget (progress bars, tooltips, chat lines, errors, etc.). The client's outbound equivalent (button clicks, context-menu selections, chat input) is sent via the client's `sendmsg`/`wdgmsg` path and arrives on the wire inside `MSG_REL` as well, but is tagged `WDGMSG_OUT` by the proto inspector to distinguish direction.

**RMSG_DSTWDG** (2):
```
int32   widget_id
```

**RMSG_WDGBAR** (16) -- barrier: a list of int32 widget IDs the server expects the client to have destroyed before it sends subsequent messages that depend on those destructions.

### RMSG_MAPIV (3) -- map invalidation

```
uint8 type
switch type:
  0: Coord c                          // invalidate one grid
  1: Coord ul; Coord lr               // invalidate rectangle
  2: (nothing)                        // invalidate all (trim)
```
Handled in `Glob.map.invalblob` (`Session.java:329`).

### RMSG_GLOBLOB (4)

Opaque list of sub-blobs. Dispatched to `Glob.blob` (`Session.java:331`) which unpacks time, astronomy, and weather subtypes. Not further decoded in this doc -- see `Glob.java`.

### RMSG_RESID (6)

```
uint16  resid
string  name
uint16  version
```
Binds a numeric id to a server-side resource. Every subsequent reference to a resource in OBJDATA/WDGMSG uses this 16-bit id; the client resolves it via `Session.getres` / `rescache` (`Session.java:302-321`). `resid == 65535` is the "nil resource" sentinel.

### RMSG_SESSKEY (13)

Opaque bytes carrying a JWK-formatted HMAC key used for signing client-originated messages that need integrity (e.g. character auth). The client generates its own ES256 key in `Session.java:382-383` and sends it as its first reliable message; the server replies with a corresponding HMAC key.

---

## 4. Object deltas (OD_*)

An `ObjDelta` carries one gob-id plus an ordered list of attribute deltas. Attribute types are defined at `src/haven/OCache.java:39-60`:

| Tag | Value | Purpose |
|---|---|---|
| `OD_REM`     | 0   | Remove this gob. |
| `OD_MOVE`    | 1   | Set position and heading. |
| `OD_RES`     | 2   | Bind gob to a resource. |
| `OD_LINBEG`  | 3   | Start a linear interpolation (movement animation). |
| `OD_LINSTEP` | 4   | Advance or end a linear interpolation. |
| `OD_SPEECH`  | 5   | Speech bubble over gob. |
| `OD_COMPOSE` | 6   | Composite skeleton resource (humanoids). |
| `OD_ZOFF`    | 7   | Vertical draw offset. |
| `OD_LUMIN`   | 8   | Light source on this gob. |
| `OD_AVATAR`  | 9   | Avatar resource layers. |
| `OD_FOLLOW`  | 10  | Attach gob as child of another gob. |
| `OD_HOMING`  | 11  | Move toward a target (projectiles). |
| `OD_OVERLAY` | 12  | Add/remove an overlay (buffs, auras, effects). |
| `OD_AUTH`    | 13  | Removed. |
| `OD_HEALTH`  | 14  | HP percentage. |
| `OD_BUDDY`   | 15  | Removed. |
| `OD_CMPPOSE` | 16  | Composite pose sequence (which animation is playing). |
| `OD_CMPMOD`  | 17  | Composite mesh modifiers (body part resources). |
| `OD_CMPEQU`  | 18  | Composite equipment (worn items). |
| `OD_ICON`    | 19  | Minimap / overhead icon. |
| `OD_RESATTR` | 20  | Free-form resource-defined attribute. |
| `OD_END`     | 255 | End of attribute list for this delta. |

### Attribute header encoding

The attribute header is compact and has two forms (`Connection.java:551-569`):

**Short form** (`type & 0x80 == 0`): a single byte encodes both the type (low 3 bits, indexed into `OCache.compodmap`) and the length (bits 3-6).
```
bits 7:   0 (short-form marker)
bits 6-3: length_minus_one, or 0 meaning "zero bytes"
bits 2-0: compodmap index
```
`OCache.compodmap = {OD_REM, OD_RESATTR, OD_FOLLOW, OD_MOVE, OD_RES, OD_LINBEG, OD_LINSTEP, OD_HOMING}` (`OCache.java:61`) -- only these 8 most-common types get the short encoding, which is how `OD_MOVE` / `OD_LINSTEP` stay cheap on the wire.

**Long form** (`type & 0x80 != 0`):
```
uint8  type | 0x80
uint8  len_hdr                        // if high bit set: uint16 length follows; else length = len_hdr & 0x7f
(uint16 len)?                         // only when len_hdr & 0x80 set
bytes  payload[len]
```

### Per-type payloads

Payloads, decoded in `ProtoDecoder.decodeAttrDelta` (`src/haven/proto/ProtoDecoder.java:281-467`):

- **OD_MOVE**: `int32 x; int32 y; uint16 angle` -- position in world units, angle in `2pi/0x10000`.
- **OD_RES**: `uint16 resid` -- server-bound resource id; 65535 means nil.
- **OD_LINBEG**: `int32 sx; int32 sy; int32 tx; int32 ty` -- interpolation from-to, in world units.
- **OD_LINSTEP**: `int32 w` -- "progress" parameter for current interpolation; `w == -1` means "done".
- **OD_SPEECH**: `int16 zoff_cm; string text`.
- **OD_COMPOSE**: `uint16 resid` -- skeleton resource.
- **OD_ZOFF**: `int16 zoff` -- millimetres? (not specified; passed through).
- **OD_LUMIN**: `int32 x; int32 y; uint16 size; uint8 strength`.
- **OD_AVATAR**: sequence of `uint16 resid` terminated by 65535.
- **OD_FOLLOW**: `uint32 oid` (0xffffffff = detach), else `int32 xoff; int32 yoff`.
- **OD_HOMING**: `uint32 target_oid` (0xffffffff = cancel), else `int32 tx; int32 ty; int32 v`.
- **OD_OVERLAY**:
  ```
  int32 overlay_id_with_flag           // bit 0 = "persistent"; overlay_id = raw >> 1
  uint16 resid                         // 65535 = remove this overlay
  ```
- **OD_HEALTH**: `uint8 hp` -- percentage 0-100.
- **OD_ICON**: `uint16 resid` (65535 = clear).
- **OD_RESATTR**:
  ```
  uint16 resid
  uint8  len                           // 0 = remove; else attribute payload follows
  bytes  data[len]
  ```
  Content is opaque to the engine; resolved by the named resource's scripting.
- **OD_REM**: empty -- gob removal marker.
- **OD_CMPPOSE** (`ProtoDecoder.java:386-400`):
  ```
  uint8 pfl                            // bit 0: interp; bit 1: poses present; bit 2: tposes present
  uint8 pseq                           // pose sequence number
  if (pfl & 2): poselist                       // see below
  if (pfl & 4): poselist; uint8 ttime_tenths   // transition time in 0.1s units
  ```
  **poselist** = repeat { `uint16 resid`; if `resid & 0x8000`: `uint8 sdtlen; bytes sdt[sdtlen]` } until `resid == 0xffff`. `sdt` is resource-specific state data.
- **OD_CMPMOD** (`ProtoDecoder.java:401-429`): list of mesh modifiers terminated by `resid == 0xffff`, each carrying a nested texture list with optional `sdt`.
- **OD_CMPEQU** (`ProtoDecoder.java:430-458`): list of equipment slots terminated by `h == 0xff`, per slot:
  ```
  uint8 h                              // bit 7: off present; bits 6-0: equipment slot type
  string attach_name
  uint16 resid                         // high bit: sdt follows (uint8 len; bytes data)
  if (h & 0x80): int16 x; int16 y; int16 z       // offset in mm
  ```

Unknown attribute types are skipped and surface as "N bytes (raw)" in the inspector.

---

## 5. Type-tagged lists (T_*)

`RMSG_NEWWDG`'s `pargs`/`cargs` and `RMSG_WDGMSG`'s `args` are serialized as a flat, type-tagged list. Decoding is in `Message.tto0` (`src/haven/Message.java:272-336`); tags are defined at `Message.java:35-67`:

| Tag | Value | Type | Encoding |
|---|---|---|---|
| `T_END`     | 0  | (terminator) | -- |
| `T_INT`     | 1  | int32 | 4 bytes LE |
| `T_STR`     | 2  | string | null-terminated UTF-8 |
| `T_COORD`   | 3  | Coord | int32 x, int32 y |
| `T_UINT8`   | 4  | uint8 | 1 byte |
| `T_UINT16`  | 5  | uint16 | 2 bytes LE |
| `T_COLOR`   | 6  | Color | 4 x uint8 (RGBA) |
| `T_FCOLOR`  | 7  | FColor | 4 x float32 |
| `T_TTOL`    | 8  | nested list | recursive; ends at T_END |
| `T_INT8`    | 9  | int8 | 1 byte |
| `T_INT16`   | 10 | int16 | 2 bytes LE |
| `T_NIL`     | 12 | null | -- |
| `T_UID`     | 13 | UID | int64 |
| `T_BYTES`   | 14 | byte[] | uint8 len; if `len & 0x80` then `int32` true length; payload |
| `T_FLOAT32` | 15 | float | 4 bytes IEEE 754 |
| `T_FLOAT64` | 16 | double | 8 bytes IEEE 754 |
| `T_FCOORD32`| 18 | Coord2d | 2 x float32 |
| `T_FCOORD64`| 19 | Coord2d | 2 x float64 |
| `T_FLOAT8`  | 21 | MiniFloat | 1 byte |
| `T_FLOAT16` | 22 | HalfFloat | 2 bytes |
| `T_SNORM8`  | 23 | signed-normalized | 1 byte -> [-1,1] |
| `T_UNORM8`  | 24 | unsigned-normalized | 1 byte -> [0,1] |
| `T_MNORM8`  | 25 | modular normalized | 1 byte -> [0,1) |
| `T_SNORM16` | 26 | | 2 bytes |
| `T_UNORM16` | 27 | | 2 bytes |
| `T_MNORM16` | 28 | | 2 bytes |
| `T_SNORM32` | 29 | | 4 bytes |
| `T_UNORM32` | 30 | | 4 bytes |
| `T_MNORM32` | 31 | | 4 bytes |
| `T_MAP`     | 32 | key-value map | repeated `tto, tto` pairs until `T_END` |
| `T_LONG`    | 33 | int64 | 8 bytes |
| `T_RESSPEC` | 34 | Resource.Spec | `string name; uint16 version` |
| `T_RESID`   | 35 | ResID | `uint16 resid`; 65535 = null |

Lists end either by reaching end-of-message (`eom`) or by hitting a `T_END` tag. Nested lists use `T_TTOL` to open a sublist that ends at its own `T_END`.

---

## 6. Message fragmentation (RMSG_FRAGMENT)

Large RMessages are split across multiple `RMSG_FRAGMENT` (14) carriers. Layout (`Connection.java:461-484`):

```
uint8  head
bytes  payload
```
where `head` encodes both the underlying message type and the continuation state:

- `(head & 0x80) == 0`: **start fragment**. `head` IS the inner RMSG type; payload is the first chunk. Client starts a new `fragbuf`.
- `head == 0x80`: **middle fragment**. Payload appends to `fragbuf`.
- `head == 0x81`: **end fragment**. Payload appends, then the reassembled `PMessage(fragtype, fragbuf)` is dispatched through `handlerel` recursively.
- Any other head: format error.

Only one fragment chain can be in flight at a time on the reliable stream. If a new start fragment arrives mid-assembly, that's a protocol error (`Connection.java:464-465`).

---

## 7. Encryption (HCrypt)

Activated by setting the `haven.hcrypt` system property (`Connection.encrypt`, `Connection.java:39`). When enabled, the handshake negotiates an AES-GCM session key derived from the auth cookie and a client-random 16-byte salt (`Connection.java:92-230`). After that, every outbound packet is wrapped:

```
uint8  MSG_CRYPT
bytes  aes_gcm(plaintext_packet, session_key, sequenced_nonce)
```

with a monotonically increasing sequence (`Crypto.tseq`) per direction. Received packets are decrypted in place and replay-protected via a sliding-window seq set (`Crypto.rseqs`). The outer `MSG_CLOSE` is also wrapped.

HCrypt is opt-in on the client. `MSG_SESS` handshake packets are never encrypted themselves, but when encryption is requested the inner auth payload goes inside an AES-GCM blob within the `MSG_SESS` body.

---

## 8. Widget-message conventions

The client issues widget messages (button clicks, selections, chat input) by building a `PMessage(RMSG_WDGMSG)`, adding the target widget id, message name, and a type-tagged list of args, then handing it to `Session.sendmsg` which queues it as a reliable message. They travel inside `MSG_REL` just like inbound ones but are generally not decoded by any code here except for logging; the proto inspector tags these `WDGMSG_OUT` to make direction obvious.

Common inbound `WDGMSG` names (from grepping real recordings):

- `set` -- set a bar/fraction value.
- `tip` -- set a tooltip string.
- `tt` -- set tooltip RichText.
- `glut` -- set hunger/satiety values.
- `prog` -- set a progress fraction.
- `max` -- set a maximum.
- `err` -- display an error message.
- `sfx` -- play a sound effect.
- `pack` -- packed payload (widget-defined).
- `curs` / `cur` -- set cursor.
- `act` -- generic action hook.

Per-widget semantics are defined in each widget class; there's no single canonical dispatch table.

---

## 9. Protocol inspection and recording

The client ships a debug toolkit in `src/haven/proto/` that lets you watch, capture, and replay traffic.

### Live inspector

`ProtoInspector` (`src/haven/proto/ProtoInspector.java`) is an in-game `Hidewnd` that subscribes to `ProtoBus` and renders every decoded event in a scrolling list with search, pause, category filters (Widget/Object/Map/Session/Glob/Resource), direction filters (In/Out), and noise filters for the highest-volume event classes (Movement-only OBJDATA, stat-bar WDGMSGs -- see section 10).

Under the hood:

1. Every transport callback (`Connection.Main.gotrel`, `gotobjdata`, `gotmapdata`) fans the message out to all registered callbacks (`Connection.java:486-489`, `535-538`, `576-579`). `ProtoBus` is one of those callbacks.
2. `ProtoBus.handle(PMessage)` calls `ProtoDecoder.decodeRel` and pushes the resulting `ProtoEvent` onto an unbounded lock-free queue.
3. A UI tick drains the queue into a per-`ProtoBus` ring buffer (`ProtoBus.drainToUI`) and notifies subscribed UI listeners.
4. Decoding is **opportunistic**: exceptions inside the decoder are swallowed so a bad message never breaks the protocol path.

### Recording formats

Two on-disk formats, written into `play/proto-recordings/`:

**`.rec` (text replay format)** -- what `Transport.Callback.Recorder` and `EnhancedRecorder` write. Each line is one event; lines beginning with `#` are comments. `Transport.Playback` (`src/haven/Transport.java:95-219`) reads these back. Line grammar:

```
<t>  close
<t>  rmsg  <type>  <bprint-encoded-bytes>
<t>  objd  <flags>  <gob>  <frame>  [<initframe>]  <type>:<bprint>  ...
<t>  map   <base64-bytes>
```

`flags` is a string of single characters: `i` = initframe, `v` = virtual, `o` = old, `d` = delete, `n` = none. `bprint` is a custom ASCII-safe encoding (see `Utils.bprint`). `EnhancedRecorder` additionally writes `# << TYPE summary` comments above each line so the file is self-describing.

**`.jsonl` (retroactive JSON log)** -- what `RetroCapture` (`src/haven/proto/RetroCapture.java`) dumps. A ring buffer (window size `CFG.PROTO_RETRO_WINDOW_SEC`, default tens of seconds to minutes; max `CFG.PROTO_RETRO_MAX_EVENTS`) records every decoded event. Dumping writes one JSON object per line:

```json
{"type":"header","dump_ts":...,"window_sec":300,"event_count":1025,...}
{"t":394.72,"rel":-32.99,"dir":"in","cat":"OBJECT","type":"OBJDATA",
 "tid":-1,"summary":"Gob 81359797 frame=20045521 OD_MOVE,OD_LINBEG,...",
 "detail":"...","size":40,"gob":81359797,"wid":-1}
```

Unlike `.rec`, jsonl is **lossy** -- it's the decoded view, not the raw bytes. You can't replay it through `Transport.Playback`. Use it for ad-hoc analysis (e.g. `python -c "import json; ..."` over the file) or when asking questions about widget/message flow.

Retro capture auto-dump can be enabled from the inspector; it flushes the window every `window_sec` to a new file and keeps running.

### Replaying a recording

```bash
java -Dhaven.record=/path/to/session.rec ... -jar hafen.jar
```
sets `Session.record` (`Session.java:40`) which attaches a fresh `Transport.Callback.Recorder` to every new session. To **play back** rather than record, you'd wire up a `Transport.Playback` via code (see `play()` in `Transport.java:141`) -- there's no CLI flag for playback out of the box.

---

## 10. Traffic characteristics

Across the eight retro captures currently in `play/proto-recordings/`, covering mixed gameplay (character movement, inventory interactions, chat, crafting, combat):

| Event type | Share of total events |
|---|---:|
| `OBJDATA` | 93.4% |
| `RMSG_WDGMSG` | 5.7% |
| `RMSG_NEWWDG` | 0.3% |
| `WDGMSG_OUT` | 0.2% |
| `RMSG_DSTWDG` | 0.2% |
| All other | < 0.2% each |

Within OBJDATA, the flag breakdown (per-attribute, so a single delta often contributes multiple flags):

| Flag | Count (of ~58k OBJDATAs) |
|---|---:|
| `OD_MOVE` | 57,824 |
| `OD_LINSTEP` | 56,963 |
| `OD_LINBEG` | 14,243 |
| `OD_CMPPOSE` | 10,560 |
| (none) | 475 |
| `OD_CMPEQU` | 558 |
| `OD_RES` | 452 |
| `OD_OVERLAY` | 177 |
| `OD_ICON` | 166 |
| `OD_HEALTH` | 104 |
| `OD_RESATTR` | 63 |
| `OD_COMPOSE` | 40 |
| `OD_CMPMOD` | 40 |
| `OD_ZOFF` | 39 |
| `OD_HOMING` | 5 |
| `OD_FOLLOW` | 2 |

Within `RMSG_WDGMSG`, the noisiest message names:

| Name | Share |
|---|---:|
| `glut` | 24.5% |
| `tip` | 23.6% |
| `set` | 23.5% |
| `tt` | 11.4% |
| `chres` | 7.0% |
| `prog` | 3.6% |
| `m` | 2.8% |
| `upd` | 1.3% |
| everything else | ~2% |

Takeaways:

- **Movement dominates**. The combination of `OD_MOVE` + `OD_LINSTEP` + `OD_LINBEG` covers almost every OBJDATA event; if you're reading a capture looking for "what actually happened", filter those out first. The inspector's **Movement** noise checkbox does exactly that (keeps OBJDATAs with `OD_CMPPOSE`, `OD_RES`, `OD_HEALTH`, `OD_OVERLAY`, etc. visible).
- **`OD_CMPPOSE` is frequent but interesting**. ~10k events in the sample set, but it signals pose/animation changes which are usually what you want to see -- do not treat it as noise.
- **Stat-bar widgets are the WDGMSG bulk**. `glut`, `tip`, `set`, `tt`, `prog`, `max` are the tooltips and gauges updating several times per second. The inspector's **Stat updates** noise checkbox drops them.
- With both noise filters on, ~80% of the traffic disappears, leaving the interaction events (widget creation, inventory moves, chat, overlays) in plain view.

---

## 11. Where to look in the code

Quick jumpable map for anyone debugging this layer:

- **Packet framing and reliability**: `src/haven/Connection.java` -- `Main.run` (`:693`), `handlemsg` (`:592`), `gotobjdata` (`:541`), `sendpending` (`:631`), `sendobjacks` (`:666`).
- **Message-type constants**: `src/haven/Session.java:44-59` for MSG_*, `src/haven/RMessage.java:30-47` for RMSG_*, `src/haven/OCache.java:39-60` for OD_*.
- **Primitive decoders**: `src/haven/Message.java` (base), `src/haven/MessageBuf.java` (read/write buffer), `src/haven/PMessage.java` (packet-level), `src/haven/RMessage.java` (reliable stream message).
- **Type-tagged list decoding**: `Message.tto0` (`src/haven/Message.java:272`), `Message.list` (`:351`).
- **Gob delta application**: `src/haven/OCache.java` -- handlers annotated `@DeltaType(OD_*)` (e.g. `:331` for `OD_MOVE`, `:358` for `OD_OVERLAY`, `:417` for `OD_RESATTR`).
- **Session-level dispatch**: `src/haven/Session.java` -- `handlerel` (`:323`), `conncb` (`:345`).
- **Map grid assembly**: `src/haven/MCache.java` -- search for `MSG_MAPREQ`, `mapdata`, `pktid`.
- **Protocol inspector / decoder**: `src/haven/proto/ProtoDecoder.java`, `ProtoBus.java`, `ProtoInspector.java`.
- **Recording & replay**: `src/haven/Transport.java` (Callback.Recorder `:45`, Playback `:95`), `src/haven/proto/EnhancedRecorder.java`, `src/haven/proto/RetroCapture.java`.

---

## 12. Debugging recipes

- **"What does the server send when I do X?"** -- open the inspector (bound via `ProtoInspector`), clear, perform X, inspect. If X is fast, enable Retro capture first, do X, hit "Dump now".
- **"I saw a weird event scroll by, can I find it again?"** -- enable Retro capture before the suspect event, then dump to jsonl after. Grep the file.
- **"What widget message triggered Y?"** -- filter to `Widget` category + `Out` direction to isolate client-originated wdgmsgs. Search for the widget id.
- **"Why is this gob doing that?"** -- use the "Gob inspector" panel (`GobInspectorPanel`), which is driven by the per-gob history accessor `ProtoBus.getHistoryForGob`.
- **"Is this truly the wire format or is the client transforming it?"** -- enable standard recording (`-Dhaven.record=file.rec`). `.rec` files carry the raw bprint/base64 bytes of every inbound message; compare those against your decoding assumption.
- **"I need to replay a bug"** -- the `.rec` files replay through `Transport.Playback` at the original pacing (see `Transport.java:141-214`). Playback stubs out the outbound side, so the client will see inbound traffic but can't send anything back -- good for rendering/deserialization bugs, not for round-trip protocol work.

---

## Appendix: endianness and string conventions

- All multi-byte integers are **little-endian**. `Message.int32`/`uint16`/etc. read LE.
- Strings are **null-terminated UTF-8**. `Message.string` reads until `0x00`.
- Coordinates (`Coord`): `int32 x, int32 y`. World units are integers at the server layer; the client scales them to float metres via `OCache.posres = (1/1024, 1/1024) * (11,11)` (`OCache.java:62`).
- Booleans: always packed into bitflags of a `uint8`; there is no dedicated bool type.
- Sentinel values: `65535` for "nil resource id" (16-bit), `0xffffffff` for "nil gob id" (32-bit), `-1` (`0xff...`) often means "end of list" in OD payloads.
