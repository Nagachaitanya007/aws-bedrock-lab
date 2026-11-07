package com.aws.lab.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        boolean cached,
        long latencyMs,
        List<String> sources
) {}
