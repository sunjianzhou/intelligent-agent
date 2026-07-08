package com.intelligent.agent.web.im;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户信息（channel-agnostic）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    private String userId;
    private String displayName;
    private String avatarUrl;
    private ChannelType channel;
}
