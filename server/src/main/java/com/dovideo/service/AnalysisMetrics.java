package com.dovideo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class AnalysisMetrics {

    private final MeterRegistry meterRegistry;

    public AnalysisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void submit(String result) {
        counter("dovideo_analysis_submit_total", "result", result).increment();
    }

    public void mqSendFailure() {
        counter("dovideo_analysis_mq_send_failure_total", null, null).increment();
    }

    public void lock(String result) {
        counter("dovideo_analysis_lock_total", "result", result).increment();
    }

    public void result(String result) {
        counter("dovideo_analysis_result_total", "result", result).increment();
    }

    public void staleRecovered() {
        counter("dovideo_analysis_stale_recovered_total", null, null).increment();
    }

    public void rateLimited(String scope) {
        counter("dovideo_rate_limited_total", "scope", scope).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopProcessingTimer(Timer.Sample sample, String result) {
        sample.stop(Timer.builder("dovideo_analysis_processing_seconds")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    public void recordWait(String stage, long elapsedMs) {
        meterRegistry.timer("dovideo_analysis_lock_wait_seconds", "stage", stage)
                .record(elapsedMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return tagKey == null
                ? meterRegistry.counter(name)
                : meterRegistry.counter(name, tagKey, tagValue);
    }
}
