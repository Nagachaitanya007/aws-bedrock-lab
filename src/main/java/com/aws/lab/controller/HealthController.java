package com.aws.lab.controller;

import com.aws.lab.DbConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final JedisPool jedisPool;

    public HealthController(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", "DocLab");
        status.put("version", "1.0.0");

        // Check Redis
        boolean redisOk = false;
        try (Jedis jedis = jedisPool.getResource()) {
            redisOk = "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception ignored) {}
        status.put("redis", redisOk ? "UP" : "DOWN");

        // Check PostgreSQL
        boolean dbOk = false;
        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword())) {
            dbOk = conn.isValid(2);
        } catch (Exception ignored) {}
        status.put("database", dbOk ? "UP" : "DOWN");

        boolean overallOk = redisOk && dbOk;
        status.put("status", overallOk ? "HEALTHY" : "DEGRADED");

        return ResponseEntity.ok(status);
    }
}
