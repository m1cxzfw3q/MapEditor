import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.JsonWriter.OutputType;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class EditorWindow extends JDialog {

    private static EditorWindow instance;

    private final Map<Object, EditorTab> openTabs = new IdentityHashMap<>();
    private final JTabbedPane tabbedPane;
    private MapEditorGUI owner;

    private EditorWindow(MapEditorGUI owner) {
        super(owner, "补丁编辑器", false);
        this.owner = owner;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { tryClose(); }
        });
        setSize(960, 680);
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        // ★ 底部那一排整体删掉 —— 不再需要
        // 只把 tabbedPane 放到中间
        add(tabbedPane, BorderLayout.CENTER);

        ThemeManager.applyToNewDialog(this);
    }

    // ==================== 对外 API ====================

    public static void open(MapEditorGUI owner, CustomTypeIO.DataAssetType type,
                            int idx, Object asset) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new EditorWindow(owner);
        }
        instance.owner = owner;
        instance.openInternal(type, idx, asset);
        if (!instance.isVisible()) instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    /** 关闭某个资产的 tab（补丁被删时调用） */
    public static void closeTabFor(Object asset) {
        if (instance == null) return;
        EditorTab t = instance.openTabs.get(asset);
        if (t != null) instance.closeTab(t, true);
    }

    /** 关闭所有 tab（撤销/重做/打开新地图时调用） */
    public static void closeAll() {
        if (instance == null) return;
        for (EditorTab t : new ArrayList<>(instance.openTabs.values())) {
            instance.closeTab(t, true);
        }
        instance.setVisible(false);
    }

    /** 主题切换时刷新所有 tab */
    public static void refreshTheme() {
        if (instance == null) return;
        for (EditorTab t : instance.openTabs.values()) t.refreshTheme();
        ThemeManager.applyToNewDialog(instance);
        instance.repaint();
    }

    // ==================== 内部逻辑 ====================

    private static final Set<CustomTypeIO.ContentType> LOADABLE_CONTENT_TYPES = Set.of(
            CustomTypeIO.ContentType.item,
            CustomTypeIO.ContentType.block,
            CustomTypeIO.ContentType.liquid,
            CustomTypeIO.ContentType.status,
            CustomTypeIO.ContentType.unit,
            CustomTypeIO.ContentType.weather
    );

    /** 构造内容类型下拉框，ordinal 越界时回退到 unit */
    private static JComboBox<CustomTypeIO.ContentType> buildContentTypeCombo(short ordinal) {
        CustomTypeIO.ContentType[] all = CustomTypeIO.ContentType.values();
        JComboBox<CustomTypeIO.ContentType> combo = new JComboBox<>(all);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof CustomTypeIO.ContentType ct) {
                    String text = ct.name + " (" + ct.name() + ")";
                    if (!LOADABLE_CONTENT_TYPES.contains(ct)) text += "  — 不可加载";
                    setText(text);
                }
                return this;
            }
        });
        if (ordinal >= 0 && ordinal < all.length) {
            combo.setSelectedIndex(ordinal);
        } else {
            combo.setSelectedIndex(CustomTypeIO.ContentType.unit.ordinal());
        }
        return combo;
    }

    /** 构造语言下拉框，当前 lang 不在列表里时插到最前 */
    private static JComboBox<String> buildBundleLangCombo(String currentLang) {
        java.util.Vector<String> langs = new java.util.Vector<>();
        for (int i = 0; i < MapReaderWriter.LANGUAGES.size; i++) {
            langs.add(MapReaderWriter.LANGUAGES.get(i));
        }
        if (currentLang != null && !currentLang.isEmpty() && !langs.contains(currentLang)) {
            langs.addFirst(currentLang);
        }
        JComboBox<String> combo = new JComboBox<>(langs);
        if (currentLang != null) combo.setSelectedItem(currentLang);
        return combo;
    }

    private void openInternal(CustomTypeIO.DataAssetType type, int idx, Object asset) {
        EditorTab existing = openTabs.get(asset);
        if (existing != null) {
            tabbedPane.setSelectedComponent(existing.panel);
            return;
        }
        EditorTab tab = new EditorTab(type, idx, asset);
        openTabs.put(asset, tab);
        tabbedPane.addTab("", tab.panel);
        tabbedPane.setTabComponentAt(tabbedPane.getTabCount() - 1, tab.tabHeader);
        tabbedPane.setSelectedComponent(tab.panel);
    }

    private void tryClose() {
        List<EditorTab> dirty = new ArrayList<>();
        for (EditorTab t : openTabs.values()) if (t.isDirty()) dirty.add(t);

        if (!dirty.isEmpty()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "有 " + dirty.size() + " 个选项卡存在未应用的修改。\n" +
                            "是：应用并关闭    否：放弃并关闭    取消：返回",
                    "未应用修改",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) {
                for (EditorTab t : dirty) t.apply();
            }
        }
        setVisible(false);
    }

    private void closeTab(EditorTab tab, boolean silent) {
        if (!silent && tab.isDirty()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "该选项卡有未应用的修改，关闭前是否应用？",
                    "未应用修改", JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) tab.apply();
        }
        openTabs.remove(tab.asset);
        tabbedPane.remove(tab.panel);
        if (openTabs.isEmpty()) setVisible(false);
    }

    // ==================== Tab ====================

    private class EditorTab {
        final CustomTypeIO.DataAssetType type;
        final Object asset;
        int idx;
        final RSyntaxTextArea textArea;
        final JPanel panel;
        final JPanel tabHeader;
        final JLabel titleLabel;
        final JLabel pathLabel;                                 // ★ 新增
        JComboBox<CustomTypeIO.ContentType> contentTypeCombo;   // ★ 新增（仅 content）
        JComboBox<String> bundleLangCombo;                      // ★ 新增（仅 bundle）
        String originalText;
        final String baseTitle;

        EditorTab(CustomTypeIO.DataAssetType type, int idx, Object asset) {
            this.type = type;
            this.idx = idx;
            this.asset = asset;
            this.baseTitle = makeTitle(type, asset);
            this.originalText = extractText(asset);

            // ---- 文本区 ----
            textArea = new RSyntaxTextArea(20, 80);
            textArea.setText(originalText);
            if (type == CustomTypeIO.DataAssetType.bundle) {
                textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE);
            } else {
                textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
            }
            textArea.setCodeFoldingEnabled(true);
            textArea.setAntiAliasingEnabled(true);
            ThemeManager.applyToNewEditor(textArea);

            // ---- 顶部：path + 内容类型 / 语言 ----
            JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

            String pathText = "(无 path)";
            if (asset instanceof MapReaderWriter.DataAsset da && da.path != null) {
                pathText = da.path;
            } else if (asset instanceof MapReaderWriter.Bundle b) {
                pathText = MapReaderWriter.makeBundlePath(b.lang);
            }
            pathLabel = new JLabel("Path: " + pathText);
            topPanel.add(pathLabel);

            if (type == CustomTypeIO.DataAssetType.content
                    && asset instanceof MapReaderWriter.RawPatch rp) {
                topPanel.add(new JLabel("   内容类型:"));
                contentTypeCombo = buildContentTypeCombo(rp.contentTypeOrdinal);
                topPanel.add(contentTypeCombo);

                JLabel hint = new JLabel("（仅 item / block / liquid / status / unit / weather 可被游戏加载）");
                hint.setForeground(Color.GRAY);
                topPanel.add(hint);

                contentTypeCombo.addActionListener(e -> updateTitleMark());

            } else if (type == CustomTypeIO.DataAssetType.bundle
                    && asset instanceof MapReaderWriter.Bundle b) {
                topPanel.add(new JLabel("   语言:"));
                bundleLangCombo = buildBundleLangCombo(b.lang);
                topPanel.add(bundleLangCombo);

                JLabel hint = new JLabel("（影响文件名 bundle[_xx_XX].properties）");
                hint.setForeground(Color.GRAY);
                topPanel.add(hint);

                bundleLangCombo.addActionListener(e -> {
                    String lang = (String) bundleLangCombo.getSelectedItem();
                    if (lang != null) {
                        pathLabel.setText("Path: " + MapReaderWriter.makeBundlePath(lang));
                    }
                    updateTitleMark();
                });
            }

            // ---- 底部一排按钮（tab 级在左，窗口级在右）----
            JPanel bottom = new JPanel(new BorderLayout());

            // 左侧：tab 级操作
            JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

            JButton applyBtn = new JButton("应用");
            applyBtn.addActionListener(e -> apply());
            leftBtns.add(applyBtn);

            JButton resetBtn = new JButton("还原");
            resetBtn.addActionListener(e -> {
                textArea.setText(originalText);
                textArea.setCaretPosition(0);

                if (contentTypeCombo != null && asset instanceof MapReaderWriter.RawPatch rp) {
                    if (rp.contentTypeOrdinal >= 0
                            && rp.contentTypeOrdinal < CustomTypeIO.ContentType.values().length) {
                        contentTypeCombo.setSelectedIndex(rp.contentTypeOrdinal);
                    }
                }
                if (bundleLangCombo != null && asset instanceof MapReaderWriter.Bundle b) {
                    if (b.lang != null) {
                        bundleLangCombo.setSelectedItem(b.lang);
                        pathLabel.setText("Path: " + MapReaderWriter.makeBundlePath(b.lang));
                    }
                }
                updateTitleMark();
            });
            leftBtns.add(resetBtn);

            JButton formatBtn = new JButton("格式化");
            formatBtn.addActionListener(e -> formatJson());
            leftBtns.add(formatBtn);

            JButton unescapeBtn = new JButton("反转义 \\uXXXX");
            unescapeBtn.addActionListener(e ->
                    textArea.setText(UnicodeEscape.unescape(textArea.getText())));
            leftBtns.add(unescapeBtn);

            JButton escapeBtn = new JButton("转义为 \\uXXXX");
            escapeBtn.addActionListener(e ->
                    textArea.setText(UnicodeEscape.escape(textArea.getText())));
            leftBtns.add(escapeBtn);

            // 右侧：窗口级操作（从构造器挪过来）
            JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));

            JButton applyAllBtn = new JButton("应用全部");
            applyAllBtn.addActionListener(e -> {
                for (EditorTab t : new ArrayList<>(openTabs.values())) t.apply();
            });
            rightBtns.add(applyAllBtn);

            JButton closeAllBtn = new JButton("关闭所有");
            closeAllBtn.addActionListener(e -> tryClose());
            rightBtns.add(closeAllBtn);

            bottom.add(leftBtns, BorderLayout.WEST);
            bottom.add(rightBtns, BorderLayout.EAST);

            panel = new JPanel(new BorderLayout());
            panel.add(topPanel, BorderLayout.NORTH);                 // ★
            panel.add(new RTextScrollPane(textArea), BorderLayout.CENTER);
            panel.add(bottom, BorderLayout.SOUTH);

            // ---- 自定义 tab 头部（标题 + 关闭按钮）----
            tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            tabHeader.setOpaque(false);
            titleLabel = new JLabel(baseTitle);
            tabHeader.add(titleLabel);

            JButton closeBtn = new JButton("×");
            closeBtn.setMargin(new Insets(0, 4, 0, 4));
            closeBtn.setBorderPainted(false);
            closeBtn.setContentAreaFilled(false);
            closeBtn.setFocusPainted(false);
            closeBtn.setToolTipText("关闭此选项卡");
            closeBtn.addActionListener(e -> closeTab(this, false));
            tabHeader.add(closeBtn);

            refreshHeaderForeground();

            // ---- 文本变更 → 更新 tab 标题的 * 标记 ----
            textArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { updateTitleMark(); }
                @Override public void removeUpdate(DocumentEvent e) { updateTitleMark(); }
                @Override public void changedUpdate(DocumentEvent e) { updateTitleMark(); }
            });
        }

        // ---------- 内容读写 ----------

        private String extractText(Object obj) {
            if (obj instanceof MapReaderWriter.RawPatch rp) return rp.content != null ? rp.content : "";
            if (obj instanceof MapReaderWriter.Bundle b)    return b.content != null ? b.content : "";
            if (obj instanceof String s)                    return s;
            return "";
        }

        boolean isDirty() {
            if (!textArea.getText().equals(originalText)) return true;

            if (contentTypeCombo != null && asset instanceof MapReaderWriter.RawPatch rp) {
                CustomTypeIO.ContentType ct = (CustomTypeIO.ContentType) contentTypeCombo.getSelectedItem();
                if (ct != null && ct.ordinal() != rp.contentTypeOrdinal) return true;
            }
            if (bundleLangCombo != null && asset instanceof MapReaderWriter.Bundle b) {
                String lang = (String) bundleLangCombo.getSelectedItem();
                return lang != null && !lang.equals(b.lang);
            }
            return false;
        }

        void apply() {
            String newText = textArea.getText();
            boolean textChanged = !newText.equals(originalText);
            boolean metaChanged = false;

            // 内容类型
            if (contentTypeCombo != null && asset instanceof MapReaderWriter.RawPatch rp) {
                CustomTypeIO.ContentType ct = (CustomTypeIO.ContentType) contentTypeCombo.getSelectedItem();
                if (ct != null) {
                    short newOrd = (short) ct.ordinal();
                    if (newOrd != rp.contentTypeOrdinal) {
                        rp.contentTypeOrdinal = newOrd;
                        metaChanged = true;
                    }
                }
            }

            // 语言
            if (bundleLangCombo != null && asset instanceof MapReaderWriter.Bundle b) {
                String newLang = (String) bundleLangCombo.getSelectedItem();
                if (newLang != null && !newLang.equals(b.lang)) {
                    b.lang = newLang;
                    b.path = MapReaderWriter.makeBundlePath(newLang);
                    pathLabel.setText("Path: " + b.path);
                    metaChanged = true;
                }
            }

            if (!textChanged && !metaChanged) return;

            owner.pushUndoSnapshot();

            if (textChanged) {
                if (asset instanceof MapReaderWriter.RawPatch rp) {
                    rp.content = newText;
                    rp.rawBytes = newText.getBytes(StandardCharsets.UTF_8);
                } else if (asset instanceof MapReaderWriter.Bundle b) {
                    b.content = newText;
                    b.rawBytes = newText.getBytes(StandardCharsets.UTF_8);
                } else if (asset instanceof String) {
                    owner.replaceStringPatchAt(type, idx, newText);
                }
                originalText = newText;
            }

            updateTitleMark();
            owner.refreshAfterEdit();
        }

        // ---------- 视觉 ----------

        private void updateTitleMark() {
            titleLabel.setText(baseTitle + (isDirty() ? " *" : ""));
        }

        private void refreshHeaderForeground() {
            Color fg = UIManager.getColor("Label.foreground");
            if (fg == null) fg = Color.BLACK;
            tabHeader.setForeground(fg);
            titleLabel.setForeground(fg);
            for (Component c : tabHeader.getComponents()) {
                c.setForeground(fg);
            }
        }

        void refreshTheme() {
            ThemeManager.applyToNewEditor(textArea);
            refreshHeaderForeground();
        }

        // ---------- 格式化 ----------

        private void formatJson() {
            if (type == CustomTypeIO.DataAssetType.bundle) return;
            try {
                JsonReader r = new JsonReader();
                JsonValue v = r.parse(textArea.getText());
                textArea.setText(v.prettyPrint(OutputType.json, 2));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(EditorWindow.this,
                        "JSON 格式错误: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ==================== 标题生成 ====================

    private static String makeTitle(CustomTypeIO.DataAssetType type, Object asset) {
        String name = null;
        if (asset instanceof MapReaderWriter.DataAsset da && da.path != null) {
            name = stripDir(da.path);
        } else if (asset instanceof MapReaderWriter.Bundle b) {
            name = MapReaderWriter.makeBundlePath(b.lang);
        }
        if (name == null || name.isEmpty()) name = type.displayed;
        if (name.length() > 28) name = name.substring(0, 26) + "...";
        return name;
    }

    private static String stripDir(String p) {
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}