import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

public class FontChooserDialog extends JDialog {

    public record Result(String family, int size, float offsetY) {}

    private final String[] allFamilies;                 // ★ 全量字体族
    private final DefaultListModel<String> listModel;   // ★ 动态列表模型
    private final JList<String> familyList;
    private final JTextField searchField;               // ★ 搜索框
    private final JSpinner sizeSpinner, offsetSpinner;
    private final JLabel previewLabel;
    private boolean confirmed = false;
    private Result result;

    private FontChooserDialog(Window owner, String currentFamily, int currentSize, float currentOffsetY) {
        super(owner, "选择字体", ModalityType.APPLICATION_MODAL);
        setSize(560, 600);
        setLocationRelativeTo(owner);

        // ---- 全量字体族 ----
        allFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();

        // ---- 列表模型（支持过滤）----
        listModel = new DefaultListModel<>();
        for (String f : allFamilies) listModel.addElement(f);

        familyList = new JList<>(listModel);
        familyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        familyList.setVisibleRowCount(16);

        // 初始选中
        if (currentFamily != null) {
            familyList.setSelectedValue(currentFamily, true);
        }
        if (familyList.getSelectedIndex() < 0 && allFamilies.length > 0) {
            familyList.setSelectedValue(Font.MONOSPACED, true);
            if (familyList.getSelectedIndex() < 0) familyList.setSelectedIndex(0);
        }

        // ---- 搜索框 ----
        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "搜索字体...");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        // ---- 字号 ----
        sizeSpinner = new JSpinner(new SpinnerNumberModel(currentSize > 0 ? currentSize : 13, 8, 72, 1));

        offsetSpinner = new JSpinner(new SpinnerNumberModel(currentOffsetY, -6.0, 6.0, 0.5));
        offsetSpinner.setToolTipText("正值下移，负值上移（单位：像素）");

        // ---- 预览 ----
        previewLabel = new JLabel("你好，世界  Hello, World  1234567890");
        previewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        previewLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)));

        // 监听
        familyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview();
        });
        sizeSpinner.addChangeListener(e -> updatePreview());
        offsetSpinner.addChangeListener(e -> updatePreview());

        updatePreview();

        // ---- 布局 ----
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 顶部：搜索框 + 字号
        JPanel topPanel = new JPanel(new BorderLayout(8, 4));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("搜索:"), BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        topPanel.add(searchRow, BorderLayout.NORTH);

        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sizeRow.add(new JLabel("字号:"));
        sizeRow.add(sizeSpinner);
        sizeRow.add(new JLabel("    垂直偏移:"));
        sizeRow.add(offsetSpinner);
        topPanel.add(sizeRow, BorderLayout.SOUTH);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 中部：字体列表
        mainPanel.add(new JScrollPane(familyList), BorderLayout.CENTER);

        // 南部：预览 + 按钮
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(previewLabel, BorderLayout.CENTER);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton resetBtn  = new JButton("重置为默认");
        JButton okBtn     = new JButton("确定");
        JButton cancelBtn = new JButton("取消");
        buttonRow.add(resetBtn);
        buttonRow.add(okBtn);
        buttonRow.add(cancelBtn);
        south.add(buttonRow, BorderLayout.SOUTH);

        mainPanel.add(south, BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // ---- 事件 ----
        okBtn.addActionListener(e -> {
            String fam = familyList.getSelectedValue();
            int sz = (Integer) sizeSpinner.getValue();
            float off = ((Number) offsetSpinner.getValue()).floatValue();
            if (fam != null) {
                result = new Result(fam, sz, off);
                confirmed = true;
            }
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());
        resetBtn.addActionListener(e -> {
            result = null;   // 调用方按"重置"处理
            confirmed = true;
            dispose();
        });

        // 回车 = 确定
        getRootPane().setDefaultButton(okBtn);

        // 主题跟随
        ThemeManager.applyToNewDialog(this);
    }

    /**
     * 根据搜索框内容过滤字体族列表。
     * 过滤时保留已选中项（如果它仍在结果中）。
     */
    private void applyFilter() {
        String keyword = searchField.getText().trim().toLowerCase();
        String selected = familyList.getSelectedValue();

        listModel.clear();
        if (keyword.isEmpty()) {
            for (String f : allFamilies) listModel.addElement(f);
        } else {
            for (String f : allFamilies) {
                if (f.toLowerCase().contains(keyword)) {
                    listModel.addElement(f);
                }
            }
        }

        // 尽量恢复选中态
        if (selected != null && listModel.contains(selected)) {
            familyList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            familyList.setSelectedIndex(0);
        } else {
            // 没有匹配项 → 预览显示提示
            previewLabel.setFont(previewLabel.getFont());
            previewLabel.setText("(无匹配字体)");
        }
    }

    private void updatePreview() {
        String fam = familyList.getSelectedValue();
        int sz = (Integer) sizeSpinner.getValue();
        float off = ((Number) offsetSpinner.getValue()).floatValue();
        if (fam == null) return;
        previewLabel.setText("你好，世界  Hello, World  1234567890");
        Font f = new Font(fam, Font.PLAIN, sz);
        if (off != 0f) {
            f = f.deriveFont(java.awt.geom.AffineTransform.getTranslateInstance(0, off));
        }
        previewLabel.setFont(f);
    }

    /**
     * 返回 null 表示用户取消。
     * result.family() == null 表示用户点击了"重置"。
     */
    public static Result show(Window owner, String currentFamily, int currentSize, float currentOffsetY) {
        FontChooserDialog d = new FontChooserDialog(owner, currentFamily, currentSize, currentOffsetY);
        d.setVisible(true);
        if (!d.confirmed) return null;
        return d.result != null ? d.result : new Result(null, 0, 0f);
    }
}