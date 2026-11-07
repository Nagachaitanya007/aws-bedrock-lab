package com.aws.lab.dto;

public record ChatRequest(
        String query,
        String sessionId,
        Integer topK,
        Boolean enableRerank
) {
    public int getEffectiveTopK() {
        return (topK != null && topK > 0) ? topK : 3;
    }

    public String getEffectiveSessionId() {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : "default-session";
    }

    public boolean isRerankRequested(boolean defaultSetting) {
        return enableRerank != null ? enableRerank : defaultSetting;
    }
}
