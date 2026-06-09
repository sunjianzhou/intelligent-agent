/**
 * 生成轻量级唯一 ID（无外部依赖）。
 * 格式：时间戳 36 进制 + 4 位随机，碰撞概率 < 1/1,679,616 per ms。
 */
export const genId = () =>
  Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
