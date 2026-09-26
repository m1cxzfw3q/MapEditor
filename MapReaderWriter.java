import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.UBJsonReader;
import arc.util.serialization.UBJsonWriter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class MapReaderWriter {
    private static final Map<Integer, String> ENTITY_ID_TO_NAME = new HashMap<>();

    public static final Seq<String> LANGUAGES = Seq.with(
            "en", "be", "bg", "ca", "cs", "da", "de", "es", "et", "eu", "fi", "fil", "fr", "hu", "id_ID",
            "it", "ja", "ko", "lt", "nl", "nl_BE", "pl", "pt_BR", "pt_PT", "ro", "ru", "sr", "sv", "th",
            "tk", "tr", "uk_UA", "vi", "zh_CN", "zh_TW"
    );

    static {// 从 EntityMapping 中提取
        ENTITY_ID_TO_NAME.put(0, "alpha");
        ENTITY_ID_TO_NAME.put(1, "atrax");
        ENTITY_ID_TO_NAME.put(2, "block");
        ENTITY_ID_TO_NAME.put(3, "UnitEntity");  // 通用单位类
        ENTITY_ID_TO_NAME.put(4, "MechUnit");
        ENTITY_ID_TO_NAME.put(5, "PayloadUnit");
        ENTITY_ID_TO_NAME.put(6, "Building");
        ENTITY_ID_TO_NAME.put(7, "Bullet");
        ENTITY_ID_TO_NAME.put(8, "Decal");
        ENTITY_ID_TO_NAME.put(9, "EffectState");
        ENTITY_ID_TO_NAME.put(10, "Fire");
        ENTITY_ID_TO_NAME.put(11, "LaunchCore");
        ENTITY_ID_TO_NAME.put(12, "Player");
        ENTITY_ID_TO_NAME.put(13, "Puddle");
        ENTITY_ID_TO_NAME.put(14, "WeatherState");
        ENTITY_ID_TO_NAME.put(15, "LaunchPayload");
        ENTITY_ID_TO_NAME.put(16, "mono");
        ENTITY_ID_TO_NAME.put(17, "nova");
        ENTITY_ID_TO_NAME.put(18, "poly");
        ENTITY_ID_TO_NAME.put(19, "pulsar");
        ENTITY_ID_TO_NAME.put(20, "UnitWaterMove");
        ENTITY_ID_TO_NAME.put(21, "spiroct");
        ENTITY_ID_TO_NAME.put(22, "???"); // 预留
        ENTITY_ID_TO_NAME.put(23, "quad");
        ENTITY_ID_TO_NAME.put(24, "LegsUnit");
        ENTITY_ID_TO_NAME.put(25, "vela");
        ENTITY_ID_TO_NAME.put(26, "oct");
        ENTITY_ID_TO_NAME.put(27, "PosTeam");
        ENTITY_ID_TO_NAME.put(28, "PosTeamDef");
        ENTITY_ID_TO_NAME.put(29, "arkyid");
        ENTITY_ID_TO_NAME.put(30, "beta");
        ENTITY_ID_TO_NAME.put(31, "gamma");
        ENTITY_ID_TO_NAME.put(32, "quasar");
        ENTITY_ID_TO_NAME.put(33, "toxopid");
        ENTITY_ID_TO_NAME.put(34, "LargeLaunchPayload");
        ENTITY_ID_TO_NAME.put(35, "WorldLabel");
        ENTITY_ID_TO_NAME.put(36, "BuildingTetherPayloadUnit");
        ENTITY_ID_TO_NAME.put(37, "timedDef");
        ENTITY_ID_TO_NAME.put(38, "timed");
        ENTITY_ID_TO_NAME.put(39, "missile");
        ENTITY_ID_TO_NAME.put(40, "vanquish");
        ENTITY_ID_TO_NAME.put(41, "PowerGraphComp");
        ENTITY_ID_TO_NAME.put(42, "PowerGraphUpdater");
        ENTITY_ID_TO_NAME.put(43, "stell");
        ENTITY_ID_TO_NAME.put(44, "osc");
        ENTITY_ID_TO_NAME.put(45, "elude");
        ENTITY_ID_TO_NAME.put(46, "latum");
        ENTITY_ID_TO_NAME.put(47, "renale");
    }

    private static String[] getRegionNames(int version) {
        if (version >= 12) {
            // SaveVersion.write: meta, patches, content, map, entities, markers, custom
            return new String[]{"meta", "patches", "content", "map", "entities", "markers", "custom"};
        } else if (version == 11) {
            // Save11.write: meta, content, patches, map, entities, markers, custom
            return new String[]{"meta", "content", "patches", "map", "entities", "markers", "custom"};
        } else if (version >= 8) {
            // Save8+: meta, content, map, entities, markers, custom
            return new String[]{"meta", "content", "map", "entities", "markers", "custom"};
        } else {
            return new String[]{"meta", "content", "map", "entities", "custom"};
        }
    }

    public static class EntityMappingEntry {
        public short id;
        public String name;
        public EntityMappingEntry(short id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class TeamBlockPlan {
        public short x, y, rotation, blockId;
        public int team;          // 新增队伍ID
        public Object config;
        public TeamBlockPlan(int team, short x, short y, short rotation, short blockId, Object config) {
            this.team = team;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.blockId = blockId;
            this.config = config;
        }
    }

    private static final CustomTypeIO.ContentMapper DUMMY_MAPPER = (type, id) -> {
        // 返回一个虚拟的 Content 对象，仅包含 ID 和类型信息
        return new CustomTypeIO.Content((short) id, type.name() + "_" + id) {};
    };

    public static class EntityInfo {
        public int id;
        public String type;
        public float x, y;
        public int team;
        public byte[] rawChunk;
        public Map<String, Object> fields;          // 保留原始字段值
        public Map<String, int[]> fieldOffsets;     // 新增：字段起始索引和长度

        public EntityInfo(int id, String type, float x, float y, int team, byte[] rawChunk, Map<String, Object> fields, Map<String, int[]> fieldOffsets) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.team = team;
            this.rawChunk = rawChunk;
            this.fields = fields;
            this.fieldOffsets = fieldOffsets;
        }

        // 根据偏移量修改位置
        public void setPosition(float newX, float newY) {
            if (fieldOffsets == null) return;
            int[] xOffset = fieldOffsets.get("x");
            int[] yOffset = fieldOffsets.get("y");
            if (xOffset != null && yOffset != null) {
                int xBits = Float.floatToIntBits(newX);
                int yBits = Float.floatToIntBits(newY);
                rawChunk[xOffset[0]]   = (byte)(xBits >> 24);
                rawChunk[xOffset[0]+1] = (byte)(xBits >> 16);
                rawChunk[xOffset[0]+2] = (byte)(xBits >> 8);
                rawChunk[xOffset[0]+3] = (byte)xBits;
                rawChunk[yOffset[0]]   = (byte)(yBits >> 24);
                rawChunk[yOffset[0]+1] = (byte)(yBits >> 16);
                rawChunk[yOffset[0]+2] = (byte)(yBits >> 8);
                rawChunk[yOffset[0]+3] = (byte)yBits;
                this.x = newX;
                this.y = newY;
            }
        }

        // 根据偏移量修改队伍
        public void setTeam(int newTeam) {
            if (fieldOffsets == null) return;
            int[] teamOffset = fieldOffsets.get("team");
            if (teamOffset != null) {
                rawChunk[teamOffset[0]] = (byte)newTeam;
                this.team = newTeam;
            }
        }

        // 根据偏移量修改健康值
        public void setHealth(float newHealth) {
            if (fieldOffsets == null) return;
            int[] healthOffset = fieldOffsets.get("health");
            if (healthOffset != null) {
                int bits = Float.floatToIntBits(newHealth);
                rawChunk[healthOffset[0]]   = (byte)(bits >> 24);
                rawChunk[healthOffset[0]+1] = (byte)(bits >> 16);
                rawChunk[healthOffset[0]+2] = (byte)(bits >> 8);
                rawChunk[healthOffset[0]+3] = (byte)bits;
                if (fields != null) fields.put("health", newHealth);
            }
        }

        // 根据偏移量修改旋转
        public void setRotation(float newRot) {
            if (fieldOffsets == null) return;
            int[] rotOffset = fieldOffsets.get("rotation");
            if (rotOffset != null) {
                int bits = Float.floatToIntBits(newRot);
                rawChunk[rotOffset[0]]   = (byte)(bits >> 24);
                rawChunk[rotOffset[0]+1] = (byte)(bits >> 16);
                rawChunk[rotOffset[0]+2] = (byte)(bits >> 8);
                rawChunk[rotOffset[0]+3] = (byte)bits;
                if (fields != null) fields.put("rotation", newRot);
            }
        }
    }

    public static class MapData {
        public String headerStr;
        public byte[] headerBytes;
        public int version;
        public List<String> regionNames = new ArrayList<>();
        public List<Integer> regionSizes = new ArrayList<>();
        public List<byte[]> regionData = new ArrayList<>();
        public Map<String, String> meta = new LinkedHashMap<>();
        public Map<CustomTypeIO.DataAssetType, List<Object>> patches = new LinkedHashMap<>(); //new data patch system
        public JsonValue markersJson;
        public List<EntityMappingEntry> entityMapping = new ArrayList<>();
        public List<TeamBlockPlan> teamBlocks = new ArrayList<>();
        public List<EntityInfo> entities = new ArrayList<>();

        // 存储原始字节片段
        public byte[] originalMappingBytes;
        public byte[] originalTeamBlocksBytes;
        public byte[] rawPatchesBytes;   // v13 时原样保留

        public byte[] getRegionData(String name) {
            int idx = regionNames.indexOf(name);
            return idx >= 0 ? regionData.get(idx) : null;
        }

        public void setRegionData(String name, byte[] newData) {
            int idx = regionNames.indexOf(name);
            if (idx >= 0) {
                regionSizes.set(idx, newData.length);
                regionData.set(idx, newData);
            }
        }

        public void updateMeta(String key, String value) {
            meta.put(key, value);
        }

        public void setPatches(Map<CustomTypeIO.DataAssetType, List<Object>> newPatches) {
            patches.clear();
            if (newPatches != null) patches.putAll(newPatches);
        }

        public void clearEntities() {
            int idx = regionNames.indexOf("entities");
            if (idx >= 0) {
                regionSizes.set(idx, 0);
                regionData.set(idx, new byte[0]);
                entityMapping.clear();
                teamBlocks.clear();
                entities.clear();
                originalMappingBytes = null;
                originalTeamBlocksBytes = null;
            }
        }

        public void rebuildRegions() throws IOException {
            int metaIdx = regionNames.indexOf("meta");
            if (metaIdx >= 0) {
                byte[] newMeta = buildMetaBytes(meta);
                regionSizes.set(metaIdx, newMeta.length);
                regionData.set(metaIdx, newMeta);
            }

            int patchesIdx = regionNames.indexOf("patches");
            if (patchesIdx >= 0) {
                byte[] newPatches;
                if (version >= 13 && rawPatchesBytes != null) {
                    newPatches = rawPatchesBytes.clone();
                } else {
                    newPatches = buildPatchesBytes(version, patches);
                }
                regionSizes.set(patchesIdx, newPatches.length);
                regionData.set(patchesIdx, newPatches);
            }

            int markersIdx = regionNames.indexOf("markers");
            if (markersIdx >= 0) {
                if (markersJson == null) {
                    regionSizes.set(markersIdx, 0);
                    regionData.set(markersIdx, new byte[0]);
                } else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (UBJsonWriter writer = new UBJsonWriter(baos)) {
                        writer.value(markersJson);
                        writer.close();
                    }
                    byte[] newMarkers = baos.toByteArray();
                    regionSizes.set(markersIdx, newMarkers.length);
                    regionData.set(markersIdx, newMarkers);
                }
            }
        }

        private byte[] buildMetaBytes(Map<String, String> meta) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeShort(meta.size());
                for (Map.Entry<String, String> e : meta.entrySet()) {
                    dos.writeUTF(e.getKey());
                    dos.writeUTF(e.getValue());
                }
            }
            return baos.toByteArray();
        }

        private byte[] buildPatchesBytes(int mapVersion, Map<CustomTypeIO.DataAssetType, List<Object>> patches) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream stream = new DataOutputStream(baos)) {
                int[] unknownNameImgIdx = {0};
                if (mapVersion >= 12) stream.writeInt(2);

                if (mapVersion == 11){
                    List<Object> allPatch = patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>());
                    stream.writeByte(allPatch.size());
                    for (Object patch : allPatch) {
                        if (patch instanceof String p) {
                            byte[] b = p.getBytes("UTF-8");
                            stream.writeInt(b.length);
                            stream.write(b);
                        } else {
                            System.err.println("警告：版本 " + mapVersion + " 地图 patches 区域出现未知项，已自动跳过该项的写入操作。");
                        }
                    }
                } else if (mapVersion == 12) {
                    List<Object> allPatch = patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>());
                    for (Object patch : allPatch) {
                        if (patch instanceof String p) {
                            byte[] b = p.getBytes("UTF-8");
                            stream.writeInt(b.length);
                            stream.write(b);
                        } else {
                            System.err.println("警告：版本 " + mapVersion + " 地图 patches 补丁区域出现未知项，已自动跳过该项的写入操作。");
                        }
                    }

                    List<Object> images = patches.computeIfAbsent(CustomTypeIO.DataAssetType.image, k -> new ArrayList<>());
                    stream.writeByte(images.size());
                    for (Object image : images) {
                        if (image instanceof DataImage img) {
                            stream.writeUTF(img.name);
                            BufferedImage source = img.img;
                            // write width and height (compatibility layer)
                            stream.writeShort(source.getWidth());
                            stream.writeShort(source.getHeight());
                            writePatchesImage(source, stream);
                        } else if (image instanceof BufferedImage source) {
                            unknownNameImgIdx[0]++;
                            stream.writeUTF("nameless-image-" + unknownNameImgIdx[0]);
                            // write width and height (compatibility layer)
                            stream.writeShort(source.getWidth());
                            stream.writeShort(source.getHeight());
                            writePatchesImage(source, stream);
                        } else {
                            System.err.println("警告：版本 " + mapVersion + " 地图 patches 图片区域出现未知项，已自动跳过该项的写入操作。");
                        }
                    }
                } else if (mapVersion >= 13) {
                    int total = patches.values().stream().mapToInt(List::size).sum();
                    stream.writeInt(total);

                    for (Map.Entry<CustomTypeIO.DataAssetType, List<Object>> entry : patches.entrySet()) {
                        CustomTypeIO.DataAssetType type = entry.getKey();
                        List<Object> contents = entry.getValue();
                        if (contents == null) continue;

                        for (Object obj : contents) {
                            stream.writeByte(type.ordinal());
                            String path;
                            DataAsset asset = (obj instanceof DataAsset da) ? da : null;

                            // 决定 path
                            if (asset != null && asset.path != null) {
                                path = asset.path;
                            } else if (type == CustomTypeIO.DataAssetType.bundle && obj instanceof Bundle b) {
                                path = makeBundlePath(b.lang);
                            } else if (type == CustomTypeIO.DataAssetType.image && obj instanceof DataImage di) {
                                path = di.path != null ? di.path : (di.name + ".png");
                            } else if (type == CustomTypeIO.DataAssetType.patch) {
                                path = "patch-" + UUID.randomUUID() + ".json";
                            } else {
                                path = "asset-" + UUID.randomUUID();
                            }
                            stream.writeUTF(path);

                            boolean embed = (asset == null || asset.byteHash == null);
                            stream.writeBoolean(embed);

                            if (embed) {
                                writeEmbeddedAsset(stream, type, obj);
                            } else {
                                stream.write(asset.byteHash);
                            }
                        }
                    }
                }
            }
            return baos.toByteArray();
        }

        private void writePatchesImage(BufferedImage source, OutputStream stream) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(source, "png", baos);
                stream.write(baos.toByteArray());
            } catch (IOException e) {
                System.err.println("写入 patches 图片失败：" + e.getMessage());
            }
        }

        private void writeEmbeddedAsset(DataOutputStream stream, CustomTypeIO.DataAssetType type, Object obj) throws IOException {
            switch (type) {
                case patch -> {
                    byte[] bytes = (obj instanceof RawPatch rp && rp.rawBytes != null)
                            ? rp.rawBytes
                            : (obj instanceof String s ? s.getBytes("UTF-8") : new byte[0]);
                    stream.writeInt(bytes.length);
                    stream.write(bytes);
                }
                case content -> {
                    // ★ short typeOrdinal + int len + bytes
                    short ord = (obj instanceof RawPatch rp) ? rp.contentTypeOrdinal : 0;
                    stream.writeShort(ord);
                    byte[] bytes = (obj instanceof RawPatch rp && rp.rawBytes != null)
                            ? rp.rawBytes
                            : (obj instanceof String s ? s.getBytes("UTF-8") : new byte[0]);
                    stream.writeInt(bytes.length);
                    stream.write(bytes);
                }
                case bundle -> {
                    byte[] bytes = (obj instanceof Bundle b && b.rawBytes != null)
                            ? b.rawBytes
                            : ((obj instanceof Bundle b2 && b2.content != null)
                            ? b2.content.getBytes("UTF-8")
                            : new byte[0]);
                    stream.writeInt(bytes.length);
                    stream.write(bytes);
                }
                case image -> {
                    // ★ int len + bytes，没有 w/h
                    byte[] bytes = null;
                    if (obj instanceof DataImage di) {
                        if (di.rawBytes != null) {
                            bytes = di.rawBytes;
                        } else if (di.img != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(di.img, "png", baos);
                            bytes = baos.toByteArray();
                        }
                    }
                    if (bytes == null) bytes = new byte[0];
                    stream.writeInt(bytes.length);
                    stream.write(bytes);
                }
                case sound, music -> {
                    byte[] bytes = (obj instanceof DataAsset da && da.rawBytes != null) ? da.rawBytes : new byte[0];
                    stream.writeInt(bytes.length);
                    stream.write(bytes);
                }
            }
        }

        // 深拷贝
        public MapData deepCopy() {
            MapData copy = new MapData();
            copy.headerStr = this.headerStr;
            copy.headerBytes = this.headerBytes.clone();
            copy.version = this.version;
            copy.regionNames = new ArrayList<>(this.regionNames);
            copy.regionSizes = new ArrayList<>(this.regionSizes);
            copy.regionData = new ArrayList<>();
            for (byte[] data : this.regionData) {
                copy.regionData.add(data.clone());
            }
            copy.meta = new LinkedHashMap<>(this.meta);
            copy.patches = new LinkedHashMap<>(this.patches);
            if (this.markersJson != null) {
                JsonReader reader = new JsonReader();
                copy.markersJson = reader.parse(this.markersJson.toString());
            }
            copy.entityMapping = new ArrayList<>();
            for (EntityMappingEntry e : this.entityMapping) {
                copy.entityMapping.add(new EntityMappingEntry(e.id, e.name));
            }
            copy.teamBlocks = new ArrayList<>();
            for (TeamBlockPlan p : this.teamBlocks) {
                copy.teamBlocks.add(new TeamBlockPlan(p.team, p.x, p.y, p.rotation, p.blockId, p.config));
            }
            copy.entities = new ArrayList<>();
            for (EntityInfo e : this.entities) {
                copy.entities.add(new EntityInfo(e.id, e.type, e.x, e.y, e.team, e.rawChunk.clone(), e.fields, e.fieldOffsets));
            }
            copy.originalMappingBytes = this.originalMappingBytes == null ? null : this.originalMappingBytes.clone();
            copy.originalTeamBlocksBytes = this.originalTeamBlocksBytes == null ? null : this.originalTeamBlocksBytes.clone();
            copy.rawPatchesBytes = this.rawPatchesBytes == null ? null : this.rawPatchesBytes.clone();
            return copy;
        }

        public void rebuildEntities() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            Writes writes = new Writes(dos);

            // 1. 写入 mapping 部分
            writes.s((short) entityMapping.size());
            for (EntityMappingEntry e : entityMapping) {
                writes.s(e.id);
                writes.str(e.name);
            }

            // 2. 写入 team blocks 部分（按队伍分组）
            // 先统计有哪些队伍
            Map<Integer, List<TeamBlockPlan>> plansByTeam = new HashMap<>();
            for (TeamBlockPlan plan : teamBlocks) {
                plansByTeam.computeIfAbsent(plan.team, k -> new ArrayList<>()).add(plan);
            }
            writes.i(plansByTeam.size()); // teamCount
            for (Map.Entry<Integer, List<TeamBlockPlan>> entry : plansByTeam.entrySet()) {
                writes.i(entry.getKey()); // teamId
                List<TeamBlockPlan> plans = entry.getValue();
                writes.i(plans.size()); // planCount
                for (TeamBlockPlan plan : plans) {
                    writes.s(plan.x);
                    writes.s(plan.y);
                    writes.s(plan.rotation);
                    writes.s(plan.blockId);
                    // 写入 config（使用 CustomTypeIO.TypeIO.writeObject）
                    CustomTypeIO.TypeIO.writeObject(writes, plan.config, null);
                }
            }

            // 保存前自检：确认所有关键字段偏移量存在（x/y/team 至少要有）
            for (EntityInfo e : entities) {
                if (e.fieldOffsets == null || !e.fieldOffsets.containsKey("x") || !e.fieldOffsets.containsKey("y")) {
                    System.err.println("警告：实体 " + e.id + " 缺少 x/y 偏移量，写回后将不可编辑");
                }
            }

            // 3. 写入 world entities
            writes.i(entities.size());
            for (EntityInfo e : entities) {
                writes.i(e.rawChunk.length);
                writes.b(e.rawChunk);
            }

            byte[] newEntitiesData = baos.toByteArray();
            setRegionData("entities", newEntitiesData);
        }
    }

    public static MapData readMapData(File file) throws IOException {
        MapData data = new MapData();
        try (InputStream fis = Files.newInputStream(file.toPath());
             InflaterInputStream inflater = new InflaterInputStream(fis);
             DataInputStream dis = new DataInputStream(inflater)) {

            byte[] header = new byte[4];
            dis.readFully(header);
            data.headerStr = new String(header);
            data.headerBytes = header;
            int mapVersion = data.version = dis.readInt();
            String[] regionNames = getRegionNames(mapVersion);

            for (int idx = 0; idx < regionNames.length; idx++) {
                int size;
                try {
                    size = dis.readInt();
                } catch (EOFException e) {
                    throw new IOException("存档区域不完整：期望 " + regionNames.length +
                            " 个区域，实际读到 " + idx + " 个（已读 " + data.regionNames + "）", e);
                }
                byte[] buf = new byte[size];
                dis.readFully(buf);

                String name = regionNames[idx];
                DebugLog.log("[region] #%d name=%s size=%d", idx, name, size);

                data.regionNames.add(name);
                data.regionSizes.add(size);
                data.regionData.add(buf);

                switch (name) {
                    case "meta"    -> data.meta = parseStringMap(new DataInputStream(new ByteArrayInputStream(buf)));
                    case "patches" -> {if (size > 0) {
                        try { parsePatchesRegion(data, mapVersion, buf); }
                        catch (Exception e) { data.rawPatchesBytes = buf.clone();System.err.println("patches 解析失败: " + e.getMessage()); }
                    }}
                    case "markers" -> { if (size > 0) {
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(buf);
                             DataInputStream md = new DataInputStream(bais)) {
                            UBJsonReader reader = new UBJsonReader();
                            data.markersJson = reader.parseWihoutClosing(md);
                        } catch (Exception e) { System.err.println("markers 解析失败: " + e.getMessage()); }
                    }}
                    case "entities" -> { if (size > 0) {
                        try { parseEntitiesRegion(buf, data); }
                        catch (Exception e) { System.err.println("entities 解析失败: " + e.getMessage()); }
                    }}
                }
            }
            // 校验：确保所有区域数已到齐
            if (data.regionNames.size() != regionNames.length) {
                throw new IOException("存档区域数量不符：期望 " + regionNames.length +
                        "，实得 " + data.regionNames.size());
            }
        }
        return data;
    }
    private static void parseEntitiesRegion(byte[] buf, MapData data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        DataInputStream dis = new DataInputStream(bais);
        Reads reads = new Reads(dis);

        // 1. 解析 entity mapping
        int mappingStart = 0;
        short mapSize = reads.s();
        data.entityMapping.clear();
        for (int i = 0; i < mapSize; i++) {
            short id = reads.s();
            String name = reads.str();
            data.entityMapping.add(new EntityMappingEntry(id, name));
        }
        int mappingEnd = buf.length - bais.available();
        data.originalMappingBytes = Arrays.copyOfRange(buf, 0, mappingEnd);

        // 2. 解析 team blocks
        int teamStart = mappingEnd;
        int teamCount = reads.i();
        data.teamBlocks.clear();
        for (int t = 0; t < teamCount; t++) {
            int teamId = reads.i();
            int planCount = reads.i();
            for (int p = 0; p < planCount; p++) {
                short x = reads.s();
                short y = reads.s();
                short rot = reads.s();
                short blockId = reads.s();
                // 使用 DUMMY_MAPPER 代替 null
                Object config = CustomTypeIO.TypeIO.readObject(reads, false, DUMMY_MAPPER, null);
                data.teamBlocks.add(new TeamBlockPlan(teamId, x, y, rot, blockId, config));
            }
        }
        int teamEnd = buf.length - bais.available();
        data.originalTeamBlocksBytes = Arrays.copyOfRange(buf, teamStart, teamEnd);

        // 3. 解析 world entities
        int entityCount = reads.i();
        data.entities.clear();

        // 定义单位类ID集合（根据之前的 EntityMapping 和常见单位）
        Set<Integer> UNIT_CLASS_IDS = new HashSet<>(Arrays.asList(
                0, 1, 2, 3, 4, 5, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26, 29, 30, 31, 32, 33, 36,
                39, 43, 45, 46, 47, 49
        ));

        for (int i = 0; i < entityCount; i++) {
            int chunkLen = reads.i();
            if (chunkLen <= 0) continue;
            byte[] chunk = reads.b(chunkLen);
            if (chunk.length > 0) {
                int typeId = chunk[0] & 0xFF;
                int id = -1;
                if (chunk.length >= 5) {
                    id = ((chunk[1] & 0xFF) << 24) | ((chunk[2] & 0xFF) << 16) | ((chunk[3] & 0xFF) << 8) | (chunk[4] & 0xFF);
                }

                if (UNIT_CLASS_IDS.contains(typeId)) {
                    try {
                        Map<String, int[]> offsets = new HashMap<>();
                        Map<String, Object> fields = CustomTypeIO.UnitEntityParser.parse(chunk, typeId, offsets);
                        float x = (Float) fields.getOrDefault("x", 0f);
                        float y = (Float) fields.getOrDefault("y", 0f);
                        int team = (Integer) fields.getOrDefault("team", -1);
                        float health = (Float) fields.getOrDefault("health", 0f);
                        float rotation = (Float) fields.getOrDefault("rotation", 0f);
                        String typeName = ENTITY_ID_TO_NAME.getOrDefault(typeId, "Unit_" + typeId);
                        data.entities.add(new EntityInfo(id, typeName, x, y, team, chunk, fields, offsets));
                    } catch (Exception e) {
                        System.err.println("Failed to parse unit ID " + typeId + " (classId=" + typeId + "): " + e.getMessage());
                        e.printStackTrace(); // 打印堆栈，便于定位
                        fallbackParse(chunk, id, typeId, data);
                    }
                }
            }
        }
    }

    // 回退解析方法
    private static void fallbackParse(byte[] chunk, int id, int typeId, MapData data) {
        float x = 0, y = 0;
        if (chunk.length >= 13) {
            x = Float.intBitsToFloat(((chunk[5] & 0xFF) << 24) | ((chunk[6] & 0xFF) << 16) | ((chunk[7] & 0xFF) << 8) | (chunk[8] & 0xFF));
            y = Float.intBitsToFloat(((chunk[9] & 0xFF) << 24) | ((chunk[10] & 0xFF) << 16) | ((chunk[11] & 0xFF) << 8) | (chunk[12] & 0xFF));
        }
        String typeName = ENTITY_ID_TO_NAME.getOrDefault(typeId, "type_" + typeId);
        data.entities.add(new EntityInfo(id, typeName, x, y, -1, chunk, null, null));
    }

    public static void writeMapData(File file, MapData data) throws IOException {
        try (OutputStream fos = Files.newOutputStream(file.toPath());
             DeflaterOutputStream deflater = new DeflaterOutputStream(fos, new Deflater(Deflater.BEST_COMPRESSION));
             DataOutputStream dos = new DataOutputStream(deflater)) {

            dos.write(data.headerBytes);
            dos.writeInt(data.version);
            for (int i = 0; i < data.regionNames.size(); i++) {
                byte[] buf = data.regionData.get(i);
                dos.writeInt(buf.length);
                dos.write(buf);
            }
        }
    }

    private static Map<String, String> parseStringMap(DataInputStream dis) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        short size = dis.readShort();
        for (int i = 0; i < size; i++) {
            map.put(dis.readUTF(), dis.readUTF());
        }
        return map;
    }

    /*
    private static void parsePatchesRegion(MapData mapData, int mapVersion, byte[] data) throws IOException {
        try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(data))) {
            if (mapVersion >= 12) stream.readInt(); // patchFormatVersion

            if (mapVersion == 11) {
                int cnt = stream.readUnsignedByte();
                for (int i = 0; i < cnt; i++) {
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>())
                            .add(new String(b, "UTF-8"));
                }
            } else if (mapVersion == 12) {
                int patchAmount = stream.readInt();
                for (int i = 0; i < patchAmount; i++) {
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>())
                            .add(new String(b, "UTF-8"));
                }
                int imageAmount = stream.readInt();
                for (int i = 0; i < imageAmount; i++) {
                    String name = stream.readUTF();
                    stream.readShort(); // width（忽略）
                    stream.readShort(); // height（忽略）
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(b)) {
                        BufferedImage img = ImageIO.read(bais);
                        mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.image, k -> new ArrayList<>())
                                .add(new DataImage(img, name, name + ".png"));
                    } catch (IOException e) {
                        System.err.println("解析 patches 图片失败：" + e.getMessage());
                    }
                }
            } else if (mapVersion >= 13) {
                int total = stream.readInt();
                for (int i = 0; i < total; i++) {
                    byte typeId = stream.readByte();
                    if (typeId < 0 || typeId >= CustomTypeIO.DataAssetType.all.length) {
                        throw new IOException("Invalid DataAssetType: " + typeId);
                    }
                    CustomTypeIO.DataAssetType type = CustomTypeIO.DataAssetType.all[typeId];
                    String path = stream.readUTF();
                    boolean embedded = stream.readBoolean();

                    if (!embedded) {
                        byte[] hash = new byte[32];
                        stream.readFully(hash);
                        DataAsset asset = createEmptyAsset(type, path);
                        asset.byteHash = hash;
                        mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(asset);
                        continue;
                    }

                    switch (type) {
                        case patch -> {
                            // int len + bytes
                            int len = stream.readInt();
                            byte[] bytes = new byte[len];
                            stream.readFully(bytes);
                            RawPatch rp = new RawPatch(new String(bytes, "UTF-8"), bytes);
                            rp.path = path;
                            mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(rp);
                        }
                        case content -> {
                            // ★ short typeOrdinal + int len + bytes
                            short contentTypeOrdinal = stream.readShort();
                            int len = stream.readInt();
                            byte[] bytes = new byte[len];
                            stream.readFully(bytes);
                            RawPatch rp = new RawPatch(new String(bytes, "UTF-8"), bytes);
                            rp.path = path;
                            rp.contentTypeOrdinal = contentTypeOrdinal;   // ★ 保存以便无损写回
                            mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(rp);
                        }
                        case bundle -> {
                            // int len + bytes（继承 DataAsset.read）
                            int len = stream.readInt();
                            byte[] bytes = new byte[len];
                            stream.readFully(bytes);
                            String text = new String(bytes, "UTF-8");
                            Bundle b = new Bundle(extractLangFromBundlePath(path), text);
                            b.path = path;
                            b.rawBytes = bytes;
                            mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(b);
                        }
                        case image -> {
                            // ★ int len + bytes（不是 short w + short h + ...）
                            int len = stream.readInt();
                            byte[] bytes = new byte[len];
                            stream.readFully(bytes);
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                                BufferedImage img = ImageIO.read(bais);
                                String name = extractNameFromPath(path);
                                DataImage di = new DataImage(img, name, path);
                                di.rawBytes = bytes;
                                mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(di);
                            } catch (IOException e) {
                                System.err.println("解析 v13 图片失败: " + path + " - " + e.getMessage());
                            }
                        }
                        case sound, music -> {
                            // int len + bytes
                            int len = stream.readInt();
                            byte[] bytes = new byte[len];
                            stream.readFully(bytes);
                            DataAsset asset = (type == CustomTypeIO.DataAssetType.sound)
                                    ? new DataSound(extractNameFromPath(path), bytes)
                                    : new DataMusic(extractNameFromPath(path), bytes);
                            asset.path = path;
                            mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(asset);
                        }
                        default -> throw new IOException("Unhandled DataAssetType: " + type);
                    }
                }
                mapData.rawPatchesBytes = null;
            }
        } catch (Exception e) {
            // 解析失败：回退到原始字节保留
            System.err.println("patches 解析失败，将原样保留字节: " + e.getMessage());
            e.printStackTrace();
            mapData.rawPatchesBytes = data.clone();
            mapData.patches.clear();
        }
    }
     */

    private static void parsePatchesRegion(MapData mapData, int mapVersion, byte[] data) throws IOException {
        try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(data))) {
            if (mapVersion >= 12) {
                stream.available();
                int fmtVersion = stream.readInt();
                DebugLog.log("[patches] formatVersion=%d, regionLen=%d", fmtVersion, data.length);
            }

            if (mapVersion == 11) {
                int cnt = stream.readUnsignedByte();
                for (int i = 0; i < cnt; i++) {
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>())
                            .add(new String(b, "UTF-8"));
                }
            } else if (mapVersion == 12) {
                int patchAmount = stream.readInt();
                for (int i = 0; i < patchAmount; i++) {
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.patch, k -> new ArrayList<>())
                            .add(new String(b, "UTF-8"));
                }
                int imageAmount = stream.readInt();
                for (int i = 0; i < imageAmount; i++) {
                    String name = stream.readUTF();
                    stream.readShort(); // width（忽略）
                    stream.readShort(); // height（忽略）
                    int len = stream.readInt();
                    byte[] b = new byte[len]; stream.readFully(b);
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(b)) {
                        BufferedImage img = ImageIO.read(bais);
                        mapData.patches.computeIfAbsent(CustomTypeIO.DataAssetType.image, k -> new ArrayList<>())
                                .add(new DataImage(img, name, name + ".png"));
                    } catch (IOException e) {
                        System.err.println("解析 patches 图片失败：" + e.getMessage());
                    }
                }
            } else if (mapVersion >= 13) {
                int total = stream.readInt();
                DebugLog.log("[patches v13] total=%d", total);

                for (int i = 0; i < total; i++) {
                    int assetStart = data.length - stream.available();

                byte typeId = stream.readByte();
                if (typeId < 0 || typeId >= CustomTypeIO.DataAssetType.all.length) {
                    throw new IOException("Invalid DataAssetType: " + typeId);
                }
                CustomTypeIO.DataAssetType type = CustomTypeIO.DataAssetType.all[typeId];
                String path = stream.readUTF();
                boolean embedded = stream.readBoolean();

                DebugLog.log("[patches v13] #%d start=%d type=%s path=\"%s\" embedded=%s",
                        i, assetStart, type, path, embedded);

                if (!embedded) {
                    byte[] hash = new byte[32];
                    stream.readFully(hash);
                    DataAsset asset = createEmptyAsset(type, path);
                    asset.byteHash = hash;
                    mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(asset);
                    int end = data.length - stream.available();
                    DebugLog.log("[patches v13] #%d end=%d consumed=%d (hash-only)", i, end, end - assetStart);
                    continue;
                }

                switch (type) {
                    case patch -> {
                        int len = stream.readInt();
                        DebugLog.log("[patches v13] #%d patch.len=%d", i, len);
                        byte[] bytes = new byte[len];
                        stream.readFully(bytes);
                        RawPatch rp = new RawPatch(new String(bytes, "UTF-8"), bytes);
                        rp.path = path;
                        mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(rp);
                    }
                    case content -> {
                        short ord = stream.readShort();
                        int len = stream.readInt();
                        DebugLog.log("[patches v13] #%d content.typeOrd=%d len=%d", i, ord, len);
                        byte[] bytes = new byte[len];
                        stream.readFully(bytes);
                        RawPatch rp = new RawPatch(new String(bytes, "UTF-8"), bytes);
                        rp.path = path;
                        rp.contentTypeOrdinal = ord;
                        mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(rp);
                    }
                    case bundle -> {
                        int len = stream.readInt();
                        DebugLog.log("[patches v13] #%d bundle.len=%d", i, len);
                        byte[] bytes = new byte[len];
                        stream.readFully(bytes);
                        Bundle b = new Bundle(extractLangFromBundlePath(path), new String(bytes, "UTF-8"));
                        b.path = path;
                        b.rawBytes = bytes;
                        mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(b);
                    }
                    case image -> {
                        int len = stream.readInt();
                        DebugLog.log("[patches v13] #%d image.len=%d", i, len);
                        byte[] bytes = new byte[len];
                        stream.readFully(bytes);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                            BufferedImage img = ImageIO.read(bais);
                            DataImage di = new DataImage(img, extractNameFromPath(path), path);
                            di.rawBytes = bytes;
                            mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(di);
                        } catch (IOException e) {
                            DebugLog.log("[patches v13] #%d image 解析失败: %s", i, e.getMessage());
                        }
                    }
                    case sound, music -> {
                        int len = stream.readInt();
                        DebugLog.log("[patches v13] #%d %s.len=%d", i, type, len);
                        byte[] bytes = new byte[len];
                        stream.readFully(bytes);
                        DataAsset asset = (type == CustomTypeIO.DataAssetType.sound)
                                ? new DataSound(extractNameFromPath(path), bytes)
                                : new DataMusic(extractNameFromPath(path), bytes);
                        asset.path = path;
                        mapData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(asset);
                    }
                }

                int end = data.length - stream.available();
                DebugLog.log("[patches v13] #%d end=%d consumed=%d", i, end, end - assetStart);
            }

            mapData.rawPatchesBytes = null;
            int finalPos = data.length - stream.available();
            DebugLog.log("[patches v13] 完成，最终 offset=%d / total=%d", finalPos, data.length);
        }

    } catch (Exception e) {
        // 关键：把出错位置附近的字节打出来
        int pos = 0;
        try {
            // 用一个镜像 stream 再测一次 available 拿不到，因为 stream 已关闭/越界
            // 改为从异常信息中提取，或用外层捕获点记录
        } catch (Exception ignored) {}

        DebugLog.log("[patches] 解析失败: %s", e.getMessage());
        // 出错时 dump 整个 patches 区域的前 1KB（或最后 256 字节）
        DebugLog.logRaw("--- patches 区域 dump（前 512 字节）---");
        DebugLog.logRaw(hexAround(data, Math.min(512, data.length), 0, 512));

        mapData.rawPatchesBytes = data.clone();
        mapData.patches.clear();
    }
}

    /** 根据类型创建空的 DataAsset 实例，用于非 embedded 场景 */
    private static DataAsset createEmptyAsset(CustomTypeIO.DataAssetType type, String path) {
        return switch (type) {
            case patch, content -> new RawPatch(null, null);
            case bundle -> new Bundle(extractLangFromBundlePath(path), null);
            case image -> new DataImage(null, extractNameFromPath(path), path);
            case sound -> new DataSound(extractNameFromPath(path), null);
            case music -> new DataMusic(extractNameFromPath(path), null);
        };
    }

    /** 从 "bundles-zh_CN.properties" 中提取 "zh_CN"；无语言后缀时返回 "en" */
    private static String extractLangFromBundlePath(String path) {
        if (path == null) return "en";
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);

        // 去扩展名
        if (name.endsWith(".properties")) {
            name = name.substring(0, name.length() - ".properties".length());
        }

        // bundle → en
        if (name.equals("bundle")) return "en";

        // bundle_zh_CN → zh_CN
        if (name.startsWith("bundle_")) return name.substring("bundle_".length());

        // 兜底：识别不了就按 en 处理（通常是不合规的命名，不该在存档里出现）
        return "en";
    }

    public static String makeBundlePath(String lang) {
        if (lang == null || lang.isEmpty() || "en".equals(lang)) {
            return "bundle.properties";
        }
        return "bundle_" + lang + ".properties";
    }

    /** 从路径中提取文件名（不含扩展名） */
    private static String extractNameFromPath(String path) {
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static void reparseEntities(byte[] entitiesData, MapData target) throws IOException {
        target.entityMapping.clear();
        target.teamBlocks.clear();
        target.entities.clear();
        parseEntitiesRegion(entitiesData, target);
    }

    /** 通用资产基类，保存 path、hash 和原始字节 */
    public static class DataAsset {
        public String path;
        public byte[] byteHash;      // 非 embedded 时有效
        public byte[] rawBytes;      // embedded 时有效
    }

    public static class DataImage extends DataAsset {
        public BufferedImage img;
        public String name;
        public DataImage(BufferedImage image, String region, String path) {
            this.img = image;
            this.name = region;
            this.path = path;
        }
    }

    public static class Bundle extends DataAsset {
        public String lang;
        public String content;
        public Bundle(String language, String content) {
            this.lang = language;
            this.content = content;
        }
    }

    public static class DataSound extends DataAsset {
        public String name;
        public DataSound(String name, byte[] bytes) {
            this.name = name;
            this.rawBytes = bytes;
        }
    }

    public static class DataMusic extends DataAsset {
        public String name;
        public DataMusic(String name, byte[] bytes) {
            this.name = name;
            this.rawBytes = bytes;
        }
    }

    /** patch / content 类资产的通用包装 */
    public static class RawPatch extends DataAsset {
        public String content;
        public short contentTypeOrdinal;    // ★ 仅 content 类型有意义
        public RawPatch(String content, byte[] bytes) {
            this.content = content;
            this.rawBytes = bytes;
        }
    }

    // ==================== 调试日志 ====================
    public static class DebugLog {
        // 启动时加 -Dmapeditor.debug=true 才启用
        public static boolean enabled = Boolean.getBoolean("mapeditor.debug");
        private static final int MAX_LINES = 20000;
        private static final StringBuilder buffer = new StringBuilder();
        private static int lineCount = 0;

        public static void log(String fmt, Object... args) {
            if (!enabled) return;
            if (lineCount >= MAX_LINES) return;
            String s = String.format(fmt, args);
            buffer.append(s).append('\n');
            lineCount++;
            System.err.println(s);
        }

        public static void logRaw(String s) {
            if (!enabled) return;
            if (lineCount >= MAX_LINES) return;
            buffer.append(s).append('\n');
            lineCount++;
            System.err.println(s);
        }

        public static String dump() {
            return buffer.toString();
        }

        public static void clear() {
            buffer.setLength(0);
            lineCount = 0;
        }
    }

    /** 打印 data 中 [pos - before, pos + after) 范围的十六进制，pos 位置用 <-- 标记 */
    public static String hexAround(byte[] data, int pos, int before, int after) {
        int lo = Math.max(0, pos - before);
        int hi = Math.min(data.length, pos + after);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  offset=%d, total=%d, 窗口=[%d, %d)%n", pos, data.length, lo, hi));
        for (int i = lo; i < hi; i += 16) {
            sb.append(String.format("%04X: ", i));
            for (int j = 0; j < 16 && i + j < hi; j++) {
                if (i + j == pos) sb.append("<<");
                sb.append(String.format("%02X ", data[i + j] & 0xFF));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String hexAround(byte[] data, int pos) {
        return hexAround(data, pos, 32, 64);
    }
}