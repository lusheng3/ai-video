package com.dovideo.enums;

import lombok.Getter;

/**
 * 账户权限级别（对应 user_info.account_type TINYINT 字段）
 */
@Getter
public enum AccountTypeEnum {

    FREE(0, "普通免费用户"),
    VIP(1, "付费VIP用户");

    private final int code;
    private final String description;

    AccountTypeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
