package com.ooohyg.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class WorkflowFiles {
    static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowFiles() {}

    static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void json(Path target, Object value) throws IOException {
        write(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    static void write(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".workflow-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String hash(Path path) throws IOException {
        return hash(Files.readAllBytes(path));
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
