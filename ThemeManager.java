import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InputStream;
import java.util.prefs.Preferences;

public final class ThemeManager {
    private ThemeManager() {}

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String KEY_DARK = "darkMode";
    private static final String KEY_FONT_FAMILY = "uiFontFamily";
    private static final String KEY_FONT_SIZE   = "uiFontSize";
    private static final String KEY_FONT_OFFSET_Y = "uiFontOffsetY";
    private static Font BUNDLED_CODE_FONT;

    /** 我们曾经 put 过的所有 key；切换前先 remove，避免 user 层残留 */
    private static final String[] OVERRIDE_KEYS = {
            // 下面这些是历史遗留：之前 applyOverrides 里 put 过，必须清掉
            "TextField.background",  "TextField.foreground",
            "TextArea.background",   "TextArea.foreground",
            "TextPane.background",   "TextPane.foreground",
            "EditorPane.background", "EditorPane.foreground",
            "Table.background",      "Table.foreground",
            "List.background",       "List.foreground",
            "ComboBox.background",   "ComboBox.foreground",
            // 下面这些是当前 applyOverrides 真正要设的
            "Component.focusColor",
            "Component.focusedBorderColor",
            "Table.alternateRowColor",
            "defaultFont",
    };

    public static boolean isDark() {
        return PREFS.getBoolean(KEY_DARK, false);
    }

    private static void setDarkPref(boolean dark) {
        PREFS.putBoolean(KEY_DARK, dark);
    }

    /** 在创建任何 Swing 组件之前调用一次 */
    public static void applyInitial() {
        loadBundledFonts();     // ★ 只加载一次
        setup(isDark());
    }

    /** 运行时切换 —— FlatLaf 下切换非常便宜 */
    public static void apply(Window ignored, boolean dark) {
        setup(dark);
    }

    private static boolean dark0() {
        return isDark();
    }

    private static void setup(boolean dark) {
        setDarkPref(dark);

        UIDefaults d = UIManager.getDefaults();
        for (String k : OVERRIDE_KEYS) d.remove(k);

        if (dark) FlatDarkLaf.setup();
        else      FlatLightLaf.setup();

        // ★ 装完 LAF 之后应用自定义字体
        applyCustomFont();

        applyOverrides(dark);

        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
            applyEditorTheme(w, dark);
            w.revalidate();
            w.repaint();
        }
    }

    private static void applyCustomFont() {
        String family = PREFS.get(KEY_FONT_FAMILY, null);
        int size = PREFS.getInt(KEY_FONT_SIZE, 13);
        float offsetY = PREFS.getFloat(KEY_FONT_OFFSET_Y, 0f);

        // 情况 A：用户没设自定义字体，但有偏移 → 在 LAF 默认字体上加偏移
        if (family == null || size <= 0) {
            if (offsetY == 0f) return;   // 默认值，什么都不做
            Font base = UIManager.getFont("defaultFont");
            if (base == null) return;
            UIManager.put("defaultFont", applyVerticalOffset(base, offsetY));
            return;
        }

        // 情况 B：用户设了自定义字体
        Font base = new Font(family, Font.PLAIN, size);
        if (!family.equalsIgnoreCase(base.getFamily())) {
            // 字体不存在 → 清掉所有字体相关偏好
            PREFS.remove(KEY_FONT_FAMILY);
            PREFS.remove(KEY_FONT_SIZE);
            return;
        }

        Font adjusted = applyVerticalOffset(base, offsetY);
        // ★ 不要用 FontUIResource 包装——它会把 AffineTransform 丢掉
        UIManager.put("defaultFont", adjusted);
    }

    /**
     * FlatLaf 的默认配色已经很好看了，这里只做少量调整。
     * 全部可选 —— 你觉得哪不对再放开。
     */
    private static void applyOverrides(boolean dark) {
        // 只保留真正的样式调整。
        // ★ 不再 put TextField.background / Table.background 之类 —— 让 FlatLaf 自己管。
        if (dark) {
            UIManager.put("Component.focusColor",         new Color(0x2F65CA));
            UIManager.put("Component.focusedBorderColor", new Color(0x2F65CA));
            UIManager.put("Table.alternateRowColor",      new Color(0x383838));
        } else {
            UIManager.put("Table.alternateRowColor",      new Color(0xF5F5F5));
        }
    }

    // ==================== RSyntaxTextArea 主题（与之前相同） ====================

    public static void applyEditorTheme(Container root, boolean dark) {
        if (root == null) return;
        Theme theme = loadEditorTheme(dark);
        if (theme == null) return;
        applyEditorThemeRec(root, theme);
    }

    private static void applyEditorThemeRec(Container root, Theme theme) {
        Font codeFont = getCurrentCodeFont();
        applyEditorThemeRec(root, theme, codeFont);
    }

    private static void applyEditorThemeRec(Container root, Theme theme, Font codeFont) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof RSyntaxTextArea area) {
                theme.apply(area);
                area.setFont(getCurrentCodeFont());
            }
            if (comp instanceof Container child) {
                applyEditorThemeRec(child, theme, codeFont);
            }
        }
    }

    /** 新建的 RSyntaxTextArea 立即应用当前主题 + 字体 */
    public static void applyToNewEditor(RSyntaxTextArea area) {
        Theme t = loadEditorTheme(isDark());
        if (t != null) t.apply(area);
        area.setFont(getCurrentCodeFont());
    }

    private static Theme loadEditorTheme(boolean dark) {
        String path = dark
                ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
        try (InputStream is = ThemeManager.class.getResourceAsStream(path)) {
            return is != null ? Theme.load(is) : null;
        } catch (IOException e) {
            System.err.println("加载编辑器主题失败: " + path + " - " + e.getMessage());
            return null;
        }
    }

    /** 当前 UI 字体族；未设置时返回 null（表示用 FlatLaf 默认） */
    public static String getUIFontFamily() {
        return PREFS.get(KEY_FONT_FAMILY, null);
    }

    /** 当前 UI 字号；未设置时返回 -1 */
    public static int getUIFontSize() {
        return PREFS.getInt(KEY_FONT_SIZE, -1);
    }

    /** 是否设置了自定义字体 */
    public static boolean hasCustomFont() {
        return getUIFontFamily() != null;
    }

    /** 当前实际使用的 UI 字体（用于对话框初始值） */
    public static Font getCurrentUIFont() {
        Font f = UIManager.getFont("defaultFont");
        if (f != null) return f;
        // fallback
        return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    private static boolean isMonospaced(Font f) {
        FontMetrics fm = new Canvas().getFontMetrics(f);
        return fm.charWidth('i') == fm.charWidth('W');
    }

    /** 对新建对话框应用当前 UI 主题（FlatLaf 下基本不需要，但作为保险） */
    public static void applyToNewDialog(Window dialog) {
        SwingUtilities.updateComponentTreeUI(dialog);
        applyEditorTheme(dialog, isDark());
    }

    private static void loadBundledFonts() {
        if (BUNDLED_CODE_FONT != null) return;
        try (InputStream is = ThemeManager.class.getResourceAsStream(
                "/fonts/MapleMono-CN-Regular.ttf")) {   // ← 换文件名
            if (is == null) {
                System.err.println("内置 Maple Mono CN 未找到");
                return;
            }
            Font base = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(base);
            BUNDLED_CODE_FONT = base;
        } catch (Exception e) {
            System.err.println("内置字体加载失败: " + e.getMessage());
        }
    }

    /** 代码编辑器主字体（拉丁/ASCII） */
    public static Font getCurrentCodeFont() {
        int size = getUIFontSize();
        if (size <= 0) size = 13;
        if (BUNDLED_CODE_FONT != null) {
            return BUNDLED_CODE_FONT.deriveFont((float) size);
        }
        String family = getUIFontFamily();
        if (family != null) {
            Font test = new Font(family, Font.PLAIN, size);
            if (isMonospaced(test)) return test;
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    public static float getFontVerticalOffset() {
        return PREFS.getFloat(KEY_FONT_OFFSET_Y, 0f);
    }

    /** 一次性设置字体 + 字号 + 垂直偏移 */
    public static void setUIFont(String family, int size, float offsetY) {
        if (family == null) {
            PREFS.remove(KEY_FONT_FAMILY);
            PREFS.remove(KEY_FONT_SIZE);
        } else {
            PREFS.put(KEY_FONT_FAMILY, family);
            PREFS.putInt(KEY_FONT_SIZE, size);
        }
        PREFS.putFloat(KEY_FONT_OFFSET_Y, offsetY);
        apply(null, isDark());
    }

    /** 兼容旧调用：只改字体字号，保留当前偏移 */
    public static void setUIFont(String family, int size) {
        setUIFont(family, size, getFontVerticalOffset());
    }

    /** 只改偏移 */
    public static void setFontVerticalOffset(float offsetY) {
        PREFS.putFloat(KEY_FONT_OFFSET_Y, offsetY);
        apply(null, isDark());
    }

    /** 对字体应用垂直偏移。offsetY > 0 向下 */
    public static Font applyVerticalOffset(Font font, float offsetY) {
        if (font == null || offsetY == 0f) return font;
        return font.deriveFont(AffineTransform.getTranslateInstance(0, offsetY));
    }

    /** 当前配置下的 UI 字体（带偏移） */
    public static Font getUIFontWithOffset() {
        return applyVerticalOffset(getCurrentUIFont(), getFontVerticalOffset());
    }
}