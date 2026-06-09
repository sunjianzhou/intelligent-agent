/**
 * 解析 JWT payload 并判断是否已过期。
 *
 * 关键细节：JWT exp 字段的单位是【秒】，Date.now() 的单位是【毫秒】，
 * 必须将 exp * 1000 再与 Date.now() 比较，否则任何 token 都会被误判为已过期。
 *
 * 本函数仅用于客户端 UX 决策（如是否跳转登录页），
 * 不作为安全决策依据（签名验证由服务端完成）。
 *
 * @param {string} token  JWT 字符串
 * @returns {boolean}     true = 已过期或解析失败；false = 未过期
 */
export function isTokenExpired(token) {
  try {
    // JWT 使用 base64url（- 替代 +，_ 替代 /，无填充），atob() 只接受标准 base64
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64))
    return payload.exp * 1000 < Date.now()
  } catch {
    return true // 解析失败视为过期，强制跳登录
  }
}

/**
 * 从 JWT token 中读取 payload（不验证签名）。
 * @param {string} token
 * @returns {object|null}
 */
export function parseTokenPayload(token) {
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(b64))
  } catch {
    return null
  }
}
