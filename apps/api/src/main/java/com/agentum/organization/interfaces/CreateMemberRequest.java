package com.agentum.organization.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateMemberRequest(
    @NotBlank(message = "请输入用户名") @Size(max = 100, message = "用户名不能超过 100 个字符") String username,
    @NotBlank(message = "请输入初始密码") @Size(min = 8, max = 80, message = "初始密码长度应为 8 到 80 个字符") String password,
    @NotBlank(message = "请输入成员姓名") @Size(max = 100, message = "成员姓名不能超过 100 个字符") String displayName,
    @Size(max = 255, message = "邮箱不能超过 255 个字符") String email,
    UUID departmentId,
    UUID roleId,
    @Size(max = 80, message = "空间编码不能超过 80 个字符") String spaceCode
) {
}
