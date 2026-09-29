package com.bbb.exercise.agentdemo.mediaservice.worker;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkerLeaseService {
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    public boolean tryAcquire(String jobId, String workerId, Duration duration) {
        Instant now = Instant.now();
        Instant until = now.plus(duration);
        return leases.compute(jobId, (key, current) -> {
            if (current == null || current.until().isBefore(now) || current.owner().equals(workerId)) {
                return new Lease(workerId, until);
            }
            return current;
        }).owner().equals(workerId);
    }

    public void release(String jobId, String workerId) {
        leases.computeIfPresent(jobId, (key, lease) -> lease.owner().equals(workerId) ? null : lease);
    }

    private record Lease(String owner, Instant until) {
    }
}
