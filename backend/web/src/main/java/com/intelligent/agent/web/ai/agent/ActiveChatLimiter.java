package com.intelligent.agent.web.ai.agent;

/**
 * 活跃流式对话上限（WS + SSE 共用）：防止并发聊天流无界占用线程资源。
 * 超过上限时 tryAcquire 立即返回 false，由入口转成"服务繁忙"（503 / WS error 事件），
 * 而不是无限排队；上限可通过 runtime 配置 stream_concurrency 调整。
 */
public class ActiveChatLimiter {

    private final Object lock = new Object();
    private volatile int maxConcurrency;
    private int active;

    public ActiveChatLimiter(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /** 非阻塞获取一个流式对话槽位；满则立即返回 false。*/
    public boolean tryAcquire() {
        synchronized (lock) {
            if (active >= maxConcurrency) {
                return false;
            }
            active++;
            return true;
        }
    }

    /** 流结束（complete/error/cancel）时释放槽位。*/
    public void release() {
        synchronized (lock) {
            if (active > 0) {
                active--;
            }
        }
    }

    public int active() {
        synchronized (lock) {
            return active;
        }
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int max) {
        synchronized (lock) {
            this.maxConcurrency = Math.max(1, max);
        }
    }
}
