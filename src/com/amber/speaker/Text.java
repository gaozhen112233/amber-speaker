package com.amber.speaker;

/**
 * 空白判断。看着多余，其实是为了避开一个很实际的问题。
 *
 * <p>Java 的 {@code String.trim()} 只去掉 {@code <= U+0020} 的字符，
 * 而中文排版里最常见的空格是<b>全角空格 U+3000</b>，{@code trim()} 对它无效。
 *
 * <p>后果不是「多一个空格」这么轻：
 * <ul>
 *   <li>{@code 〔歪头〕　你好} 这种用全角空格分隔的句子，判断「这行是不是只有〔〕」会得出
 *       「不是」，于是纯动作行变成一个空气泡；</li>
 *   <li>接口收到 {@code "　"}（一个全角空格）时，{@code length() == 1}，判空失效，
 *       她会收到一句压根不存在的话；</li>
 *   <li>字幕最后一行只有空白时，气泡里空一块，看着像卡住了。</li>
 * </ul>
 *
 * <p>桌宠用的是 .NET 的 {@code Trim()}，它是 Unicode-aware 的，所以那边没这个问题。
 * 移植到 Java 必须补上，否则两边行为差异只在特定输入下才暴露，最难查。
 */
public final class Text {

    private Text() {
    }

    /** 是不是空白：半角空格、全角空格、制表、换行都算。null 当空白。 */
    public static boolean isBlank(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isBlankChar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isBlankChar(char c) {
        return c <= ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** 去掉首尾空白，含全角空格。 */
    public static String trim(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && isBlankChar(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlankChar(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    public static String trimEnd(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && isBlankChar(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
