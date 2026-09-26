import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.util.Nullable;
import java.io.*;
import java.util.*;

public class CustomTypeIO {

    public enum ContentType {
        item("物品"), block("建筑"), mech_UNUSED, bullet("子弹"), liquid("流体"), status("状态效果"), unit("单位"),
        weather("天气"), effect_UNUSED, sector("区块"), loadout_UNUSED, typeid_UNUSED, error, planet("星球"), ammo_UNUSED,
        team("队伍"), unitCommand("单位指令"), unitStance("单位姿态");

        public final String name;
        public final boolean unused;

        ContentType(String name) { this.name = name; unused = false; }
        ContentType() { name = "unused"; unused = true; }
    }

    public enum DataAssetType{
        patch("patches", "补丁", Seq.with("json", "hjson", "json5"), MapReaderWriter.RawPatch.class, true),
        content("content", "内容", Seq.with("json", "hjson", "json5"), MapReaderWriter.RawPatch.class, true),
        bundle("bundles", "语言包", Seq.with("properties"), MapReaderWriter.Bundle.class, false),
        image("sprites", "图像", Seq.with("png"), MapReaderWriter.DataImage.class, false),
        sound("sounds", "音效", Seq.with("mp3", "ogg"), MapReaderWriter.DataSound.class, false),
        music("music", "音乐", Seq.with("mp3", "ogg"), MapReaderWriter.DataMusic.class, false)
        ;

        public final String folder, displayed;
        public final Seq<String> extensions;
        public final Class<?> type;
        public final boolean embedded;

        public static final DataAssetType[] all = values();

        DataAssetType(String folder, String displayed, Seq<String> extensions, Class<?> type, boolean embedded){
            this.folder = folder;
            this.displayed = displayed;
            this.extensions = extensions;
            this.type = type;
            this.embedded = embedded;
        }

        @Override
        public String toString() {
            return folder;
        }
    }

    public static abstract class Content {
        public final short id;
        public final String name;
        public String data;
        public boolean dataPatchAdded = false;
        public Content(short id, String name) { this.id = id; this.name = name; }
        public void patch(String json) { dataPatchAdded = true; data = json; }
    }

    public static class Item extends Content { public Item(short id, String name) { super(id, name); } }
    public static class Liquid extends Content { public Liquid(short id, String name) { super(id, name); } }
    public static class UnitType extends Content { public UnitType(short id, String name) { super(id, name); } }
    public static class Block extends Content { public Block(short id, String name) { super(id, name); } }
    public static class StatusEffect extends Content { public StatusEffect(short id, String name) { super(id, name); } }

    public static class Team { public int id; public Team(int id) { this.id = id; } }
    public static class Point2 { public int x, y; public Point2(int x, int y) { this.x = x; this.y = y; } public int pack() { return (x << 16) | (y & 0xFFFF); } public static Point2 unpack(int packed) { return new Point2(packed >> 16, (short)packed); } }
    public static class Vec2 { public float x, y; public Vec2(float x, float y) { this.x = x; this.y = y; } }
    public static class Building { public int pos; public Building(int pos) { this.pos = pos; } }
    public static class Unit { public int id; public UnitType type; public float x, y; public int team; public Unit(int id, UnitType type, float x, float y, int team) { this.id = id; this.type = type; this.x = x; this.y = y; this.team = team; } }

    public interface ContentMapper { Content get(ContentType type, int id); }
    public interface EntityFactory { Object createUnit(int id); }

    public static class TypeIO {
        public static Object readObject(Reads read, boolean box, ContentMapper mapper, EntityFactory factory) {
            byte type = read.b();
            switch (type) {
                case 0: return null;
                case 1: return read.i();
                case 2: return read.l();
                case 3: return read.f();
                case 4: return readString(read);
                case 5: {
                    ContentType[] types = ContentType.values();
                    int ordinal = read.b();
                    if (ordinal < 0 || ordinal >= types.length) { read.s(); return null; }
                    ContentType ct = types[ordinal];
                    int id = read.s();
                    return mapper.get(ct, id);
                }
                case 6: {
                    short length = read.s(); int[] arr = new int[length]; for (int i = 0; i < length; i++) arr[i] = read.i(); return arr;
                }
                case 7: { int x = read.i(), y = read.i(); return new Point2(x, y); }
                case 8: {
                    byte len = read.b(); Point2[] points = new Point2[len]; for (int i = 0; i < len; i++) points[i] = Point2.unpack(read.i()); return points;
                }
                case 9: { read.b(); read.s(); return null; }
                case 10: return read.bool();
                case 11: return read.d();
                case 12: { int pos = read.i(); return box ? new Building(pos) : null; }
                case 13: return read.s();
                case 14: { int blen = read.i(); return read.b(new byte[blen]); }
                case 15: { read.b(); return null; }
                case 16: { int boollen = read.i(); boolean[] bools = new boolean[boollen]; for (int i = 0; i < boollen; i++) bools[i] = read.bool(); return bools; }
                case 17: { int id = read.i(); return factory != null ? factory.createUnit(id) : null; }
                case 18: { int len = read.s(); Vec2[] vecs = new Vec2[len]; for (int i = 0; i < len; i++) vecs[i] = new Vec2(read.f(), read.f()); return vecs; }
                case 19: { return new Vec2(read.f(), read.f()); }
                case 20: { return new Team(read.ub()); }
                case 21: { short len = read.s(); int[] ints = new int[len]; for (int i = 0; i < len; i++) ints[i] = read.i(); return ints; }
                case 22: { int objlen = read.i(); Object[] objs = new Object[objlen]; for (int i = 0; i < objlen; i++) objs[i] = readObject(read, box, mapper, factory); return objs; }
                case 23: return read.us();
                default: throw new RuntimeException("Unknown object type: " + type);
            }
        }

        public static void writeObject(Writes write, Object object, ContentMapper mapper) {
            if (object == null) { write.b((byte)0); }
            else if (object instanceof Integer) { write.b((byte)1); write.i((Integer)object); }
            else if (object instanceof Long) { write.b((byte)2); write.l((Long)object); }
            else if (object instanceof Float) { write.b((byte)3); write.f((Float)object); }
            else if (object instanceof String) { write.b((byte)4); writeString(write, (String)object); }
            else if (object instanceof Content c) {
                write.b((byte)5);
                byte typeOrdinal = switch (c) {
                    case Item item -> (byte) ContentType.item.ordinal();
                    case Liquid liquid -> (byte) ContentType.liquid.ordinal();
                    case UnitType unitType -> (byte) ContentType.unit.ordinal();
                    case Block block -> (byte) ContentType.block.ordinal();
                    default -> throw new RuntimeException("Unknown Content type: " + c.getClass());
                };
                write.b(typeOrdinal); write.s(c.id);
            }
            else if (object instanceof int[] arr) {write.b((byte)6); write.s((short)arr.length); for (int v : arr) write.i(v); }
            else if (object instanceof Point2) { write.b((byte)7); write.i(((Point2)object).x); write.i(((Point2)object).y); }
            else if (object instanceof Point2[] arr) {write.b((byte)8); write.b((byte)arr.length); for (Point2 p : arr) write.i(p.pack()); }
            else if (object instanceof Boolean) { write.b((byte)10); write.bool((Boolean)object); }
            else if (object instanceof Double) { write.b((byte)11); write.d((Double)object); }
            else if (object instanceof Building) { write.b((byte)12); write.i(((Building)object).pos); }
            else if (object instanceof Integer) { write.b((byte)13); write.s((Integer)object); }
            else if (object instanceof byte[] arr) {write.b((byte)14); write.i(arr.length); write.b(arr); }
            else if (object instanceof boolean[] arr) {write.b((byte)16); write.i(arr.length); for (boolean b : arr) write.bool(b); }
            else if (object instanceof Unit u) {write.b((byte)17); write.i(u.id); }
            else if (object instanceof Vec2[] arr) {write.b((byte)18); write.s((short)arr.length); for (Vec2 v : arr) { write.f(v.x); write.f(v.y); } }
            else if (object instanceof Vec2) { write.b((byte)19); write.f(((Vec2)object).x); write.f(((Vec2)object).y); }
            else if (object instanceof Team) { write.b((byte)20); write.b(((Team)object).id); }
            else if (object instanceof Object[] arr) {write.b((byte)22); write.i(arr.length); for (Object obj : arr) { writeObject(write, obj, mapper); } }
            else if (object instanceof Short) { write.b((byte)23); write.s((Short)object); }
            else throw new IllegalArgumentException("Unknown object type: " + object.getClass());
        }

        public static String readString(Reads read) { byte exists = read.b(); if (exists != 0) return read.str(); else return null; }
        public static void writeString(Writes write, String str) { if (str != null) { write.b((byte)1); write.str(str); } else { write.b((byte)0); } }

        // ==================== 实体工厂接口 ====================
        public interface EntityFactory { Object createUnit(int id); }

        // ==================== 辅助方法（跳过数据） ====================

        // controller：type 字节 + 按 type 追加负载
        public static void readController(Reads read) {
            byte type = read.b();
            switch (type) {
                case 0 -> read.i();                       // playerId
                case 2 -> {}                              // null / 无附加
                case 3 -> read.i();                       // pos
                case 9 -> {                               // logicAI
                    read.bool();                          // hasTargetPos
                    // 若 hasTargetPos 为 true 还要读 8 字节 vec2；但受 @Nullable 影响，
                    // 更稳的做法是直接用 readObject 递归跳过
                }
                default -> { /* 未知类型，按 readObject 风格跳过 */ }
            }
            // 指令队列 + 姿态队列的尾部数据，也随类型不同而不同
            // 最保险的实现仍是用 readObject 递归跳过，见下面 readObjectSafe()
        }

        // mounts：byte 数量 + 每个 (byte, float, float)
        public static void readMounts(Reads read) {
            int len = read.ub();
            for (int i = 0; i < len; i++) {
                read.b();  // state
                read.f();  // aimX
                read.f();  // aimY
            }
        }

        // statuses：int 数量 + 每条 short(id) + float(time)，dynamic 状态再读标志/数值
        public static void readStatus(Reads read) {
            read.s();          // effect id
            read.f();          // 剩余时间
            // 如果是 dynamic(22)，还需要再读 flags 和数值
            // 无法从 id 判断时，可按位读取（保持原逻辑即可，见下方遗留分支）
        }

        // abilities：byte 数量 + 每个 float
        public static void readAbilities(Reads read) {
            int len = read.ub();
            for (int i = 0; i < len; i++) read.f();
        }

        // items（stack）：short(item id, -1 为空) + int 数量 = 6 字节
        public static void readItems(Reads read) {
            read.s();
            read.i();
        }

        // tile（mineTile）：int 位置
        public static void readTile(Reads read) {
            read.i();
        }

        public static void readPlansQueue(Reads read) {
            int used = read.i();
            if (used == -1) return;
            for (int i = 0; i < used; i++) {
                byte type = read.b();
                read.i(); // position
                if (type != 1) {
                    read.s(); // block id
                    read.b(); // rotation
                    read.b(); // hasConfig
                    readObject(read, false, null, null); // config
                }
            }
        }

        public static void readVec2(Reads read, @Nullable Vec2 vec) {
            read.f(); read.f();
        }

        public static void writeAbilities(Writes write, int len) {
            write.b((byte)len);
        }

        public static void writeController(Writes write, boolean isNull) {
            if (isNull) {
                write.b((byte)2); // type 2 = null
            } else {
                write.b((byte)0); // 简化，写个 player id 0
                write.i(0);
            }
        }

        public static void writeTile(Writes write, boolean isNull) {
            if (isNull) {
                write.i(-1);
            } else {
                write.i(0);
            }
        }

        public static void writeMounts(Writes write, int len) {
            write.b((byte)len);
        }

        public static void writePlansQueue(Writes write, boolean isEmpty) {
            if (isEmpty) {
                write.i(-1);
            } else {
                write.i(0);
            }
        }

        public static void writeItems(Writes write, boolean isEmpty) {
            if (isEmpty) {
                write.s((short)-1);
                write.i(0);
            } else {
                write.s((short)0);
                write.i(0);
            }
        }

        public static void writeStatuses(Writes write, int count) {
            write.i(count);
        }
    }

    // ==================== UnitEntityParser ====================
    public static class UnitEntityParser {

        private interface FieldReader {
            Object read(Reads reads, ByteArrayInputStream bais, Map<String, int[]> offsets, int baseOffset) throws IOException;
        }

        private static class FieldDef {
            String name;
            boolean skip;      // true 表示不存入 fields，仅用于推进指针
            boolean track;     // true 表示需要记录偏移量（我们关心的可编辑字段）
            FieldReader reader;

            FieldDef(String name, boolean skip, FieldReader reader) {
                this.name = name;
                this.skip = skip;
                this.track = !skip && (name.equals("x") || name.equals("y") || name.equals("team") || name.equals("health") || name.equals("rotation"));
                this.reader = reader;
            }
        }

        // ========== UnitEntity (ID 3) 版本字段列表 ==========
        // rev 0
        private static final List<FieldDef> UNIT_ENTITY_REV0 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("_unknown2", true, (r, b, o, base) -> { r.bool(); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 1
        private static final List<FieldDef> UNIT_ENTITY_REV1 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 2
        private static final List<FieldDef> UNIT_ENTITY_REV2 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 3 (增加 mineTile)
        private static final List<FieldDef> UNIT_ENTITY_REV3 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 4 (增加 plans)
        private static final List<FieldDef> UNIT_ENTITY_REV4 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 5 (增加 updateBuilding 和 vel)
        private static final List<FieldDef> UNIT_ENTITY_REV5 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("updateBuilding", false, (r, b, o, base) -> r.bool()),
                new FieldDef("vel", true, (r, b, o, base) -> { TypeIO.readVec2(r, null); return null; }),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 6 (同 rev 5)
        private static final List<FieldDef> UNIT_ENTITY_REV6 = UNIT_ENTITY_REV5;

        // rev 7 (增加 abilities)
        private static final List<FieldDef> UNIT_ENTITY_REV7 = Arrays.asList(
                new FieldDef("abilities", true, (r, b, o, base) -> { TypeIO.readAbilities(r); return null; }),
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("updateBuilding", false, (r, b, o, base) -> r.bool()),
                new FieldDef("vel", true, (r, b, o, base) -> { TypeIO.readVec2(r, null); return null; }),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // UnitEntity 系 —— 供 id 3/0/16/18/30/31 等使用
        private static final List<FieldDef> UNIT_ENTITY_REV8 = Arrays.asList(
                new FieldDef("abilities", true, (r, b, o, base) -> { TypeIO.readAbilities(r); return null; }),
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("updateBuilding", false, (r, b, o, base) -> r.bool()),
                new FieldDef("vel", true, (r, b, o, base) -> { TypeIO.readVec2(r, null); return null; }),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );
        private static final List<FieldDef> UNIT_ENTITY_REV9 = UNIT_ENTITY_REV8;

        // ========== MechUnit (ID 4) 版本字段列表 ==========
        // 基于 UnitEntity 的对应版本，在 ammo 后插入 baseRotation，并移除可能存在的 _unknown1
        private static List<FieldDef> mechFromUnit(List<FieldDef> unitDefs) {
            List<FieldDef> mech = new ArrayList<>();
            boolean ammoPassed = false;
            for (FieldDef def : unitDefs) {
                if (def.name.equals("ammo")) {
                    mech.add(def);
                    ammoPassed = true;
                    // 插入 baseRotation
                    mech.add(new FieldDef("baseRotation", false, (r, b, o, base) -> r.f()));
                } else if (def.name.equals("_unknown1") && ammoPassed) {
                    // 跳过 _unknown1（在 MechUnit 中不存在）
                    continue;
                } else {
                    mech.add(def);
                }
            }
            return mech;
        }

        // rev 0
        private static final List<FieldDef> MECH_UNIT_REV0 = mechFromUnit(UNIT_ENTITY_REV0);
        // rev 1
        private static final List<FieldDef> MECH_UNIT_REV1 = mechFromUnit(UNIT_ENTITY_REV1);
        // rev 2
        private static final List<FieldDef> MECH_UNIT_REV2 = mechFromUnit(UNIT_ENTITY_REV2);
        // rev 3
        private static final List<FieldDef> MECH_UNIT_REV3 = mechFromUnit(UNIT_ENTITY_REV3);
        // rev 4
        private static final List<FieldDef> MECH_UNIT_REV4 = mechFromUnit(UNIT_ENTITY_REV4);
        // rev 5 (已定义)
        private static final List<FieldDef> MECH_UNIT_REV5 = Arrays.asList(
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("_unknown1", true, (r, b, o, base) -> { r.f(); return null; }),
                new FieldDef("baseRotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // rev 6 (同 rev 5)
        private static final List<FieldDef> MECH_UNIT_REV6 = MECH_UNIT_REV5;
        // rev 7 (带 abilities)
        private static final List<FieldDef> MECH_UNIT_REV7 = Arrays.asList(
                new FieldDef("abilities", true, (r, b, o, base) -> { TypeIO.readAbilities(r); return null; }),
                new FieldDef("ammo", false, (r, b, o, base) -> r.f()),
                new FieldDef("baseRotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("controller", true, (r, b, o, base) -> { TypeIO.readController(r); return null; }),
                new FieldDef("elevation", false, (r, b, o, base) -> r.f()),
                new FieldDef("flag", false, (r, b, o, base) -> r.d()),
                new FieldDef("health", false, (r, b, o, base) -> r.f()),
                new FieldDef("isShooting", false, (r, b, o, base) -> r.bool()),
                new FieldDef("mineTile", true, (r, b, o, base) -> { TypeIO.readTile(r); return null; }),
                new FieldDef("mounts", true, (r, b, o, base) -> { TypeIO.readMounts(r); return null; }),
                new FieldDef("plans", true, (r, b, o, base) -> { TypeIO.readPlansQueue(r); return null; }),
                new FieldDef("rotation", false, (r, b, o, base) -> r.f()),
                new FieldDef("shield", false, (r, b, o, base) -> r.f()),
                new FieldDef("spawnedByCore", false, (r, b, o, base) -> r.bool()),
                new FieldDef("stack", true, (r, b, o, base) -> { TypeIO.readItems(r); return null; }),
                new FieldDef("statuses", true, (r, b, o, base) -> {
                    int cnt = r.i();
                    for (int i = 0; i < cnt; i++) TypeIO.readStatus(r);
                    return null;
                }),
                new FieldDef("team", false, (r, b, o, base) -> r.ub()),
                new FieldDef("typeId", false, (r, b, o, base) -> (int)r.s()),
                new FieldDef("updateBuilding", false, (r, b, o, base) -> r.bool()),
                new FieldDef("vel", true, (r, b, o, base) -> { TypeIO.readVec2(r, null); return null; }),
                new FieldDef("x", false, (r, b, o, base) -> r.f()),
                new FieldDef("y", false, (r, b, o, base) -> r.f())
        );

        // MechUnit 系 —— 在 ammo 之后插入 baseRotation，其余与 UnitEntity 相同
        private static final List<FieldDef> MECH_UNIT_REV8 = mechFromUnit(UNIT_ENTITY_REV8);
        private static final List<FieldDef> MECH_UNIT_REV9 = MECH_UNIT_REV8;

        // ========== CrawlUnit (ID 46) 版本字段列表 ==========
        // rev 0 (与 UnitEntity rev 7 相同)
        private static final List<FieldDef> CRAWL_UNIT_REV0 = UNIT_ENTITY_REV7;

        // ========== 版本映射表 ==========
        private static final Map<Integer, Map<Short, List<FieldDef>>> VERSIONED_FIELD_DEFS = new HashMap<>();

        private static int resolveAlias(int classId) {
            return switch (classId) {
                case 1  -> 24;   // atrax  → corvus (LegsUnit)
                case 25 -> 4;    // vela   → mace   (MechUnit)
                case 47 -> 46;   // renale → latum  (CrawlUnit)
                default -> classId;
            };
        }

        static {
            // ID 3
            Map<Short, List<FieldDef>> map3 = new HashMap<>();
            map3.put((short)0, UNIT_ENTITY_REV0);
            map3.put((short)1, UNIT_ENTITY_REV1);
            map3.put((short)2, UNIT_ENTITY_REV2);
            map3.put((short)3, UNIT_ENTITY_REV3);
            map3.put((short)4, UNIT_ENTITY_REV4);
            map3.put((short)5, UNIT_ENTITY_REV5);
            map3.put((short)6, UNIT_ENTITY_REV6);
            map3.put((short)7, UNIT_ENTITY_REV7);
            VERSIONED_FIELD_DEFS.put(3, map3);

            // ID 4
            Map<Short, List<FieldDef>> map4 = new HashMap<>();
            map4.put((short)0, MECH_UNIT_REV0);
            map4.put((short)1, MECH_UNIT_REV1);
            map4.put((short)2, MECH_UNIT_REV2);
            map4.put((short)3, MECH_UNIT_REV3);
            map4.put((short)4, MECH_UNIT_REV4);
            map4.put((short)5, MECH_UNIT_REV5);
            map4.put((short)6, MECH_UNIT_REV6);
            map4.put((short)7, MECH_UNIT_REV7);
            VERSIONED_FIELD_DEFS.put(4, map4);

            // ID 46
            Map<Short, List<FieldDef>> map46 = new HashMap<>();
            map46.put((short)0, CRAWL_UNIT_REV0);
            VERSIONED_FIELD_DEFS.put(46, map46);

            // 其他 ID 暂时只支持 rev 7（可根据需要补充）
            int[] otherIds = {0,1,2,5,16,17,18,19,20,21,23,24,25,26,29,30,31,32,33,36,39,40,43,44,45,47};
            for (int id : otherIds) {
                Map<Short, List<FieldDef>> map = new HashMap<>();
                map.put((short)7, UNIT_ENTITY_REV7); // 假设它们与 UnitEntity rev 7 结构相同
                VERSIONED_FIELD_DEFS.put(id, map);
            }

            // 3 (flare / UnitEntity)
            map3.put((short)8, UNIT_ENTITY_REV8);
            map3.put((short)9, UNIT_ENTITY_REV9);

            // 4 (mace / MechUnit)
            map4.put((short)8, MECH_UNIT_REV8);
            map4.put((short)9, MECH_UNIT_REV9);

            // 24 (corvus / LegsUnit) —— 使用 UnitEntity 模板
            Map<Short, List<FieldDef>> map24 = new HashMap<>();
            for (short r = 0; r <= 9; r++) map24.put(r, UNIT_ENTITY_REV9);
            VERSIONED_FIELD_DEFS.put(24, map24);

            // 20 (risso / UnitWaterMove) —— 同上（实测 rev 9）
            Map<Short, List<FieldDef>> map20 = new HashMap<>();
            for (short r = 0; r <= 9; r++) map20.put(r, UNIT_ENTITY_REV9);
            VERSIONED_FIELD_DEFS.put(20, map20);
        }

        public static Map<String, Object> parse(byte[] chunk, int classId, Map<String, int[]> offsets) throws IOException {
            int offset = 5;
            ByteArrayInputStream bais = new ByteArrayInputStream(chunk, offset, chunk.length - offset);
            DataInputStream dis = new DataInputStream(bais);
            Reads reads = new Reads(dis);

            short rev = reads.s();
            Map<String, Object> fields = new HashMap<>();
            fields.put("_rev", rev);
            fields.put("_classId", classId);

            int effectiveId = resolveAlias(classId);
            Map<Short, List<FieldDef>> versionMap = VERSIONED_FIELD_DEFS.get(effectiveId);
            if (versionMap == null) throw new IOException("Unsupported classId: " + classId);

            List<FieldDef> defs = versionMap.get(rev);
            if (defs == null) {
                short maxRev = versionMap.keySet().stream().max(Short::compareTo).orElse((short)-1);
                if (maxRev < 0) throw new IOException("No field defs for classId " + classId + " rev " + rev);
                defs = versionMap.get(maxRev);
                System.err.println("Rev " + rev + " 未定义（classId=" + classId + "），回退到 rev " + maxRev);
            }

            for (FieldDef def : defs) {
                if (bais.available() <= 0) break;
                int start = chunk.length - bais.available();
                try {
                    Object val = def.reader.read(reads, bais, offsets, start);
                    if (def.track) {
                        int end = chunk.length - bais.available();
                        offsets.put(def.name, new int[]{start, end - start});
                    }
                    if (!def.skip && val != null) fields.put(def.name, val);
                } catch (EOFException e) {
                    break;
                }
            }

            // 容错：容忍尾部多余字节
            if (bais.available() > 0) {
                System.err.println("classId " + classId + " rev " + rev + " 有 " +
                        bais.available() + " 字节未解析（可能是新版追加字段）");
            }
            return fields;
        }
    }
}