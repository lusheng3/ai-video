package com.dovideo.controller;

import com.dovideo.common.Result;
import com.dovideo.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final AppProperties appProperties;

    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "mockEnabled", appProperties.isMockEnabled(),
                "analysisTopic", appProperties.getMq().getAnalysisTopic()
        ));
    }
}
