package io.github.massimilianopili.mcp.filesystem;

public record FileInfo(
        String name,
        String type,
        long sizeBytes,
        String lastModified
) {}
