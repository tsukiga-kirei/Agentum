export const usernameRuleMessage = "账号需以英文字母开头，仅支持英文、数字、下划线和短横线，长度 3-50 位";

const usernamePattern = /^[A-Za-z][A-Za-z0-9_-]{2,49}$/;

export function isValidUsername(username: string): boolean {
  return usernamePattern.test(username.trim());
}
