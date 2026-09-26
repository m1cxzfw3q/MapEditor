import arc.struct.Seq;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.JsonWriter.OutputType;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MapEditorGUI extends JFrame {
    private JLabel statusLabel;
    private JTextField headerField, versionField;
    private DefaultTableModel metaTableModel;
    private JTextArea regionArea;
    private RSyntaxTextArea markersJsonArea;
    private RSyntaxTextArea rulesJsonArea;
    private JTable teamTable, entityTable;
    private DefaultTableModel teamModel, entityModel;
    private boolean markersModified = false;
    private File currentFile;
    private MapReaderWriter.MapData currentData;
    private JTabbedPane tabs; // 改为成员变量以便在拖拽处理中访问

    // 类成员
    private JButton patchesAddButton;

    private static class PatchTab {
        String title;
        CustomTypeIO.DataAssetType type;
        JTable table;
        DefaultTableModel model;

        // 过滤后的引用（与 model 行一一对应）
        List<Object[]> rowRefs = new ArrayList<>();

        // 全量数据（未过滤）
        List<Object[]> allRows = new ArrayList<>();
        List<Object[]> allRefs = new ArrayList<>();
    }

    private JTabbedPane patchesSubTabs;
    private final List<PatchTab> patchTabs = new ArrayList<>();

    private PatchTab getActivePatchTab() {
        if (patchesSubTabs == null) return null;
        int idx = patchesSubTabs.getSelectedIndex();
        if (idx < 0 || idx >= patchTabs.size()) return null;
        return patchTabs.get(idx);
    }

    // 撤销管理器
    private class UndoManager {
        private final Stack<MapReaderWriter.MapData> undoStack = new Stack<>();
        private final Stack<MapReaderWriter.MapData> redoStack = new Stack<>();
        private boolean operationInProgress = false;

        public void saveState() {
            if (currentData == null || operationInProgress) return;
            undoStack.push(currentData.deepCopy());
            redoStack.clear();
        }

        public boolean canUndo() {
            return !undoStack.isEmpty();
        }

        public boolean canRedo() {
            return !redoStack.isEmpty();
        }

        public void undo() {
            if (!canUndo()) return;
            EditorWindow.closeAll();
            operationInProgress = true;
            redoStack.push(currentData.deepCopy());
            currentData = undoStack.pop();
            updateUI();
            statusLabel.setText("已撤销");
            operationInProgress = false;
        }

        public void redo() {
            if (!canRedo()) return;
            EditorWindow.closeAll();
            operationInProgress = true;
            undoStack.push(currentData.deepCopy());
            currentData = redoStack.pop();
            updateUI();
            statusLabel.setText("已重做");
            operationInProgress = false;
        }
    }

    private final List<FieldInfo> ruleFields;
    private UndoManager undoManager = new UndoManager();

    private static final Set<CustomTypeIO.ContentType> LOADABLE_CONTENT_TYPES = Set.of(
            CustomTypeIO.ContentType.item,
            CustomTypeIO.ContentType.block,
            CustomTypeIO.ContentType.liquid,
            CustomTypeIO.ContentType.status,
            CustomTypeIO.ContentType.unit,
            CustomTypeIO.ContentType.weather
    );

    public MapEditorGUI() {
        setTitle("Mindustry 地图编辑器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        ruleFields = new ArrayList<>();
        initRuleFields();
        setSize(1100, 750);
        setLocationRelativeTo(null);
        initMenu();
        initComponents();
        setupDropTarget();
    }

    private static class FieldInfo {
        String key;          // JSON 中的键名
        String label;        // 界面显示名称
        Class<?> type;       // 值类型: Boolean.class, Integer.class, Float.class
        Object defaultValue; // 默认值（用于 JSON 中缺失时）
        JComponent component; // 对应的输入组件（在创建时赋值）

        FieldInfo(String key, String label, Class<?> type, Object defaultValue) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }

    private void initRuleFields() {
        // 杂项1
        ruleFields.add(new FieldInfo("allowEditRules", "游戏中编辑规则", Boolean.class, false));
        ruleFields.add(new FieldInfo("infiniteResources", "无限资源(沙盒模式)", Boolean.class, false));
        ruleFields.add(new FieldInfo("waveTimer", "波次计时器", Boolean.class, true));
        ruleFields.add(new FieldInfo("waveSending", "可跳波", Boolean.class, true));
        ruleFields.add(new FieldInfo("waves", "波次", Boolean.class, true));
        ruleFields.add(new FieldInfo("airUseSpawns", "空军出生在刷怪点", Boolean.class, false));
        ruleFields.add(new FieldInfo("wavesSpawnAtCores", "核心生成波次", Boolean.class, true));
        ruleFields.add(new FieldInfo("pvp", "PVP模式", Boolean.class, false));
        ruleFields.add(new FieldInfo("pvpAutoPause", "PVP自动暂停", Boolean.class, true));
        ruleFields.add(new FieldInfo("waitEnemies", "波次等待敌人清零", Boolean.class, false));
        ruleFields.add(new FieldInfo("attackMode", "进攻模式", Boolean.class, false));
        ruleFields.add(new FieldInfo("editor", "编辑器模式", Boolean.class, false));
        ruleFields.add(new FieldInfo("derelictRepair", "废墟可修复", Boolean.class, true));
        ruleFields.add(new FieldInfo("canGameOver", "游戏是否可结束", Boolean.class, true));
        ruleFields.add(new FieldInfo("coreCapture", "核心被摧毁换队", Boolean.class, false));
        ruleFields.add(new FieldInfo("reactorExplosions", "开启反应堆爆炸", Boolean.class, true));
        ruleFields.add(new FieldInfo("possessionAllowed", "允许附身单位", Boolean.class, true));
        ruleFields.add(new FieldInfo("schematicsAllowed", "允许使用蓝图", Boolean.class, true));
        ruleFields.add(new FieldInfo("damageExplosions", "启用爆炸伤害", Boolean.class, true));
        ruleFields.add(new FieldInfo("fire", "开启火焰", Boolean.class, true));
        ruleFields.add(new FieldInfo("unitAmmo", "启用单位弹药限制", Boolean.class, false));
        ruleFields.add(new FieldInfo("randomWaveAI", "不可预测波次AI", Boolean.class, false));
        ruleFields.add(new FieldInfo("unitPayloadUpdate", "单位载荷运作", Boolean.class, false));
        ruleFields.add(new FieldInfo("unitPayloadsExplode", "单位载荷爆炸", Boolean.class, false));
        ruleFields.add(new FieldInfo("unitCapVariable", "核心添加单位上限", Boolean.class, true));
        ruleFields.add(new FieldInfo("showSpawns", "显示刷怪点", Boolean.class, false));

        // 单位属性
        ruleFields.add(new FieldInfo("solarMultiplier", "太阳能倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitBuildSpeedMultiplier", "单位制造速度倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitCostMultiplier", "单位制造花费倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitDamageMultiplier", "单位伤害倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitHealthMultiplier", "单位血量倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitCrashDamageMultiplier", "空军坠毁伤害倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("unitMineSpeedMultiplier", "单位挖矿速度倍率", Float.class, 1f));

        // 建筑属性
        ruleFields.add(new FieldInfo("ghostBlocks", "幽灵建筑", Boolean.class, true));
        ruleFields.add(new FieldInfo("logicUnitControl", "逻辑控制单位", Boolean.class, true));
        ruleFields.add(new FieldInfo("logicUnitBuild", "逻辑控制单位建造", Boolean.class, true));
        ruleFields.add(new FieldInfo("logicUnitDeconstruct", "逻辑控制单位拆除", Boolean.class, false));
        ruleFields.add(new FieldInfo("allowEditWorldProcessors", "允许编辑世界处理器", Boolean.class, false));
        ruleFields.add(new FieldInfo("disableWorldProcessors", "禁用所有世界处理器", Boolean.class, false));

        ruleFields.add(new FieldInfo("buildSpeedMultiplier", "建造速度倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("buildCostMultiplier", "建造花费倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("blockDamageMultiplier", "建筑伤害倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("blockHealthMultiplier", "建筑血量倍率", Float.class, 1f));
        ruleFields.add(new FieldInfo("deconstructRefundMultiplier", "拆除返还倍率", Float.class, 0.5f));
        ruleFields.add(new FieldInfo("objectiveTimerMultiplier", "计时器目标中的时间乘数", Float.class, 1f));
        ruleFields.add(new FieldInfo("enemyCoreBuildRadius", "敌方核心禁建范围", Float.class, 400f)); // 原为1f，修正为游戏默认400

        // 杂项2
        ruleFields.add(new FieldInfo("polygonCoreProtection", "建造区域平分", Boolean.class, false));
        ruleFields.add(new FieldInfo("placeRangeCheck", "建筑额外禁建范围", Boolean.class, false));
        ruleFields.add(new FieldInfo("cleanupDeadTeams", "PVP清除战败队伍建筑", Boolean.class, true));
        ruleFields.add(new FieldInfo("onlyDepositCore", "物品仅可丢入核心", Boolean.class, false));
        ruleFields.add(new FieldInfo("allowCoreUnloaders", "允许装卸器卸载核心资源", Boolean.class, true));

        ruleFields.add(new FieldInfo("itemDepositCooldown", "玩家物品存入冷却时间(s)", Float.class, 0.5f));

        ruleFields.add(new FieldInfo("coreDestroyClear", "核心被摧毁清理附近建筑", Boolean.class, false));
        ruleFields.add(new FieldInfo("hideBannedBlocks", "隐藏禁用建筑", Boolean.class, false));
        ruleFields.add(new FieldInfo("allowEnvironmentDeconstruct", "可拆除环境墙", Boolean.class, false));
        ruleFields.add(new FieldInfo("instantBuild", "建筑瞬间建造", Boolean.class, false));
        ruleFields.add(new FieldInfo("blockWhitelist", "建筑白名单", Boolean.class, false));
        ruleFields.add(new FieldInfo("unitWhitelist", "单位白名单", Boolean.class, false));

        ruleFields.add(new FieldInfo("dropZoneRadius", "刷怪点大小", Float.class, 300f));
        ruleFields.add(new FieldInfo("waveSpacing", "波次间隔", Float.class, 7200f));
        ruleFields.add(new FieldInfo("initialWaveSpacing", "初始波次间隔", Float.class, 0f));

        ruleFields.add(new FieldInfo("winWave", "胜利波次", Integer.class, 0));
        ruleFields.add(new FieldInfo("unitCap", "固定单位上限", Integer.class, 0));

        // 修正：disableUnitCap 是布尔类型，默认应为 false
        ruleFields.add(new FieldInfo("disableUnitCap", "禁用单位上限", Boolean.class, false));
        ruleFields.add(new FieldInfo("dragMultiplier", "阻力倍率", Float.class, 1f)); // 原为7200f，修正为1

        ruleFields.add(new FieldInfo("ambientMusic", "环境氛围音乐", String.class, ""));
        ruleFields.add(new FieldInfo("darkMusic", "战斗冲突音乐", String.class, ""));

        ruleFields.add(new FieldInfo("alwaysPlayMusic", "始终播放音乐", Boolean.class, false));
        ruleFields.add(new FieldInfo("disableMusic", "关闭自动播放音乐", Boolean.class, false));
        ruleFields.add(new FieldInfo("musicVolume", "音乐音量", Float.class, 1f));

        ruleFields.add(new FieldInfo("fog", "战争迷雾", Boolean.class, false));
        ruleFields.add(new FieldInfo("staticFog", "静态迷雾", Boolean.class, true));
        ruleFields.add(new FieldInfo("lighting", "环境照明", Boolean.class, false));

        ruleFields.add(new FieldInfo("modeName", "自定义模式名", String.class, ""));
        ruleFields.add(new FieldInfo("mission", "任务", String.class, ""));

        ruleFields.add(new FieldInfo("coreIncinerates", "核心焚烧溢出资源", Boolean.class, true));
        ruleFields.add(new FieldInfo("borderDarkness", "若为假，边框会逐渐消失于黑暗中", Boolean.class, true));
        ruleFields.add(new FieldInfo("limitMapArea", "限制地图区域", Boolean.class, false));

        ruleFields.add(new FieldInfo("limitX", "限制地图区域(X)", Integer.class, 0));
        ruleFields.add(new FieldInfo("limitY", "限制地图区域(Y)", Integer.class, 0));
        ruleFields.add(new FieldInfo("limitWidth", "限制地图区域(宽)", Integer.class, 1));
        ruleFields.add(new FieldInfo("limitHeight", "限制地图区域(高)", Integer.class, 1));

        ruleFields.add(new FieldInfo("disableOutsideArea", "区域外禁用", Boolean.class, true));
        ruleFields.add(new FieldInfo("backgroundTexture", "背景纹理路径及扩展名", String.class, ""));
        ruleFields.add(new FieldInfo("backgroundSpeed", "背景纹理移速缩放(0禁用)", Float.class, 27000f)); // 原为String，修正为Float
        ruleFields.add(new FieldInfo("backgroundScl", "背景纹理缩放比例", Float.class, 1f)); // 原为String，修正为Float

        ruleFields.add(new FieldInfo("backgroundOffsetX", "背景UV偏移(X)", Float.class, 0.1f));
        ruleFields.add(new FieldInfo("backgroundOffsetY", "背景UV偏移(Y)", Float.class, 0.1f));

        ruleFields.add(new FieldInfo("allowLogicData", "允许世界处理器使用\"data\"指令", Boolean.class, false));
    }

    private void initMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("文件");
        JMenuItem open = new JMenuItem("打开");
        open.addActionListener(this::openFile);
        file.add(open);
        JMenuItem save = new JMenuItem("另存为");
        save.addActionListener(this::saveFile);
        file.add(save);
        JMenuItem exit = new JMenuItem("退出");
        exit.addActionListener(e -> System.exit(0));
        file.add(exit);
        bar.add(file);

        JMenu view = new JMenu("视图");
        JCheckBoxMenuItem darkItem = new JCheckBoxMenuItem("深色模式", ThemeManager.isDark());
        darkItem.addActionListener(e -> {
            ThemeManager.apply(this, darkItem.isSelected());
            EditorWindow.refreshTheme();
        });
        view.add(darkItem);

        JMenuItem fontItem = new JMenuItem("字体...");
        fontItem.addActionListener(e -> {
            FontChooserDialog.Result r = FontChooserDialog.show(
                    this,
                    ThemeManager.getUIFontFamily(),
                    ThemeManager.hasCustomFont() ? ThemeManager.getUIFontSize() : 13,
                    ThemeManager.getFontVerticalOffset());

            if (r == null) return;

            if (r.family() == null) {
                ThemeManager.setUIFont(null, 0, 0f);
                statusLabel.setText("字体已重置为默认");
            } else {
                ThemeManager.setUIFont(r.family(), r.size(), r.offsetY());
                statusLabel.setText("字体已切换: " + r.family() + " " + r.size()
                        + "  偏移 " + r.offsetY() + "px");
            }
        });
        view.add(fontItem);

        bar.add(view);
        setJMenuBar(bar);
    }

    private void initComponents() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.25);

        // 左侧信息面板
        JPanel left = new JPanel(new BorderLayout());
        JPanel info = new JPanel(new GridLayout(4, 2, 5, 5));
        info.setBorder(BorderFactory.createTitledBorder("基本信息"));
        info.add(new JLabel("文件标识:")); headerField = new JTextField(); headerField.setEditable(false); info.add(headerField);
        info.add(new JLabel("版本号:")); versionField = new JTextField(); versionField.setEditable(false); info.add(versionField);
        info.add(new JLabel("区域列表:")); regionArea = new JTextArea(10,20); regionArea.setEditable(false); regionArea.setFont(new Font("Monospaced", Font.PLAIN,12)); info.add(new JScrollPane(regionArea));
        left.add(info, BorderLayout.NORTH);

        // 右侧选项卡
        tabs = new JTabbedPane(); // 成员变量

        // 元数据
        metaTableModel = new DefaultTableModel(new String[]{"键", "值"}, 0) {
            public boolean isCellEditable(int r, int c) { return c == 1; }
        };
        JTable metaTable = new JTable(metaTableModel);
        tabs.addTab("元数据", new JScrollPane(metaTable));

        // ==================== 补丁选项卡 ====================
        patchesSubTabs = new JTabbedPane();
        patchTabs.clear();

        // "所有" 子选项卡
        patchTabs.add(createPatchTab("所有", null));

        // 每个 DataAssetType 一个子选项卡
        for (CustomTypeIO.DataAssetType t : CustomTypeIO.DataAssetType.all) {
            patchTabs.add(createPatchTab(t.displayed, t));
        }

        // 把每个 PatchTab 的表格包成 JScrollPane 加进 sub tabs
        for (PatchTab pt : patchTabs) {
            JScrollPane scroll = new JScrollPane(pt.table);
            // 在子选项卡标题上加数量提示，updateUI 里会刷新
            patchesSubTabs.addTab(pt.title, scroll);
        }

        JPanel patchPanel = new JPanel(new BorderLayout());
        patchPanel.add(patchesSubTabs, BorderLayout.CENTER);

        JPanel pbtns = new JPanel(new FlowLayout());
        patchesAddButton = new JButton("添加");
        patchesAddButton.addActionListener(e -> addPatch());
        pbtns.add(patchesAddButton);

        JButton edit = new JButton("编辑");
        edit.addActionListener(e -> editPatch());
        pbtns.add(edit);

        JButton del = new JButton("删除");
        del.addActionListener(e -> removePatch());
        pbtns.add(del);

        JButton imp = new JButton("从文件导入");
        imp.addActionListener(e -> importPatches());
        pbtns.add(imp);

        JButton exportSelected = new JButton("导出所选");
        exportSelected.addActionListener(e -> exportSelectedPatch());
        pbtns.add(exportSelected);

        JButton exportAll = new JButton("全部导出");
        exportAll.addActionListener(e -> exportAllPatches());
        pbtns.add(exportAll);

        patchPanel.add(pbtns, BorderLayout.SOUTH);

        JPanel patchSearchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        patchSearchPanel.add(new JLabel("搜索:"));

        JTextField patchSearchField = new JTextField(24);
        patchSearchField.setToolTipText("在名称、摘要、类型中搜索；留空显示全部");
        patchSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() {
                patchFilter = patchSearchField.getText().trim();
                applyFilterToAllTabs();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        });
        patchSearchPanel.add(patchSearchField);

        JCheckBox namesOnly = new JCheckBox("仅搜名称");
        namesOnly.addActionListener(e -> {
            patchSearchNamesOnly = namesOnly.isSelected();
            applyFilterToAllTabs();
        });
        patchSearchPanel.add(namesOnly);

        JButton clearSearch = new JButton("清空");
        clearSearch.addActionListener(e -> {
            patchSearchField.setText("");
            // DocumentListener 会自动触发 applyFilterToAllTabs
        });
        patchSearchPanel.add(clearSearch);

        patchesSubTabs.addChangeListener(e -> updatePatchesAddButtonState());
        patchPanel.add(patchSearchPanel, BorderLayout.NORTH);
        tabs.addTab("补丁", patchPanel);

        // Team Blocks（带复选框）
        JPanel teamPanel = new JPanel(new BorderLayout());
        teamModel = new DefaultTableModel(
                new String[]{"队伍", "X", "Y", "旋转", "块ID", "配置"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        teamTable = new JTable(teamModel);

        // ★ 原生多行选择
        teamTable.setCellSelectionEnabled(false);
        teamTable.setRowSelectionAllowed(true);
        teamTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        teamTable.setRowHeight(22);
        teamTable.setShowGrid(false);
        teamTable.setIntercellSpacing(new Dimension(0, 0));

        teamTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = teamTable.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;   // 双击空白不触发
                    // 收敛选中范围到当前行
                    teamTable.setRowSelectionInterval(viewRow, viewRow);
                    editSelectedTeamBlock();
                }
            }
        });

        teamPanel.add(new JScrollPane(teamTable), BorderLayout.CENTER);
        tabs.addTab("Team Blocks", teamPanel);

        // 添加按钮面板
        JPanel teamButtons = new JPanel(new FlowLayout());
        JButton addTeamBlock = new JButton("添加");
        addTeamBlock.addActionListener(e -> addTeamBlock());
        teamButtons.add(addTeamBlock);
        JButton deleteTeamBlocks = new JButton("删除所选");
        deleteTeamBlocks.addActionListener(e -> deleteSelectedTeamBlocks());
        teamButtons.add(deleteTeamBlocks);
        JButton editTeamBlocks = new JButton("编辑所选");
        editTeamBlocks.addActionListener(e -> editSelectedTeamBlock());
        teamButtons.add(editTeamBlocks);
        JButton applyTeamChanges = new JButton("应用更改");
        applyTeamChanges.addActionListener(e -> applyTeamChanges());
        teamButtons.add(applyTeamChanges);
        teamPanel.add(teamButtons, BorderLayout.SOUTH);

        // Entities 列表（带复选框）
        JPanel entityPanel = new JPanel(new BorderLayout());
        entityModel = new DefaultTableModel(
                new String[]{"ID", "类型", "X", "Y", "队伍", "健康值", "旋转"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        entityTable = new JTable(entityModel);

        // ★ 原生多行选择
        entityTable.setCellSelectionEnabled(false);
        entityTable.setRowSelectionAllowed(true);
        entityTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entityTable.setRowHeight(22);
        entityTable.setShowGrid(false);
        entityTable.setIntercellSpacing(new Dimension(0, 0));

        entityTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = entityTable.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    entityTable.setRowSelectionInterval(viewRow, viewRow);
                    editSelectedEntity();
                }
            }
        });

        entityPanel.add(new JScrollPane(entityTable), BorderLayout.CENTER);
        JPanel entityButtons = new JPanel(new FlowLayout());
        JButton deleteSelected = new JButton("删除所选");
        deleteSelected.addActionListener(e -> deleteSelectedEntities());
        entityButtons.add(deleteSelected);
        JButton editSelected = new JButton("编辑所选");
        editSelected.addActionListener(e -> editSelectedEntity());
        entityButtons.add(editSelected);
        JButton applyChanges = new JButton("应用更改");
        applyChanges.addActionListener(e -> applyEntitiesChanges());
        entityButtons.add(applyChanges);
        JButton reparseEntities = new JButton("重新解析");
        reparseEntities.addActionListener(e -> reparseEntities());
        entityButtons.add(reparseEntities);
        JButton exportCSV = new JButton("导出 CSV");
        exportCSV.addActionListener(e -> exportEntitiesToCSV());
        entityButtons.add(exportCSV);
        entityPanel.add(entityButtons, BorderLayout.SOUTH);
        tabs.addTab("Entities", entityPanel);

        // Markers JSON
        JPanel jsonPanel = new JPanel(new BorderLayout());
        markersJsonArea = new RSyntaxTextArea(20, 40);
        markersJsonArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        markersJsonArea.setCodeFoldingEnabled(true);
        markersJsonArea.setAntiAliasingEnabled(true);
        ThemeManager.applyToNewEditor(markersJsonArea);   // ★
        RTextScrollPane jsonScroll = new RTextScrollPane(markersJsonArea);
        jsonPanel.add(jsonScroll, BorderLayout.CENTER);

        JPanel jsonButtons = new JPanel(new FlowLayout());
        JButton loadJson = new JButton("从区域加载 JSON");
        loadJson.addActionListener(e -> loadMarkersJson());
        jsonButtons.add(loadJson);
        JButton applyJson = new JButton("应用修改");
        applyJson.addActionListener(e -> applyMarkersJson());
        jsonButtons.add(applyJson);
        JButton exportJson = new JButton("导出为 JSON 文件");
        exportJson.addActionListener(e -> exportMarkersJson());
        jsonButtons.add(exportJson);
        JButton importJson = new JButton("从 JSON 文件导入");
        importJson.addActionListener(e -> importMarkersJson());
        jsonButtons.add(importJson);
        JButton formatJson = new JButton("格式化 JSON");
        formatJson.addActionListener(e -> formatMarkersJson());
        jsonButtons.add(formatJson);
        jsonPanel.add(jsonButtons, BorderLayout.SOUTH);
        tabs.addTab("Markers JSON", jsonPanel);

        // Rules JSON
        JPanel rulesJsonPanel = new JPanel(new BorderLayout());
        rulesJsonArea = new RSyntaxTextArea(20, 40);
        rulesJsonArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        rulesJsonArea.setCodeFoldingEnabled(true);
        rulesJsonArea.setAntiAliasingEnabled(true);
        ThemeManager.applyToNewEditor(rulesJsonArea);   // ★
        RTextScrollPane rulesJsonScroll = new RTextScrollPane(rulesJsonArea);
        rulesJsonPanel.add(rulesJsonScroll, BorderLayout.CENTER);

        JPanel rulesJsonButtons = new JPanel(new FlowLayout());
        JButton loadRulesJson = new JButton("从元数据加载");
        loadRulesJson.addActionListener(e -> loadRulesJson());
        rulesJsonButtons.add(loadRulesJson);
        JButton applyRulesJson = new JButton("应用修改");
        applyRulesJson.addActionListener(e -> applyRulesJson());
        rulesJsonButtons.add(applyRulesJson);
        JButton exportRulesJson = new JButton("导出为 JSON 文件");
        exportRulesJson.addActionListener(e -> exportRulesJson());
        rulesJsonButtons.add(exportRulesJson);
        JButton importRulesJson = new JButton("从 JSON 文件导入");
        importRulesJson.addActionListener(e -> importRulesJson());
        rulesJsonButtons.add(importRulesJson);
        JButton formatRulesJson = new JButton("格式化 JSON");
        formatRulesJson.addActionListener(e -> formatRulesJson());
        rulesJsonButtons.add(formatRulesJson);
        rulesJsonPanel.add(rulesJsonButtons, BorderLayout.SOUTH);
        tabs.addTab("Rules JSON", rulesJsonPanel);

        // Rules Graphic 面板
        JPanel rulesGraphicPanel = new JPanel(new BorderLayout());
        JPanel graphicContent = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        // 动态创建组件
        for (int i = 0; i < ruleFields.size(); i++) {
            FieldInfo field = ruleFields.get(i);
            gbc.gridy = i;
            gbc.gridx = 0;
            graphicContent.add(new JLabel(field.label + ":"), gbc);
            gbc.gridx = 1;
            if (field.type == Boolean.class) {
                JCheckBox check = new JCheckBox();
                field.component = check;
                graphicContent.add(check, gbc);
            } else {
                JTextField text = new JTextField(10);
                field.component = text;
                graphicContent.add(text, gbc);
            }
        }

        JScrollPane graphicScroll = new JScrollPane(graphicContent);
        graphicScroll.getVerticalScrollBar().setUnitIncrement(40);
        rulesGraphicPanel.add(graphicScroll, BorderLayout.CENTER);

        JPanel graphicButtons = new JPanel(new FlowLayout());
        JButton loadGraphic = new JButton("从规则加载");
        loadGraphic.addActionListener(e -> loadRulesGraphic());
        graphicButtons.add(loadGraphic);

        JButton applyGraphic = new JButton("应用修改");
        applyGraphic.addActionListener(e -> applyRulesGraphic());
        graphicButtons.add(applyGraphic);

        rulesGraphicPanel.add(graphicButtons, BorderLayout.SOUTH);
        tabs.addTab("Rules图形化", rulesGraphicPanel);

        // 操作
        JPanel act = new JPanel(new GridLayout(4, 1, 10, 10));
        JButton rebuild = new JButton("重建数据（保存前必须执行）"); rebuild.addActionListener(e -> rebuildData()); act.add(rebuild);
        JButton saveBtn = new JButton("保存到新文件..."); saveBtn.addActionListener(this::saveFile); act.add(saveBtn);
        tabs.addTab("操作", act);

        split.setLeftComponent(left);
        split.setRightComponent(tabs);

        getContentPane().add(split, BorderLayout.CENTER);

        // 调试日志
        JPanel debugPanel = new JPanel(new BorderLayout());
        JTextArea debugArea = new JTextArea(20, 40);
        debugArea.setEditable(false);
        debugArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane debugScroll = new JScrollPane(debugArea);
        debugPanel.add(debugScroll, BorderLayout.CENTER);

        JPanel debugButtons = new JPanel(new FlowLayout());
        JCheckBox debugToggle = new JCheckBox("启用调试", MapReaderWriter.DebugLog.enabled);
        debugToggle.addActionListener(e -> MapReaderWriter.DebugLog.enabled = debugToggle.isSelected());
        debugButtons.add(debugToggle);

        JButton refreshDebug = new JButton("刷新");
        refreshDebug.addActionListener(e -> debugArea.setText(MapReaderWriter.DebugLog.dump()));
        debugButtons.add(refreshDebug);

        JButton clearDebug = new JButton("清空");
        clearDebug.addActionListener(e -> { MapReaderWriter.DebugLog.clear(); debugArea.setText(""); });
        debugButtons.add(clearDebug);

        JButton exportDebug = new JButton("导出为文件");
        exportDebug.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("debug.log"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
                    pw.print(MapReaderWriter.DebugLog.dump());
                    statusLabel.setText("调试日志已导出");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, printStackTrace("导出失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        debugButtons.add(exportDebug);

        // 每次切到这个 tab 自动刷新
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == debugPanel) {
                debugArea.setText(MapReaderWriter.DebugLog.dump());
                // 自动滚到底部
                debugArea.setCaretPosition(debugArea.getDocument().getLength());
            }
        });

        debugPanel.add(debugButtons, BorderLayout.SOUTH);
        tabs.addTab("调试日志", debugPanel);

        // 创建状态栏面板（包含状态标签和撤销/重做按钮）
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEtchedBorder());
        statusPanel.add(statusLabel);

        JButton undoButton = new BorderlessButton("撤销");
        undoButton.addActionListener(e -> undoManager.undo());
        statusPanel.add(undoButton);

        JButton redoButton = new BorderlessButton("重做");
        redoButton.addActionListener(e -> undoManager.redo());
        statusPanel.add(redoButton);

        // 为整个面板添加边框，使其与界面风格一致
        statusPanel.setBorder(BorderFactory.createEtchedBorder());

        // 将状态面板添加到窗口底部
        getContentPane().add(statusPanel, BorderLayout.SOUTH);

        // 初始化按钮状态
        updatePatchesAddButtonState();
    }

    static class BorderlessButton extends JButton {
        public BorderlessButton(String text) {
            super(text);
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12)); // 保留内边距，但视觉无边框
            setBorderPainted(false);     // 不画边框
            setContentAreaFilled(false); // 不填充默认背景
            setFocusPainted(false);      // 不画焦点虚线框
            setOpaque(false);            // 透明背景
        }
    }

    private void updatePatchesAddButtonState() {
        if (patchesAddButton == null) return;
        PatchTab tab = getActivePatchTab();
        if (tab == null) {
            patchesAddButton.setVisible(true);
            return;
        }
        // null 表示"所有"选项卡；文本类型支持"添加"，非文本类型隐藏
        boolean canAdd = (tab.type == null)
                || tab.type == CustomTypeIO.DataAssetType.patch
                || tab.type == CustomTypeIO.DataAssetType.content
                || tab.type == CustomTypeIO.DataAssetType.bundle;
        patchesAddButton.setVisible(canAdd);
        // 换行重排（如果用了 FlowLayout 才必要；BoxLayout 下 setVisible 会自动收缩）
        Container parent = patchesAddButton.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    /** 返回当前表格所有选中行（model 索引），已升序。空数组表示没有选中 */
    private static int[] getSelectedModelRows(JTable table) {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) return new int[0];
        int[] modelRows = new int[viewRows.length];
        for (int i = 0; i < viewRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        Arrays.sort(modelRows);
        return modelRows;
    }

    private PatchTab createPatchTab(String title, CustomTypeIO.DataAssetType filterType) {
        PatchTab tab = new PatchTab();
        tab.title = title;
        tab.type = filterType;

        tab.model = new DefaultTableModel(new String[]{"类型", "名称", "摘要"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tab.table = new JTable(tab.model);
        tab.table.getColumnModel().getColumn(0).setMaxWidth(60);
        tab.table.getColumnModel().getColumn(1).setPreferredWidth(180);

        // ★ 原生多行选择
        tab.table.setCellSelectionEnabled(false);
        tab.table.setRowSelectionAllowed(true);
        tab.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tab.table.setRowHeight(22);
        tab.table.setShowGrid(false);
        tab.table.setIntercellSpacing(new Dimension(0, 0));

        // 双击进入查看/编辑
        tab.table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = tab.table.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    viewAsset(tab, tab.table.convertRowIndexToModel(viewRow));
                }
            }
        });

        return tab;
    }

    private String assetName(Object obj) {
        if (obj instanceof MapReaderWriter.DataAsset da && da.path != null && !da.path.isEmpty()) {
            String p = da.path;
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
        if (obj instanceof MapReaderWriter.Bundle b) {
            return makeBundlePath(b.lang);
        }
        if (obj instanceof MapReaderWriter.DataImage di) {
            return di.name != null ? di.name : "(图片)";
        }
        if (obj instanceof MapReaderWriter.DataSound ds) return ds.name;
        if (obj instanceof MapReaderWriter.DataMusic dm) return dm.name;
        return "(未命名)";
    }

    private String assetSummary(CustomTypeIO.DataAssetType type, Object obj) {
        if (obj instanceof MapReaderWriter.DataAsset da && da.byteHash != null) {
            return "[hash] " + bytesToHexPrefix(da.byteHash, 8) + "...";
        }
        switch (type) {
            case patch, content, bundle -> {
                if (obj instanceof MapReaderWriter.Bundle b) {
                    String prefix = "[" + (b.lang != null ? b.lang : "?") + "] ";
                    String text = b.content;
                    if (text == null) return prefix + "(空)";
                    String oneLine = text.replaceAll("\\s+", " ").trim();
                    return prefix + (oneLine.length() > 100 ? oneLine.substring(0, 100) + "..." : oneLine);
                }
                String text = null;
                if (obj instanceof MapReaderWriter.RawPatch rp) {
                    text = rp.content;
                    if (type == CustomTypeIO.DataAssetType.content) {
                        CustomTypeIO.ContentType ct = (rp.contentTypeOrdinal >= 0
                                && rp.contentTypeOrdinal < CustomTypeIO.ContentType.values().length)
                                ? CustomTypeIO.ContentType.values()[rp.contentTypeOrdinal]
                                : null;
                        String prefix = "[contentType=" + (ct != null ? ct.name() : "?") + "] ";
                        if (text == null) return prefix + "(空)";
                        String oneLine = text.replaceAll("\\s+", " ").trim();
                        String body = oneLine.length() > 100 ? oneLine.substring(0, 100) + "..." : oneLine;
                        return prefix + body;
                    }
                }else if (obj instanceof String s) {
                    text = s;
                }
                if (text == null) return "(空)";
                String oneLine = text.replaceAll("\\s+", " ").trim();
                return oneLine.length() > 120 ? oneLine.substring(0, 120) + "..." : oneLine;
            }
            case image -> {
                if (obj instanceof MapReaderWriter.DataImage di) {
                    if (di.img != null) {
                        int size = di.rawBytes != null ? di.rawBytes.length : -1;
                        return String.format("PNG %dx%d%s",
                                di.img.getWidth(), di.img.getHeight(),
                                size > 0 ? ", " + size + "B" : "");
                    }
                    return "(hash-only image)";
                }
                return "(图片)";
            }
            case sound, music -> {
                if (obj instanceof MapReaderWriter.DataAsset da && da.rawBytes != null) {
                    return "音频 " + da.rawBytes.length + "B";
                }
                return "(音频)";
            }
        }
        return obj != null ? obj.toString() : "";
    }

    private static String bytesToHexPrefix(byte[] bytes, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, n); i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private void exportEntitiesToCSV() {
        if (currentData == null || currentData.entities.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的实体");
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("entities.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = fc.getSelectedFile();

        // 使用 OutputStreamWriter 写入 UTF-8 BOM
        try (OutputStream os = new FileOutputStream(file);
             Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(writer)) {

            // 写入 UTF-8 BOM (EF BB BF)
            os.write(0xEF);
            os.write(0xBB);
            os.write(0xBF);

            // 写入 CSV 头部
            pw.println("ID,类型,X,Y,队伍,健康值,旋转");

            for (var info : currentData.entities) {
                // 基础字段
                String id = String.valueOf(info.id);
                String type = info.type;
                String x = formatFloat(info.x);
                String y = formatFloat(info.y);
                String team = String.valueOf(info.team);

                // 从 fields 中获取健康值和旋转（如果存在）
                String health = "?";
                String rotation = "?";
                if (info.fields != null) {
                    String h = formatFloat((Float) info.fields.get("health"));
                    if (h != null) health = h;
                    String r = formatFloat((Float) info.fields.get("rotation"));
                    if (r != null) rotation = r;
                }

                // 构造 CSV 行，用引号包裹可能包含逗号的字段
                String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"",
                        escapeCSV(id), escapeCSV(type), escapeCSV(x), escapeCSV(y),
                        escapeCSV(team), escapeCSV(health), escapeCSV(rotation));
                pw.println(line);
            }

            statusLabel.setText("已导出 " + currentData.entities.size() + " 个单位");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, printStackTrace("导出失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // 辅助方法：转义 CSV 字段中的双引号（将 " 替换为 ""）
    private String escapeCSV(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    private String formatFloat(float f) {
        if (Float.isNaN(f)) return "NaN";
        if (f == 0f) return "0";
        // 如果绝对值在合理范围内，显示定点小数
        if (Math.abs(f) >= 1e-4 && Math.abs(f) <= 1e7) {
            return String.format("%.4f", f).replaceAll("0*$", "").replaceAll("\\.$", "");
        } else {
            return String.valueOf(f); // 科学计数法
        }
    }

    private void addTeamBlock() {
        // 创建输入对话框
        JTextField teamField = new JTextField("0");
        JTextField xField = new JTextField("0");
        JTextField yField = new JTextField("0");
        JTextField rotField = new JTextField("0");
        JTextField blockIdField = new JTextField("0");
        // config 暂不支持直接输入，默认为 null
        JLabel configNote = new JLabel("config 将设为 null (如需复杂配置，可后续编辑或导入二进制)");

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        gbc.gridx = 0; gbc.gridy = row; panel.add(new JLabel("队伍 ID:"), gbc);
        gbc.gridx = 1; panel.add(teamField, gbc);
        row++;
        gbc.gridx = 0; gbc.gridy = row; panel.add(new JLabel("X:"), gbc);
        gbc.gridx = 1; panel.add(xField, gbc);
        row++;
        gbc.gridx = 0; gbc.gridy = row; panel.add(new JLabel("Y:"), gbc);
        gbc.gridx = 1; panel.add(yField, gbc);
        row++;
        gbc.gridx = 0; gbc.gridy = row; panel.add(new JLabel("旋转:"), gbc);
        gbc.gridx = 1; panel.add(rotField, gbc);
        row++;
        gbc.gridx = 0; gbc.gridy = row; panel.add(new JLabel("块ID:"), gbc);
        gbc.gridx = 1; panel.add(blockIdField, gbc);
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; panel.add(configNote, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "添加建筑计划", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                int team = Integer.parseInt(teamField.getText());
                short x = Short.parseShort(xField.getText());
                short y = Short.parseShort(yField.getText());
                short rot = Short.parseShort(rotField.getText());
                short blockId = Short.parseShort(blockIdField.getText());

                undoManager.saveState();
                // 创建新计划，config = null
                MapReaderWriter.TeamBlockPlan newPlan =
                        new MapReaderWriter.TeamBlockPlan(team, x, y, rot, blockId, null);
                currentData.teamBlocks.add(newPlan);
                updateUI();
                statusLabel.setText("已添加建筑计划，请点击“应用更改”保存");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "请输入有效的数字", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updateMetaTableEntry(String value) {
        for (int i = 0; i < metaTableModel.getRowCount(); i++) {
            String k = (String) metaTableModel.getValueAt(i, 0);
            if (k.equals("rules")) {
                metaTableModel.setValueAt(value, i, 1);
                return;
            }
        }
        // 如果表格中没有该键，添加一行
        metaTableModel.addRow(new Object[]{"rules", value});
    }

    private void editSelectedTeamBlock() {
        int[] rows = getSelectedModelRows(teamTable);
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先勾选要编辑的建筑计划");
            return;
        }
        if (rows.length > 1) {
            JOptionPane.showMessageDialog(this, "一次只能编辑一个建筑计划");
            return;
        }
        MapReaderWriter.TeamBlockPlan plan = currentData.teamBlocks.get(rows[0]);

        JTextField teamField = new JTextField(String.valueOf(plan.team));
        JTextField xField = new JTextField(String.valueOf(plan.x));
        JTextField yField = new JTextField(String.valueOf(plan.y));
        JTextField rotField = new JTextField(String.valueOf(plan.rotation));
        JTextField blockIdField = new JTextField(String.valueOf(plan.blockId));
        // config 字段暂不支持编辑，仅显示提示
        JLabel configLabel = new JLabel(plan.config == null ? "null" : plan.config.toString());

        JPanel panel = new JPanel(new GridLayout(6, 2, 5, 5));
        panel.add(new JLabel("队伍 ID:"));
        panel.add(teamField);
        panel.add(new JLabel("X:"));
        panel.add(xField);
        panel.add(new JLabel("Y:"));
        panel.add(yField);
        panel.add(new JLabel("旋转:"));
        panel.add(rotField);
        panel.add(new JLabel("块ID:"));
        panel.add(blockIdField);
        panel.add(new JLabel("配置:"));
        panel.add(configLabel); // 只读

        int result = JOptionPane.showConfirmDialog(this, panel, "编辑建筑计划", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                int newTeam = Integer.parseInt(teamField.getText());
                short newX = Short.parseShort(xField.getText());
                short newY = Short.parseShort(yField.getText());
                short newRot = Short.parseShort(rotField.getText());
                short newBlockId = Short.parseShort(blockIdField.getText());

                undoManager.saveState();
                plan.team = newTeam;
                plan.x = newX;
                plan.y = newY;
                plan.rotation = newRot;
                plan.blockId = newBlockId;
                // config 保持不变

                updateUI();
                statusLabel.setText("建筑计划已修改，请点击“应用更改”保存");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "请输入有效的数字", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void setupDropTarget() {
        new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    Transferable tr = dtde.getTransferable();
                    DataFlavor[] flavors = tr.getTransferDataFlavors();
                    for (DataFlavor flavor : flavors) {
                        if (flavor.isFlavorJavaFileListType()) {
                            dtde.acceptDrop(DnDConstants.ACTION_COPY);
                            @SuppressWarnings("unchecked")
                            List<File> files = (List<File>) tr.getTransferData(flavor);
                            handleDroppedFiles(files);
                            dtde.dropComplete(true);
                            return;
                        }
                    }
                    dtde.rejectDrop();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MapEditorGUI.this, printStackTrace("拖拽导入失败: ", e));
                    dtde.rejectDrop();
                }
            }
        });
    }

    private void loadRulesGraphic() {
        if (currentData == null) return;
        String rulesStr = currentData.meta.get("rules");
        if (rulesStr == null) {
            JOptionPane.showMessageDialog(this, "当前地图没有 rules 字段");
            return;
        }
        try {
            JsonReader reader = new JsonReader();
            JsonValue rules = reader.parse(rulesStr);

            for (FieldInfo field : ruleFields) {
                try {
                    if (field.component instanceof JCheckBox) {
                        boolean val = rules.getBoolean(field.key, (Boolean) field.defaultValue);
                        ((JCheckBox) field.component).setSelected(val);
                    } else if (field.component instanceof JTextField) {
                        if (field.type == Float.class) {
                            float val = rules.getFloat(field.key, (Float) field.defaultValue);
                            ((JTextField) field.component).setText(String.valueOf(val));
                        } else if (field.type == Integer.class) {
                            int val = rules.getInt(field.key, (Integer) field.defaultValue);
                            ((JTextField) field.component).setText(String.valueOf(val));
                        } else if (field.type == String.class) {
                            String val = rules.getString(field.key, (String) field.defaultValue);
                            ((JTextField) field.component).setText(val);
                        }
                    }
                } catch (Exception e) {
                    // 捕获单个字段的错误，显示详细信息
                    String msg = String.format("字段 '%s' (%s) 加载失败:\n%s\n原始JSON值: %s",
                            field.key, field.label, e.getMessage(),
                            rules.get(field.key) != null ? rules.get(field.key).toString() : "null");
                    JOptionPane.showMessageDialog(this, msg, "字段解析错误", JOptionPane.WARNING_MESSAGE);
                }
            }
            statusLabel.setText("已加载规则到图形化面板");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("解析规则失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyRulesGraphic() {
        if (currentData == null) return;
        String rulesStr = currentData.meta.get("rules");
        JsonValue rules;
        try {
            JsonReader reader = new JsonReader();
            rules = reader.parse(rulesStr != null ? rulesStr : "{}");
        } catch (Exception e) {
            rules = new JsonValue(JsonValue.ValueType.object);
        }

        for (FieldInfo field : ruleFields) {
            rules.remove(field.key);
            if (field.component instanceof JCheckBox) {
                boolean val = ((JCheckBox) field.component).isSelected();
                if (val != (Boolean) field.defaultValue) {
                    rules.addChild(field.key, new JsonValue(val));
                }
            } else if (field.component instanceof JTextField) {
                String text = ((JTextField) field.component).getText().trim();
                try {
                    if (field.type == Float.class) {
                        float val = Float.parseFloat(text);
                        if (Math.abs(val - (Float) field.defaultValue) > 1e-6) {
                            rules.addChild(field.key, new JsonValue(val));
                        }
                    } else if (field.type == Integer.class) {
                        int val = Integer.parseInt(text);
                        if (val != (Integer) field.defaultValue) {
                            rules.addChild(field.key, new JsonValue(val));
                        }
                    }
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(this, "字段 " + field.label + " 的值无效", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }

        String newRules = rules.toString();
        undoManager.saveState();
        currentData.meta.put("rules", newRules);
        // 同步更新表格
        updateMetaTableEntry(newRules);
        statusLabel.setText("规则已更新，请重建数据后保存");
    }

    private void handleDroppedFiles(List<File> files) {
        for (File file : files) {
            String name = file.getName().toLowerCase();

            // 1. 地图：任何时候都可以打开
            if (name.endsWith(".msav")) {
                openMapFile(file);
                continue;
            }

            // 2. 其它文件必须已打开地图
            if (currentData == null) {
                JOptionPane.showMessageDialog(this,
                        "请先打开一个地图，然后再导入补丁或规则。");
                return;
            }

            String activeMainTab = tabs.getTitleAt(tabs.getSelectedIndex());

            if ("Rules JSON".equals(activeMainTab)) {
                handleRulesJsonDrop(file);
            } else if ("Markers JSON".equals(activeMainTab)) {
                handleMarkersJsonDrop(file);
            } else {
                // "补丁" 或其它选项卡 → 走补丁导入
                handlePatchDrop(file);
            }
        }
    }

    private void handleRulesJsonDrop(File file) {
        try {
            String content = Files.readString(file.toPath());
            rulesJsonArea.setText(content);
            statusLabel.setText("已加载规则 JSON 到编辑器，请点击“应用修改”");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    printStackTrace("导入失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleMarkersJsonDrop(File file) {
        try {
            String content = Files.readString(file.toPath());
            markersJsonArea.setText(content);
            statusLabel.setText("已加载标记 JSON 到编辑器，请点击“应用修改”");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    printStackTrace("导入失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handlePatchDrop(File file) {
        CustomTypeIO.DataAssetType target = resolveTargetAssetType(file);
        if (target == null) return; // 用户取消或类型不支持

        try {
            switch (target) {
                case patch, content, bundle -> importTextAsset(file, target);
                case image                  -> importImageAsset(file);
                case sound, music           -> importAudioAsset(file, target);
            }
            updateUI();
            statusLabel.setText("已导入 " + target.displayed + ": " + file.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    printStackTrace("导入失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 决定拖入的文件应作为哪种资产导入。
     * 顺序：
     *   1. 文件扩展名 → 候选类型列表
     *   2. 当前补丁子选项卡若是具体类型且命中候选 → 用它
     *   3. 候选唯一 → 用它
     *   4. 候选多个 → 弹对话框
     */
    private CustomTypeIO.DataAssetType resolveTargetAssetType(File file) {
        String name = file.getName().toLowerCase();

        List<CustomTypeIO.DataAssetType> candidates = new ArrayList<>();
        if (name.endsWith(".png")) {
            candidates.add(CustomTypeIO.DataAssetType.image);
        } else if (name.endsWith(".ogg") || name.endsWith(".mp3")) {
            candidates.add(CustomTypeIO.DataAssetType.sound);
            candidates.add(CustomTypeIO.DataAssetType.music);
        } else if (name.endsWith(".properties")) {
            candidates.add(CustomTypeIO.DataAssetType.bundle);
        } else if (name.endsWith(".json") || name.endsWith(".hjson")
                || name.endsWith(".json5")) {
            candidates.add(CustomTypeIO.DataAssetType.patch);
            candidates.add(CustomTypeIO.DataAssetType.content);
        } else if (name.endsWith(".txt")) {
            candidates.add(CustomTypeIO.DataAssetType.patch);
        } else {
            JOptionPane.showMessageDialog(this,
                    "不支持的文件类型: " + file.getName(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // 1. 补丁选项卡且激活了具体类型子选项卡 → 优先
        String mainTab = tabs.getTitleAt(tabs.getSelectedIndex());
        if ("补丁".equals(mainTab)) {
            PatchTab active = getActivePatchTab();
            if (active != null && active.type != null
                    && candidates.contains(active.type)) {
                return active.type;
            }
        }

        // 2. 唯一候选直接返回
        if (candidates.size() == 1) return candidates.getFirst();

        // 3. 多候选：让用户选
        Object choice = JOptionPane.showInputDialog(this,
                "为 " + file.getName() + " 选择资产类型：",
                "选择导入类型",
                JOptionPane.QUESTION_MESSAGE,
                null,
                candidates.toArray(),
                candidates.getFirst());
        return (CustomTypeIO.DataAssetType) choice;
    }

    private void importTextAsset(File file, CustomTypeIO.DataAssetType type) throws IOException {
        String content = Files.readString(file.toPath());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        undoManager.saveState();

        if (type == CustomTypeIO.DataAssetType.bundle) {
            String lang = extractLangFromFileName(file.getName());
            MapReaderWriter.Bundle b = new MapReaderWriter.Bundle(lang, content);
            b.rawBytes = bytes;
            b.path = file.getName();
            currentData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(b);
        } else {
            MapReaderWriter.RawPatch rp = new MapReaderWriter.RawPatch(content, bytes);
            rp.path = file.getName();
            if (type == CustomTypeIO.DataAssetType.content) {
                // ContentType.item 的 ordinal 是 0，默认当作物品
                rp.contentTypeOrdinal = 0;
            }
            currentData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(rp);
        }
    }

    private void importImageAsset(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        BufferedImage img;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            img = ImageIO.read(bais);
        }
        if (img == null) {
            throw new IOException("无法解析 PNG 图片：" + file.getName());
        }

        undoManager.saveState();

        String name = stripExtension(file.getName());
        MapReaderWriter.DataImage di = new MapReaderWriter.DataImage(img, name, file.getName());
        di.rawBytes = bytes;
        currentData.patches
                .computeIfAbsent(CustomTypeIO.DataAssetType.image, k -> new ArrayList<>())
                .add(di);
    }

    private void importAudioAsset(File file, CustomTypeIO.DataAssetType type) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) throw new IOException("文件为空：" + file.getName());

        undoManager.saveState();

        String name = stripExtension(file.getName());
        MapReaderWriter.DataAsset da = (type == CustomTypeIO.DataAssetType.sound)
                ? new MapReaderWriter.DataSound(name, bytes)
                : new MapReaderWriter.DataMusic(name, bytes);
        da.path = file.getName();
        currentData.patches.computeIfAbsent(type, k -> new ArrayList<>()).add(da);
    }

    private static String extractLangFromFileName(String fileName) {
        String base = stripExtension(fileName);
        if (base.startsWith("bundles-")) return base.substring(8);
        return "en";
    }

    private static String makeBundlePath(String lang) {
        return MapReaderWriter.makeBundlePath(lang);
    }

    private JComboBox<String> buildBundleLangCombo(String currentLang) {
        // MapReaderWriter.LANGUAGES 是 Seq<String>，直接用
        Vector<String> langs = new Vector<>();
        for (int i = 0; i < MapReaderWriter.LANGUAGES.size; i++) {
            langs.add(MapReaderWriter.LANGUAGES.get(i));
        }
        // 保险：如果当前 lang 不在列表里（自定义语言），也加进去
        if (currentLang != null && !currentLang.isEmpty() && !langs.contains(currentLang)) {
            langs.addFirst(currentLang);
        }

        JComboBox<String> combo = new JComboBox<>(langs);
        if (currentLang != null) {
            combo.setSelectedItem(currentLang);
        }
        return combo;
    }

    private void importPatch(String content) {
        if (currentData == null) return;
        PatchTab tab = getActivePatchTab();
        CustomTypeIO.DataAssetType targetType =
                (tab != null && tab.type != null) ? tab.type : CustomTypeIO.DataAssetType.patch;

        undoManager.saveState();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MapReaderWriter.RawPatch rp = new MapReaderWriter.RawPatch(content, bytes);
        rp.path = "patch-" + UUID.randomUUID() + ".json";
        currentData.patches
                .computeIfAbsent(targetType, k -> new ArrayList<>())
                .add(rp);
        updateUI();
    }

    private void openMapFile(File file) {
        EditorWindow.closeAll();
        try {
            currentData = MapReaderWriter.readMapData(file);
            currentFile = file;
            undoManager = new UndoManager(); // 重置撤销管理器
            updateUI();
            statusLabel.setText("已加载: " + file.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, printStackTrace("读取失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String printStackTrace(String prefix, Throwable throwable) {
        String firstStackTraces = prefix + "\n" + throwable.toString();
        Seq<StackTraceElement> stackTrace = Seq.with(throwable.getStackTrace());
        stackTrace.removeAll(st -> st.getClassName().contains("MethodAccessor") || st.getClassName().substring(st.getClassName().lastIndexOf(".") + 1).equals("Method"));
        stackTrace.truncate(10);
        firstStackTraces += "\n" + stackTrace.toString("\n", st -> {
            String className = st.getClassName();
            return className.substring(className.lastIndexOf(".") + 1) + "." + st.getMethodName() + ": " + st.getLineNumber();
        });
        return firstStackTraces;
    }

    private void openFile(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Mindustry地图 (*.msav)", "msav"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openMapFile(fc.getSelectedFile());
        }
    }

    private void updateUI() {
        if (currentData == null) return;
        headerField.setText(currentData.headerStr);
        versionField.setText(String.valueOf(currentData.version));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < currentData.regionNames.size(); i++)
            sb.append(String.format("%-10s : %d字节\n", currentData.regionNames.get(i), currentData.regionSizes.get(i)));
        regionArea.setText(sb.toString());

        metaTableModel.setRowCount(0);
        for (Map.Entry<String, String> e : currentData.meta.entrySet())
            metaTableModel.addRow(new Object[]{e.getKey(), e.getValue()});


        // 清空所有子选项卡
        for (PatchTab tab : patchTabs) {
            tab.model.setRowCount(0);
            tab.rowRefs.clear();
            tab.allRows.clear();
            tab.allRefs.clear();
        }

        // 遍历所有类型，只填 "allRows" / "allRefs"
        for (Map.Entry<CustomTypeIO.DataAssetType, List<Object>> entry : currentData.patches.entrySet()) {
            CustomTypeIO.DataAssetType type = entry.getKey();
            List<Object> list = entry.getValue();
            if (list == null || list.isEmpty()) continue;

            for (int i = 0; i < list.size(); i++) {
                Object obj = list.get(i);
                String name = assetName(obj);
                String summary = assetSummary(type, obj);

                Object[] row = new Object[]{type.displayed, name, summary};
                Object[] ref = new Object[]{type, i};

                PatchTab allTab = patchTabs.getFirst();
                allTab.allRows.add(row.clone());
                allTab.allRefs.add(ref);

                for (int t = 1; t < patchTabs.size(); t++) {
                    PatchTab pt = patchTabs.get(t);
                    if (pt.type == type) {
                        pt.allRows.add(row.clone());
                        pt.allRefs.add(ref);
                        break;
                    }
                }
            }
        }

        // 应用当前搜索关键词到所有子选项卡
        applyFilterToAllTabs();

        if (currentData.markersJson != null) {
            markersJsonArea.setText(currentData.markersJson.prettyPrint(OutputType.json, 2));
        } else {
            markersJsonArea.setText("// markers 区域为空或无法解析");
        }
        markersModified = false;

        teamModel.setRowCount(0);
        for (var plan : currentData.teamBlocks) {
            String configStr = (plan.config == null) ? "null" : plan.config.toString();
            teamModel.addRow(new Object[]{plan.team, plan.x, plan.y,
                    plan.rotation, plan.blockId, configStr});
        }

        entityModel.setRowCount(0);
        for (var e : currentData.entities) {
            if (e.fields != null) {
                entityModel.addRow(new Object[]{
                        e.id, e.type, e.x, e.y,
                        e.fields.getOrDefault("team", "?"),
                        e.fields.getOrDefault("health", "?"),
                        e.fields.getOrDefault("rotation", "?")
                });
            } else {
                entityModel.addRow(new Object[]{e.id, e.type, e.x, e.y, "?", "?", "?"});
            }
        }
    }

    /** 全局搜索关键词；空串表示不过滤 */
    private String patchFilter = "";
    private boolean patchSearchNamesOnly = false;

    private void applyFilterToAllTabs() {
        for (PatchTab tab : patchTabs) {
            applyFilter(tab);
        }
        updatePatchTabTitles();
    }

    private void applyFilter(PatchTab tab) {
        tab.model.setRowCount(0);
        tab.rowRefs.clear();

        String filter = patchFilter;
        boolean empty = filter == null || filter.isEmpty();
        String lowerFilter = empty ? null : filter.toLowerCase(Locale.ROOT);

        for (int i = 0; i < tab.allRows.size(); i++) {
            Object[] row = tab.allRows.get(i);

            boolean match;
            if (empty) {
                match = true;
            } else {
                String typeName = (String) row[0];
                String name = (String) row[1];
                String summary = (String) row[2];

                match = containsIgnoreCase(name, lowerFilter);
                if (!match && !patchSearchNamesOnly) {
                    match = containsIgnoreCase(summary, lowerFilter)
                            || containsIgnoreCase(typeName, lowerFilter);
                }
            }

            if (match) {
                tab.model.addRow(row.clone());
                tab.rowRefs.add(tab.allRefs.get(i));
            }
        }
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        if (haystack == null || lowerNeedle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private void updatePatchTabTitles() {
        for (int t = 0; t < patchTabs.size(); t++) {
            PatchTab pt = patchTabs.get(t);
            int total = pt.allRows.size();
            int shown = pt.rowRefs.size();
            String label;
            if (patchFilter == null || patchFilter.isEmpty()) {
                label = pt.title + " (" + total + ")";
            } else {
                label = pt.title + " (" + shown + "/" + total + ")";
            }
            patchesSubTabs.setTitleAt(t, label);
        }
    }

    private void deleteSelectedTeamBlocks() {
        int[] rows = getSelectedModelRows(teamTable);
        if (rows.length == 0) return;
        undoManager.saveState();
        for (int i = rows.length - 1; i >= 0; i--) {
            currentData.teamBlocks.remove(rows[i]);
        }
        updateUI();
        statusLabel.setText("已删除 " + rows.length + " 个建筑计划");
    }

    private void applyTeamChanges() {
        if (currentData == null) return;
        try {
            undoManager.saveState();
            currentData.rebuildEntities(); // 这会重新生成整个 entities 区域
            // 重新解析 entities 以更新显示
            byte[] entitiesData = currentData.getRegionData("entities");
            if (entitiesData != null) {
                MapReaderWriter.reparseEntities(entitiesData, currentData);
            }
            updateUI();
            statusLabel.setText("Team Blocks 更改已应用");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("应用失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // 补丁操作
    private void addPatch() {
        PatchTab tab = getActivePatchTab();
        CustomTypeIO.DataAssetType targetType =
                (tab != null && tab.type != null) ? tab.type : CustomTypeIO.DataAssetType.patch;

        // 非文本类型不应该走这条路
        if (targetType == CustomTypeIO.DataAssetType.image
                || targetType == CustomTypeIO.DataAssetType.sound
                || targetType == CustomTypeIO.DataAssetType.music) {
            // 直接转发到文件导入
            importPatches();
            return;
        }

        JDialog dialog = new JDialog(this, "输入新补丁 (" + targetType.displayed + ")", true);
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(true);

        JPanel panel = new JPanel(new BorderLayout());
        RSyntaxTextArea textArea = new RSyntaxTextArea(20, 60);
        textArea.setSyntaxEditingStyle(
                targetType == CustomTypeIO.DataAssetType.bundle
                        ? SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
                        : SyntaxConstants.SYNTAX_STYLE_JSON);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        ThemeManager.applyToNewEditor(textArea);   // ★
        panel.add(new RTextScrollPane(textArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton okButton = new JButton("确定");
        JButton cancelButton = new JButton("取消");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        okButton.addActionListener(ev -> {
            String s = textArea.getText();
            if (s == null || s.trim().isEmpty()) { dialog.dispose(); return; }
            if (currentData == null) { dialog.dispose(); return; }
            undoManager.saveState();

            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            MapReaderWriter.RawPatch rp = new MapReaderWriter.RawPatch(s, bytes);
            rp.path = "patch-" + UUID.randomUUID() + ".json";
            if (targetType == CustomTypeIO.DataAssetType.content) {
                rp.contentTypeOrdinal = 0; // 默认 item，用户可改
            }
            currentData.patches
                    .computeIfAbsent(targetType, k -> new ArrayList<>())
                    .add(rp);

            updateUI();
            statusLabel.setText("已添加 " + targetType.displayed + "，请重建数据后保存");
            dialog.dispose();
        });
        cancelButton.addActionListener(ev -> dialog.dispose());

        dialog.add(panel);
        dialog.setVisible(true);
    }

    private void viewAsset(PatchTab tab, int row) {
        if (tab == null || row < 0 || row >= tab.rowRefs.size()) return;
        Object[] ref = tab.rowRefs.get(row);
        CustomTypeIO.DataAssetType type = (CustomTypeIO.DataAssetType) ref[0];
        int idx = (Integer) ref[1];
        List<Object> list = currentData.patches.get(type);
        if (list == null || idx >= list.size()) return;
        Object obj = list.get(idx);

        switch (type) {
            case patch, content, bundle -> viewTextAsset(type, idx, obj);
            case image -> viewImageAsset(obj);
            case sound, music -> viewAudioAsset(type, obj);
        }
    }

    private void viewTextAsset(CustomTypeIO.DataAssetType type, int idx, Object obj) {
        EditorWindow.open(this, type, idx, obj);
    }

    /** 编辑器改动后刷新主窗口 */
    public void refreshAfterEdit() {
        updateUI();
        statusLabel.setText("补丁已更新，请重建数据后保存");
    }

    /** 编辑器应用前压一次撤销快照 */
    public void pushUndoSnapshot() {
        undoManager.saveState();
    }

    /** v11/v12 的 String 类型补丁原地替换（String 不可变） */
    public void replaceStringPatchAt(CustomTypeIO.DataAssetType type, int idx, String newText) {
        if (currentData == null) return;
        List<Object> list = currentData.patches.get(type);
        if (list != null && idx >= 0 && idx < list.size()) {
            list.set(idx, newText);
        }
    }

    private void viewImageAsset(Object obj) {
        if (!(obj instanceof MapReaderWriter.DataImage di) || di.img == null) {
            JOptionPane.showMessageDialog(this,
                    "该图片资产没有内嵌数据（hash-only），无法预览。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "查看图片 " + di.name, true);
        dialog.setSize(700, 600);
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new BorderLayout());

        JLabel info = new JLabel(String.format("路径: %s   尺寸: %dx%d   字节: %d",
                di.path,
                di.img.getWidth(), di.img.getHeight(),
                di.rawBytes != null ? di.rawBytes.length : -1));
        info.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(info, BorderLayout.NORTH);

        JLabel imageLabel = new JLabel(new ImageIcon(di.img));
        JScrollPane scroll = new JScrollPane(imageLabel);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout());
        JButton saveBtn = new JButton("导出为 PNG");
        saveBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(di.name + ".png"));
            if (fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    ImageIO.write(di.img, "png", fc.getSelectedFile());
                    statusLabel.setText("图片已导出");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, printStackTrace("导出失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        buttons.add(saveBtn);

        JButton replaceBtn = new JButton("替换...");
        replaceBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG 图片", "png"));
            if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    BufferedImage newImg = ImageIO.read(fc.getSelectedFile());
                    if (newImg == null) {
                        JOptionPane.showMessageDialog(dialog, "无法解析该图片", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(newImg, "png", baos);
                    byte[] bytes = baos.toByteArray();
                    undoManager.saveState();
                    di.img = newImg;
                    di.rawBytes = bytes;
                    di.byteHash = null;   // 强制重新内嵌
                    updateUI();
                    statusLabel.setText("图片已替换");
                    dialog.dispose();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, printStackTrace("替换失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        buttons.add(replaceBtn);

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        buttons.add(closeBtn);

        panel.add(buttons, BorderLayout.SOUTH);
        dialog.add(panel);
        dialog.setVisible(true);
    }

    private void viewAudioAsset(CustomTypeIO.DataAssetType type, Object obj) {
        if (!(obj instanceof MapReaderWriter.DataAsset da)) return;

        JDialog dialog = new JDialog(this, "音频资产 " + type.displayed, true);
        dialog.setSize(560, 260);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // ============ 信息区 ============
        JPanel infoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        int row = 0;
        addInfoRow(infoPanel, gbc, row++, "类型:", type.displayed);
        addInfoRow(infoPanel, gbc, row++, "路径:", da.path != null ? da.path : "(无)");

        boolean hashOnly = da.byteHash != null;
        addInfoRow(infoPanel, gbc, row++, "存储方式:", hashOnly ? "hash-only（引用外部缓存）" : "内嵌字节");
        addInfoRow(infoPanel, gbc, row++, "大小:",
                da.rawBytes != null ? da.rawBytes.length + " B" : "—");

        if (hashOnly) {
            addInfoRow(infoPanel, gbc, row++, "hash:", bytesToHexPrefix(da.byteHash, 16) + "...");
        }

        panel.add(infoPanel, BorderLayout.CENTER);

        // ============ 按钮区 ============
        JPanel buttons = new JPanel(new FlowLayout());

        // 替换
        JButton replaceBtn = new JButton("替换...");
        replaceBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "音频文件 (*.ogg, *.mp3)", "ogg", "mp3"));
            if (fc.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;

            File chosen = fc.getSelectedFile();
            try {
                byte[] newBytes = Files.readAllBytes(chosen.toPath());
                if (newBytes.length == 0) {
                    JOptionPane.showMessageDialog(dialog, "文件为空", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 决定新扩展名：优先用用户选的文件，其次保留原扩展名
                String ext = extensionOf(chosen.getName());
                if (ext == null) {
                    ext = extensionOf(da.path);
                    if (ext == null) ext = "ogg";
                }

                // 更新路径：保留目录部分，替换文件名 + 扩展名
                String newPath = replaceFileName(da.path, chosen.getName(), ext);

                undoManager.saveState();

                // 更新 DataAsset 的通用字段
                da.path = newPath;
                da.rawBytes = newBytes;
                da.byteHash = null;   // ★ 置 null → 写回时走 embedded 分支

                // 如果是 sound / music，同时同步 name 字段
                if (obj instanceof MapReaderWriter.DataSound ds) {
                    ds.name = stripExtension(newPath);
                } else if (obj instanceof MapReaderWriter.DataMusic dm) {
                    dm.name = stripExtension(newPath);
                }

                statusLabel.setText("音频已替换：" + newBytes.length + " B");
                updateUI();
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        printStackTrace("读取文件失败: ", ex),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttons.add(replaceBtn);

        // 导出
        JButton exportBtn = new JButton("导出");
        exportBtn.addActionListener(e -> {
            if (da.rawBytes == null) {
                JOptionPane.showMessageDialog(dialog,
                        "该资产没有内嵌数据（hash-only），无法导出。",
                        "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JFileChooser fc = new JFileChooser();
            String suggested = da.path != null ? stripDirectory(da.path) : (type.displayed + ".ogg");
            fc.setSelectedFile(new File(suggested));
            if (fc.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            try {
                Files.write(fc.getSelectedFile().toPath(), da.rawBytes);
                statusLabel.setText("已导出音频");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        printStackTrace("导出失败: ", ex),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttons.add(exportBtn);

        // 关闭
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        buttons.add(closeBtn);

        panel.add(buttons, BorderLayout.SOUTH);
        dialog.add(panel);
        dialog.setVisible(true);
    }

    private void editPatch() {
        PatchTab tab = getActivePatchTab();
        if (tab == null) return;

        int[] rows = getSelectedModelRows(tab.table);
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择一个补丁");
            return;
        }
        if (rows.length > 1) {
            JOptionPane.showMessageDialog(this, "一次只能编辑一个补丁");
            return;
        }
        viewAsset(tab, rows[0]);
    }

    private void removePatch() {
        PatchTab tab = getActivePatchTab();
        if (tab == null) return;

        int[] rows = getSelectedModelRows(tab.table);
        if (rows.length == 0) return;

        undoManager.saveState();

        List<Object[]> toRemove = new ArrayList<>();
        for (int r : rows) {
            if (r < tab.rowRefs.size()) toRemove.add(tab.rowRefs.get(r));
        }

        // 同类型内索引降序删，避免错位
        toRemove.sort((a, b) -> {
            CustomTypeIO.DataAssetType ta = (CustomTypeIO.DataAssetType) a[0];
            CustomTypeIO.DataAssetType tb = (CustomTypeIO.DataAssetType) b[0];
            if (ta != tb) return Integer.compare(tb.ordinal(), ta.ordinal());
            return Integer.compare((Integer) b[1], (Integer) a[1]);
        });

        // 通知编辑器关闭对应 tab
        for (Object[] ref : toRemove) {
            CustomTypeIO.DataAssetType type = (CustomTypeIO.DataAssetType) ref[0];
            int idx = (Integer) ref[1];
            List<Object> list = currentData.patches.get(type);
            if (list != null && idx < list.size()) {
                EditorWindow.closeTabFor(list.get(idx));
            }
        }

        for (Object[] ref : toRemove) {
            CustomTypeIO.DataAssetType type = (CustomTypeIO.DataAssetType) ref[0];
            int idx = (Integer) ref[1];
            List<Object> list = currentData.patches.get(type);
            if (list != null && idx < list.size()) list.remove(idx);
        }

        updateUI();
        statusLabel.setText("已删除 " + rows.length + " 个补丁");
    }

    private void importPatches() {
        PatchTab tab = getActivePatchTab();
        CustomTypeIO.DataAssetType target = (tab != null && tab.type != null)
                ? tab.type
                : CustomTypeIO.DataAssetType.patch;

        // 文件选择器的过滤器按类型走
        String[] exts;
        String desc;
        switch (target) {
            case patch, content -> {
                exts = new String[]{"json", "hjson", "json5", "txt"};
                desc = "补丁/内容 (*.json;*.hjson;*.json5;*.txt)";
            }
            case bundle -> {
                exts = new String[]{"properties"};
                desc = "语言包 (*.properties)";
            }
            case image -> {
                exts = new String[]{"png"};
                desc = "图片 (*.png)";
            }
            case sound, music -> {
                exts = new String[]{"ogg", "mp3"};
                desc = "音频 (*.ogg;*.mp3)";
            }
            default -> {
                exts = new String[]{"json"};
                desc = "所有支持的类型";
            }
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(desc, exts));
        fc.setMultiSelectionEnabled(true);   // ★ 允许一次选多个

        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File[] files = fc.getSelectedFiles();
        if (files.length == 0) return;

        int ok = 0, fail = 0;
        for (File file : files) {
            try {
                switch (target) {
                    case patch, content -> importTextAsset(file, target);
                    case bundle         -> importTextAsset(file, CustomTypeIO.DataAssetType.bundle);
                    case image          -> importImageAsset(file);
                    case sound, music   -> importAudioAsset(file, target);
                    default -> throw new IOException("未知类型: " + target);
                }
                ok++;
            } catch (Exception ex) {
                fail++;
                System.err.println("导入 " + file.getName() + " 失败: " + ex.getMessage());
            }
        }

        updateUI();
        statusLabel.setText("导入 " + ok + " 个" + target.displayed
                + (fail > 0 ? "（" + fail + " 个失败）" : ""));
    }

    private void exportSelectedPatch() {
        PatchTab tab = getActivePatchTab();
        if (tab == null) return;

        int[] rows = getSelectedModelRows(tab.table);
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择要导出的补丁");
            return;
        }

        List<Object[]> assets = new ArrayList<>();   // [DataAssetType, Object]
        for (int r : rows) {
            if (r >= tab.rowRefs.size()) continue;
            Object[] ref = tab.rowRefs.get(r);
            CustomTypeIO.DataAssetType type = (CustomTypeIO.DataAssetType) ref[0];
            int idx = (Integer) ref[1];
            List<Object> list = currentData.patches.get(type);
            if (list != null && idx < list.size()) {
                assets.add(new Object[]{type, list.get(idx)});
            }
        }
        if (assets.isEmpty()) return;

        exportAssetsAsZip(assets, "assets.zip");
    }

    private void exportAllPatches() {
        if (currentData == null || currentData.patches.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的补丁");
            return;
        }

        List<Object[]> assets = new ArrayList<>();
        for (Map.Entry<CustomTypeIO.DataAssetType, List<Object>> e : currentData.patches.entrySet()) {
            if (e.getValue() == null) continue;
            for (Object a : e.getValue()) {
                assets.add(new Object[]{e.getKey(), a});
            }
        }
        if (assets.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的补丁");
            return;
        }

        exportAssetsAsZip(assets, "assets.zip");
    }

    private void exportAssetsAsZip(java.util.List<Object[]> assets, String defaultName) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(defaultName));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "资源包 (*.zip)", "zip"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(".zip")) {
            out = new File(out.getPath() + ".zip");
        }

        int written = 0, skipped = 0;
        Set<String> usedPaths = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(Deflater.BEST_COMPRESSION);

            for (Object[] entry : assets) {
                CustomTypeIO.DataAssetType type = (CustomTypeIO.DataAssetType) entry[0];
                Object asset = entry[1];

                String zipPath = buildAssetZipPath(type, asset);
                if (zipPath == null) {
                    skipped++;
                    System.err.println("跳过（无法确定路径）: " + asset);
                    continue;
                }
                zipPath = uniquifyPath(usedPaths, zipPath);
                usedPaths.add(zipPath);

                byte[] data = assetBytes(type, asset);
                if (data == null) {
                    skipped++;
                    System.err.println("跳过（无内容）: " + zipPath);
                    continue;
                }

                ZipEntry ze = new ZipEntry(zipPath);
                ze.setTime(System.currentTimeMillis());
                zos.putNextEntry(ze);
                zos.write(data);
                zos.closeEntry();
                written++;
            }

            // 附一份说明
            String readme =
                    "Mindustry asset pack\n" +
                            "Exported by Map Editor\n" +
                            "Assets: " + written + "\n" +
                            "Date:   " + new java.util.Date() + "\n";
            ZipEntry readmeEntry = new ZipEntry("README.txt");
            readmeEntry.setTime(System.currentTimeMillis());
            zos.putNextEntry(readmeEntry);
            zos.write(readme.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    printStackTrace("导出失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        statusLabel.setText("已导出 " + written + " 个资产"
                + (skipped > 0 ? "（跳过 " + skipped + " 个）" : "")
                + " → " + out.getName());
    }

    /** 计算资产在 zip 中的完整路径 */
    private static String buildAssetZipPath(CustomTypeIO.DataAssetType type, Object asset) {
        String rawPath = assetRawPath(asset);
        if (rawPath == null || rawPath.isEmpty()) return null;
        rawPath = rawPath.replace('\\', '/');
        // 去掉前导斜杠，防止 ZipEntry 报错
        while (rawPath.startsWith("/")) rawPath = rawPath.substring(1);

        switch (type) {
            case patch:
                return "patches/" + rawPath;
            case content: {
                String folder = "units";
                if (asset instanceof MapReaderWriter.RawPatch rp) {
                    folder = contentFolderName(rp.contentTypeOrdinal);
                }
                return "content/" + folder + "/" + rawPath;
            }
            case bundle:
                return "bundles/" + rawPath;
            case image:
                return "sprites/" + rawPath;
            case sound:
                return "sounds/" + rawPath;
            case music:
                return "music/" + rawPath;
        }
        return null;
    }

    /** 从资产对象里取出文件名（可含子目录，不含类型前缀） */
    private static String assetRawPath(Object asset) {
        if (asset instanceof MapReaderWriter.DataAsset da
                && da.path != null && !da.path.isEmpty()) {
            return da.path;
        }
        if (asset instanceof MapReaderWriter.Bundle b) {
            return MapReaderWriter.makeBundlePath(b.lang);
        }
        return null;
    }

    /** ContentType.ordinal → 原版 folderName */
    private static String contentFolderName(short ordinal) {
        return switch (ordinal) {
            case 0 -> "items";
            case 1 -> "blocks";
            case 4 -> "liquids";
            case 5 -> "statuses";    // ← 复数
            case 7 -> "weather";
            default -> "units";
        };
    }

    /** 同名冲突时加 _1、_2 后缀 */
    private static String uniquifyPath(Set<String> used, String path) {
        if (!used.contains(path)) return path;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        String base = (dot > slash) ? path.substring(0, dot) : path;
        String ext  = (dot > slash) ? path.substring(dot) : "";
        int n = 1;
        String candidate;
        do {
            candidate = base + "_" + n + ext;
            n++;
        } while (used.contains(candidate));
        return candidate;
    }

    /** 把资产对象转为字节数组（UTF-8 文本或原始二进制） */
    private static byte[] assetBytes(CustomTypeIO.DataAssetType type, Object asset) {
        switch (type) {
            case patch, content -> {
                if (asset instanceof MapReaderWriter.RawPatch rp) {
                    if (rp.rawBytes != null) return rp.rawBytes;
                    if (rp.content != null) return rp.content.getBytes(StandardCharsets.UTF_8);
                }
                if (asset instanceof String s) {
                    return s.getBytes(StandardCharsets.UTF_8);
                }
                return null;
            }
            case bundle -> {
                if (asset instanceof MapReaderWriter.Bundle b) {
                    if (b.rawBytes != null) return b.rawBytes;
                    if (b.content != null) return b.content.getBytes(StandardCharsets.UTF_8);
                }
                return null;
            }
            case image -> {
                if (asset instanceof MapReaderWriter.DataImage di) {
                    if (di.rawBytes != null) return di.rawBytes;
                    if (di.img != null) {
                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(di.img, "png", baos);
                            return baos.toByteArray();
                        } catch (IOException e) {
                            System.err.println("图片转 PNG 失败: " + e.getMessage());
                            return null;
                        }
                    }
                }
                return null;
            }
            case sound, music -> {
                if (asset instanceof MapReaderWriter.DataAsset da && da.rawBytes != null) {
                    return da.rawBytes;
                }
                return null;
            }
        }
        return null;
    }

    private void updatePatches() {
        // 数据由 viewAsset / addPatch / removePatch 直接修改 currentData.patches，
        // 表格只是视图，不再从表格反向同步。
    }

    /** 向 GridBagLayout 里加一行 "label: value" */
    private void addInfoRow(JPanel panel, GridBagConstraints gbc, int row, String label, String value) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        JTextField tf = new JTextField(value);
        tf.setEditable(false);
        tf.setBorder(null);
        tf.setBackground(panel.getBackground());
        panel.add(tf, gbc);
    }

    /** 提取文件扩展名（小写、不含点），无扩展名返回 null */
    private static String extensionOf(String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot < slash) return null;
        return name.substring(dot + 1).toLowerCase();
    }

    /** 去掉目录，返回文件名 */
    private static String stripDirectory(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 去掉扩展名，返回不含扩展名的文件名 */
    private static String stripExtension(String path) {
        String name = stripDirectory(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 用新文件名 + 扩展名替换原 path 的文件名部分，保留目录。
     * 如果原 path 没有目录，直接返回新名字。
     */
    private static String replaceFileName(String oldPath, String newFileName, String newExt) {
        String dir = "";
        if (oldPath != null) {
            int slash = Math.max(oldPath.lastIndexOf('/'), oldPath.lastIndexOf('\\'));
            if (slash >= 0) dir = oldPath.substring(0, slash + 1);
        }
        // 新文件名的 basename（不含扩展名）
        String base = newFileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        // 用选中的文件名作为 basename，扩展名用 newExt
        return dir + base + "." + newExt;
    }

    // Entities 操作
    private void deleteSelectedEntities() {
        int[] rows = getSelectedModelRows(entityTable);
        if (rows.length == 0) return;
        undoManager.saveState();
        List<MapReaderWriter.EntityInfo> toRemove = new ArrayList<>();
        for (int i = rows.length - 1; i >= 0; i--) {
            toRemove.add(currentData.entities.get(rows[i]));
        }
        currentData.entities.removeAll(toRemove);
        updateUI();
        statusLabel.setText("已删除 " + rows.length + " 个单位");
    }

    private void editSelectedEntity() {
        int[] rows = getSelectedModelRows(entityTable);
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先勾选要编辑的实体");
            return;
        }
        if (rows.length > 1) {
            JOptionPane.showMessageDialog(this, "一次只能编辑一个实体");
            return;
        }
        var info = currentData.entities.get(rows[0]);

        JTextField xField = new JTextField(String.valueOf(info.x));
        JTextField yField = new JTextField(String.valueOf(info.y));
        JTextField teamField = new JTextField(String.valueOf(info.team));
        JTextField healthField = new JTextField(info.fields != null ? info.fields.getOrDefault("health", 0f).toString() : "0");
        JTextField rotField = new JTextField(info.fields != null ? info.fields.getOrDefault("rotation", 0f).toString() : "0");

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5,5,5,5);
        gbc.gridx = 0; gbc.gridy = 0; panel.add(new JLabel("X:"), gbc);
        gbc.gridx = 1; panel.add(xField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; panel.add(new JLabel("Y:"), gbc);
        gbc.gridx = 1; panel.add(yField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; panel.add(new JLabel("队伍:"), gbc);
        gbc.gridx = 1; panel.add(teamField, gbc);
        gbc.gridx = 0; gbc.gridy = 3; panel.add(new JLabel("健康值:"), gbc);
        gbc.gridx = 1; panel.add(healthField, gbc);
        gbc.gridx = 0; gbc.gridy = 4; panel.add(new JLabel("旋转:"), gbc);
        gbc.gridx = 1; panel.add(rotField, gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, "编辑实体", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                float newX = Float.parseFloat(xField.getText());
                float newY = Float.parseFloat(yField.getText());
                int newTeam = Integer.parseInt(teamField.getText());
                float newHealth = Float.parseFloat(healthField.getText());
                float newRot = Float.parseFloat(rotField.getText());

                undoManager.saveState();

                info.setPosition(newX, newY);
                info.setTeam(newTeam);
                info.setHealth(newHealth);
                info.setRotation(newRot);

                updateUI();
                statusLabel.setText("实体已修改，请点击“应用更改”保存");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "请输入有效的数字", "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void applyEntitiesChanges() {
        if (currentData == null) return;
        try {
            undoManager.saveState();
            currentData.rebuildEntities();
            byte[] entitiesData = currentData.getRegionData("entities");
            if (entitiesData != null) {
                MapReaderWriter.reparseEntities(entitiesData, currentData);
            }
            updateUI();
            statusLabel.setText("Entities 更改已应用");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("应用失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reparseEntities() {
        if (currentData == null) return;
        byte[] entitiesData = currentData.getRegionData("entities");
        if (entitiesData == null || entitiesData.length == 0) {
            JOptionPane.showMessageDialog(this, "entities 区域为空");
            return;
        }
        try {
            undoManager.saveState();
            MapReaderWriter.reparseEntities(entitiesData, currentData);
            updateUI();
            statusLabel.setText("Entities 重新解析完成");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("重新解析失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // Markers JSON
    private void loadMarkersJson() {
        if (currentData == null) return;
        if (currentData.markersJson != null) {
            markersJsonArea.setText(currentData.markersJson.prettyPrint(OutputType.json, 2));
            markersModified = false;
        } else {
            markersJsonArea.setText("// markers 区域为空或无法解析");
        }
    }

    private void applyMarkersJson() {
        if (currentData == null) return;
        String text = markersJsonArea.getText().trim();
        if (text.isEmpty()) {
            undoManager.saveState();
            currentData.markersJson = null;
            currentData.setRegionData("markers", new byte[0]);
            markersModified = true;
            statusLabel.setText("Markers 已清空，请重建数据后保存");
            return;
        }
        try {
            JsonReader jsonReader = new JsonReader();
            JsonValue newJson = jsonReader.parse(text);
            undoManager.saveState();
            currentData.markersJson = newJson;
            markersModified = true;
            statusLabel.setText("Markers JSON 已更新，请重建数据后保存");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("JSON 解析失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportMarkersJson() {
        if (currentData == null || currentData.markersJson == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("markers.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
                pw.print(markersJsonArea.getText());
                statusLabel.setText("已导出 markers JSON");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, printStackTrace("导出失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importMarkersJson() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON 文件", "json"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String json = Files.readString(fc.getSelectedFile().toPath());
                markersJsonArea.setText(json);
                statusLabel.setText("已加载 JSON 到编辑器，请点击“应用修改”");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, printStackTrace("导入失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void formatMarkersJson() {
        String text = markersJsonArea.getText().trim();
        if (text.isEmpty()) return;
        try {
            JsonReader reader = new JsonReader();
            JsonValue value = reader.parse(text);
            markersJsonArea.setText(value.prettyPrint(OutputType.json, 2));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("JSON 格式错误: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // Rules JSON
    private void loadRulesJson() {
        if (currentData == null) return;
        String rules = currentData.meta.get("rules");
        if (rules != null) {
            try {
                JsonReader reader = new JsonReader();
                JsonValue value = reader.parse(rules);
                rulesJsonArea.setText(value.prettyPrint(OutputType.json, 2));
            } catch (Exception e) {
                rulesJsonArea.setText(rules);
            }
        } else {
            rulesJsonArea.setText("// 地图元数据中不包含 rules 字段");
        }
    }

    private void applyRulesJson() {
        if (currentData == null) return;
        String text = rulesJsonArea.getText().trim();
        if (text.isEmpty()) {
            undoManager.saveState();
            currentData.meta.remove("rules");
            updateMetaTableEntry(null); // 可选：移除表格中的对应行？这里简单置空
            statusLabel.setText("Rules 已移除");
            return;
        }
        try {
            JsonReader reader = new JsonReader();
            JsonValue value = reader.parse(text);
            String compact = value.toString();
            undoManager.saveState();
            currentData.meta.put("rules", compact);
            // 同步更新表格
            updateMetaTableEntry(compact);
            statusLabel.setText("Rules JSON 已更新");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("JSON 格式错误: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportRulesJson() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("rules.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (PrintWriter pw = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
                pw.print(rulesJsonArea.getText());
                statusLabel.setText("已导出 Rules JSON");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, printStackTrace("JSON 导出失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importRulesJson() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON 文件", "json"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String json = Files.readString(fc.getSelectedFile().toPath());
                rulesJsonArea.setText(json);
                statusLabel.setText("已加载 JSON 到编辑器，请点击“应用修改”");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, printStackTrace("导入失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void formatRulesJson() {
        String text = rulesJsonArea.getText().trim();
        if (text.isEmpty()) return;
        try {
            JsonReader reader = new JsonReader();
            JsonValue value = reader.parse(text);
            rulesJsonArea.setText(value.prettyPrint(OutputType.json, 2));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, printStackTrace("JSON 格式错误: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void syncMeta() {
        if (currentData == null) return;
        Map<String, String> preserved = new LinkedHashMap<>(currentData.meta);
        for (int i = 0; i < metaTableModel.getRowCount(); i++) {
            String k = (String) metaTableModel.getValueAt(i, 0);
            String v = (String) metaTableModel.getValueAt(i, 1);
            preserved.put(k, v);
        }
        currentData.meta.clear();
        currentData.meta.putAll(preserved);
    }

    private void rebuildData() {
        if (currentData == null) return;
        try {
            syncMeta();
            updatePatches();

            // markers 已经通过 applyMarkersJson 保存，但可能未重建
            // 直接调用 rebuildRegions 即可

            currentData.rebuildRegions();
            statusLabel.setText("数据重建完成，可保存");
            updateUI();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, printStackTrace("重建失败: ", e), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveFile(ActionEvent e) {
        if (currentData == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(currentFile != null ? currentFile.getName() : "new_map.msav"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Mindustry地图 (*.msav)", "msav"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".msav")) f = new File(f.getPath() + ".msav");
            try {
                syncMeta();
                updatePatches();
                if (markersModified) {
                    JOptionPane.showMessageDialog(this, "Markers 有未应用的修改，请先点击“应用修改”");
                    return;
                }
                currentData.rebuildRegions();
                MapReaderWriter.writeMapData(f, currentData);
                statusLabel.setText("已保存: " + f.getName());
                JOptionPane.showMessageDialog(this, "保存成功！");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, printStackTrace("保存失败: ", ex), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ThemeManager.applyInitial();          // ★ 在 new MapEditorGUI() 之前
            new MapEditorGUI().setVisible(true);
        });
    }
}