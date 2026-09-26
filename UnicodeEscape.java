public final class UnicodeEscape {

    private UnicodeEscape() {}

    /**
     * 把 \\uXXXX 转义序列还原为实际字符。
     * - 支持 BMP 及代理对（如 \\uD83D\\uDE00 → 😀）
     * - 非法序列（hex 不足、非 hex 字符）原样保留
     * - 不区分 \\\\uXXXX（转义反斜杠 + u）—— 见文末说明
     */
    public static String unescape(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;

        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            // 需要满足：\ u X X X X（i+5 < n 保证 4 位 hex 都能访问）
            if (c == '\\' && i + 5 < n && s.charAt(i + 1) == 'u') {
                int code = 0;
                boolean ok = true;
                for (int k = 0; k < 4; k++) {
                    int d = Character.digit(s.charAt(i + 2 + k), 16);
                    if (d < 0) { ok = false; break; }
                    code = (code << 4) | d;
                }
                if (ok) {
                    sb.append((char) code);
                    i += 6;   // 跳过 \ u X X X X
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 把非 ASCII 字符转义为 \\uXXXX。
     * - ASCII 可见字符（0x00-0x7F）保留
     * - 中文、emoji 等一律转义；代理对会得到两个 \\uXXXX
     */
    public static String escape(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else {
                appendHex4(sb, c);
            }
        }
        return sb.toString();
    }

    /** 用 4 位十六进制 + \\u 前缀写入，避免 String.format 的性能开销 */
    private static void appendHex4(StringBuilder sb, int c) {
        sb.append('\\').append('u');
        sb.append(hexDigit((c >> 12) & 0xF));
        sb.append(hexDigit((c >> 8)  & 0xF));
        sb.append(hexDigit((c >> 4)  & 0xF));
        sb.append(hexDigit(c & 0xF));
    }

    private static char hexDigit(int v) {
        return (char)(v < 10 ? '0' + v : 'A' + v - 10);
    }

    /** 文本里是否可能包含 \\uXXXX 转义序列（用于决定是否启用按钮） */
    public static boolean containsEscape(String s) {
        if (s == null) return false;
        int i = 0, n = s.length();
        while (i < n - 5) {
            if (s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                boolean ok = true;
                for (int k = 2; k <= 5; k++) {
                    if (Character.digit(s.charAt(i + k), 16) < 0) { ok = false; break; }
                }
                if (ok) return true;
            }
            i++;
        }
        return false;
    }
}