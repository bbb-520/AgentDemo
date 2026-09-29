package com.bbb.exercise.agentdemo.mediaservice.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLeaseServiceTest {
    @Test
    void onlyOneWorkerOwnsLease() {
        WorkerLeaseService service = new WorkerLeaseService();
        assertThat(service.tryAcquire("job-1", "worker-a", Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("job-1", "worker-b", Duration.ofMinutes(1))).isFalse();
        service.release("job-1", "worker-a");
        assertThat(service.tryAcquire("job-1", "worker-b", Duration.ofMinutes(1))).isTrue();
    }
}
