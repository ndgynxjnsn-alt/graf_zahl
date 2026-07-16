package com.example.demo;

import org.springframework.stereotype.Service;

@Service
public class WorkService {

    /**
     * Deliberately CPU-heavy so the Pyroscope agent captures meaningful
     * stack frames under this method.
     */
    public long burnCpu(long iterations) {
        long acc = 0;
        for (long i = 0; i < iterations; i++) {
            acc += hash(i);
        }
        return acc;
    }

    private long hash(long x) {
        long h = x * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return h & 0xFFFF;
    }
}
