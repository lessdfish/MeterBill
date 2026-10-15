package com.meterbill.operations.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "meterbill.redisson", name = "enabled", havingValue = "true")
public class TaskCoordinationService {

    private final RedissonClient redisson;

    public boolean runIfAcquired(String taskKey, Runnable task) {
        RLock lock = redisson.getLock("meterbill:task:" + taskKey);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
