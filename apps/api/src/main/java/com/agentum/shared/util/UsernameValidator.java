package com.agentum.shared.util;

import java.util.regex.Pattern;

public final class UsernameValidator {

    public static final String RULE_MESSAGE = "账号需以英文字母开头，仅支持英文、数字、下划线和短横线，长度 3-50 位";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,49}$");

    private UsernameValidator() {
    }

    public static boolean isValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }
}
