package com.libramind.catalog;

import com.libramind.common.api.BusinessException;

/** 规范化并校验 ISBN，支持 ISBN-10 转 ISBN-13，不接受只满足长度的错误号码。 */
public final class Isbn {
    private Isbn() {}

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (value.matches("[0-9]{9}[0-9X]")) {
            int sum = 0;
            for (int i = 0; i < 10; i++) {
                sum += (10 - i) * (value.charAt(i) == 'X' ? 10 : value.charAt(i) - '0');
            }
            if (sum % 11 != 0) {
                throw BusinessException.invalid("ISBN-10 校验位不正确");
            }
            String prefix = "978" + value.substring(0, 9);
            return prefix + checkDigit(prefix);
        }
        if (!value.matches("97[89][0-9]{10}")
                || value.charAt(12) - '0' != checkDigit(value.substring(0, 12))) {
            throw BusinessException.invalid("请输入有效的 ISBN-13 或 ISBN-10");
        }
        return value;
    }

    private static int checkDigit(String prefix) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (prefix.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10;
    }
}
