package com.example.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A tiny surface that emits interesting telemetry: latency (metrics + traces),
 * log lines carrying the trace id, occasional errors, and CPU-bound work that
 * the Pyroscope agent can profile.
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Counter rolls;
    private final Timer workTimer;
    private final WorkService workService;

    public DemoController(MeterRegistry registry, WorkService workService) {
        // Custom metrics with predictable names -> app_rolls_total, app_work_duration_*
        this.rolls = Counter.builder("app.rolls")
                .description("Number of dice rolls served")
                .register(registry);
        this.workTimer = Timer.builder("app.work.duration")
                .description("Time spent doing CPU work")
                .publishPercentileHistogram()
                .register(registry);
        this.workService = workService;
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of("service", "graf-zahl-demo", "status", "ok");
    }

    @GetMapping("/rolldice")
    public Map<String, Object> rollDice(@RequestParam(defaultValue = "player") String player) {
        int result = ThreadLocalRandom.current().nextInt(1, 7);
        rolls.increment();
        log.info("{} rolled a {}", player, result);
        return Map.of("player", player, "result", result);
    }

    /**
     * CPU-bound endpoint. The heavy loop gives the Pyroscope profiler
     * something to show and also produces variable request latency.
     */
    @GetMapping("/work")
    public Map<String, Object> work() {
        long iterations = ThreadLocalRandom.current().nextLong(2_000_000, 8_000_000);
        long checksum = workTimer.record(() -> workService.burnCpu(iterations));
        log.info("Completed work: iterations={} checksum={}", iterations, checksum);
        return Map.of("iterations", iterations, "checksum", checksum);
    }

    /** Fails ~30% of the time so error metrics, error logs and error traces show up. */
    @GetMapping("/flaky")
    public Map<String, Object> flaky() {
        if (ThreadLocalRandom.current().nextInt(100) < 30) {
            log.warn("Flaky endpoint failed");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "unlucky");
        }
        return Map.of("status", "ok");
    }
}
