package com.donkeytt.journeysync.network;

/**
 * Minimal token-bucket rate limiter used to throttle tile transfers.
 */
public class RateLimiter {

    private final long capacity;
    private final long refillPerMillis;
    private long tokens;
    private long lastRefill;

    /** @param bytesPerSecond 0 = unlimited */
    public RateLimiter(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            this.capacity = Long.MAX_VALUE;
            this.refillPerMillis = 0;
            this.tokens = Long.MAX_VALUE;
        } else {
            this.capacity = bytesPerSecond;
            this.refillPerMillis = bytesPerSecond / 1000L;
            this.tokens = bytesPerSecond;
        }
        this.lastRefill = System.currentTimeMillis();
    }

    /** Block until {@code bytes} can be consumed. */
    public synchronized void acquire(int bytes) {
        if (refillPerMillis == 0) {
            return;
        }
        while (true) {
            refill();
            if (tokens >= bytes) {
                tokens -= bytes;
                return;
            }
            long waitMs = Math.max(1, (bytes - tokens) / Math.max(1, refillPerMillis));
            try {
                wait(Math.min(waitMs, 1000));
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return;
            }
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerMillis);
            lastRefill = now;
        }
    }
}
