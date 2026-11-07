package com.aws.lab;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class DbConfig {

    private static final Map<String, String> ENV_FILE_MAP = loadDotEnv();

    private static Map<String, String> loadDotEnv() {
        Map<String, String> map = new HashMap<>();
        File envFile = new File(".env");
        if (!envFile.exists()) {
            envFile = new File("E:\\aws-bedrock-lab\\.env");
        }

        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eqIdx = line.indexOf('=');
                    if (eqIdx != -1) {
                        String key = line.substring(0, eqIdx).trim();
                        String val = line.substring(eqIdx + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        map.put(key, val);
                    }
                }
            } catch (Exception e) {
                System.err.println("[DbConfig] Warning: Failed to read .env file: " + e.getMessage());
            }
        }
        return map;
    }

    private static String getVal(String key) {
        String sysVal = System.getenv(key);
        if (sysVal != null && !sysVal.isBlank()) {
            return sysVal;
        }
        return ENV_FILE_MAP.get(key);
    }

    public static String getUrl() {
        String url = getVal("DOCLAB_DB_URL");
        if (url == null || url.isBlank()) {
            url = getVal("AEGIS_DB_URL");
        }
        return (url != null && !url.isBlank()) ? url : "jdbc:postgresql://127.0.0.1:5432/doclab_rag";
    }

    public static String getUser() {
        String user = getVal("DOCLAB_DB_USER");
        if (user == null || user.isBlank()) {
            user = getVal("AEGIS_DB_USER");
        }
        if (user == null || user.isBlank()) {
            user = getVal("DB_USER");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("Missing environment variable: DOCLAB_DB_USER (or AEGIS_DB_USER / DB_USER) in environment or .env");
        }
        return user;
    }

    public static String getPassword() {
        String pass = getVal("DOCLAB_DB_PASS");
        if (pass == null || pass.isBlank()) {
            pass = getVal("DOCLAB_DB_PASSWORD");
        }
        if (pass == null || pass.isBlank()) {
            pass = getVal("AEGIS_DB_PASS");
        }
        if (pass == null || pass.isBlank()) {
            pass = getVal("AEGIS_DB_PASSWORD");
        }
        if (pass == null || pass.isBlank()) {
            pass = getVal("DB_PASSWORD");
        }
        if (pass == null || pass.isBlank()) {
            throw new IllegalStateException("Missing environment variable: DOCLAB_DB_PASS (or AEGIS_DB_PASS / DB_PASSWORD) in environment or .env");
        }
        return pass;
    }

    public static String getRedisHost() {
        String host = getVal("DOCLAB_REDIS_HOST");
        if (host == null || host.isBlank()) {
            host = getVal("AEGIS_REDIS_HOST");
        }
        return (host != null && !host.isBlank()) ? host : "127.0.0.1";
    }

    public static int getRedisPort() {
        String port = getVal("DOCLAB_REDIS_PORT");
        if (port == null || port.isBlank()) {
            port = getVal("AEGIS_REDIS_PORT");
        }
        return (port != null && !port.isBlank()) ? Integer.parseInt(port) : 6379;
    }
}
