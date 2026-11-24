"""Async token-bucket rate limiter supporting global and per-download speed caps."""

from __future__ import annotations

import asyncio
import time


class TokenBucketRateLimiter:
    """Thread-safe / asyncio-safe token bucket limiter (bytes/sec)."""

    def __init__(self, bytes_per_sec: float = 0.0) -> None:
        self._rate = max(0.0, float(bytes_per_sec))
        self._tokens = self._rate
        self._last_refill = time.monotonic()
        self._lock = asyncio.Lock()

    @property
    def rate_bytes_per_sec(self) -> float:
        return self._rate

    def set_rate_kbps(self, kbps: float) -> None:
        """Update speed cap dynamically in KB/s (0 = unlimited)."""
        new_rate = max(0.0, float(kbps) * 1024.0)
        self._rate = new_rate
        if new_rate > 0:
            self._tokens = min(self._tokens, new_rate)
        self._last_refill = time.monotonic()

    async def acquire(self, num_bytes: int) -> None:
        """Wait until `num_bytes` tokens are available (handles chunks larger than burst cleanly)."""
        if self._rate <= 0 or num_bytes <= 0:
            return
        remaining = float(num_bytes)
        while remaining > 0:
            rate = self._rate
            if rate <= 0:
                return
            async with self._lock:
                now = time.monotonic()
                elapsed = now - self._last_refill
                self._last_refill = now
                burst = max(rate * 0.5, 65536.0)
                self._tokens = min(burst, self._tokens + elapsed * rate)
                if self._tokens > 0:
                    take = min(remaining, self._tokens)
                    self._tokens -= take
                    remaining -= take
                if remaining <= 0:
                    return
                wait_time = min(remaining / rate, 0.2)
            await asyncio.sleep(max(0.005, wait_time))


class CompositeRateLimiter:
    """Enforces both global and per-download rate limits simultaneously."""

    def __init__(
        self,
        global_limiter: TokenBucketRateLimiter | None = None,
        local_limiter: TokenBucketRateLimiter | None = None,
    ) -> None:
        self.global_limiter = global_limiter
        self.local_limiter = local_limiter

    async def acquire(self, num_bytes: int) -> None:
        if self.local_limiter is not None:
            await self.local_limiter.acquire(num_bytes)
        if self.global_limiter is not None:
            await self.global_limiter.acquire(num_bytes)
