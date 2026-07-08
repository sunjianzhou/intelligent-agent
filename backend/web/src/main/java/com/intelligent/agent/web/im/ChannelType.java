package com.intelligent.agent.web.im;

/**
 * Channel 类型枚举（与 Python ChannelType 语义一致）。
 */
public enum ChannelType {

    FEISHU("feishu_im"),
    WECOM("wecom"),
    WEB("web"),
    TELEGRAM("telegram");

    private final String value;

    ChannelType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ChannelType fromValue(String value) {
        for (ChannelType ct : values()) {
            if (ct.value.equals(value)) {
                return ct;
            }
        }
        throw new IllegalArgumentException("Unknown channel type: " + value);
    }
}
