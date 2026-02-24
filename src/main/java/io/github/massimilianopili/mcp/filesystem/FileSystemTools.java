package io.github.massimilianopili.mcp.filesystem;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Service
public class FileSystemTools {

    private final Path baseDir;

    public FileSystemTools(@Value("${mcp.fs.basedir:C:/NoCloud}") String basedir) {
        this.baseDir = Path.of(basedir);
    }

    @Tool(name = "fs_list", description = "Elenca file e cartelle in una directory. Il percorso e' relativo alla directory base configurata (default: C:/NoCloud).")
    public List<FileInfo> listFiles(
            @ToolParam(description = "Percorso relativo della directory, es: Progetti/Vari") String relativePath) {
        Path dir = resolveAndValidate(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Il percorso non e' una directory: " + relativePath);
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> new FileInfo(
                    p.getFileName().toString(),
                    Files.isDirectory(p) ? "directory" : "file",
                    safeSize(p),
                    safeLastModified(p)
            )).toList();
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura directory: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fs_read", description = "Legge il contenuto di un file di testo. Il percorso e' relativo alla directory base. Massimo 100KB.")
    public String readFile(
            @ToolParam(description = "Percorso relativo del file, es: Progetti/Vari/mcp/pom.xml") String relativePath) {
        Path file = resolveAndValidate(relativePath);
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Il percorso e' una directory, non un file");
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("File non trovato: " + relativePath);
        }
        try {
            long size = Files.size(file);
            if (size > 100_000) {
                String content = Files.readString(file);
                return content.substring(0, Math.min(content.length(), 100_000))
                        + "\n... [TRONCATO - file di " + size + " bytes]";
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException("Errore lettura file: " + e.getMessage(), e);
        }
    }

    @Tool(name = "fs_search", description = "Cerca file per nome (glob pattern) in una directory, ricorsivamente fino a 10 livelli di profondita'. Massimo 100 risultati.")
    public List<String> searchFiles(
            @ToolParam(description = "Percorso relativo della directory di partenza") String relativePath,
            @ToolParam(description = "Pattern glob, es: *.java, *.xml, pom*") String globPattern) {
        Path dir = resolveAndValidate(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Il percorso non e' una directory: " + relativePath);
        }
        try (var stream = Files.walk(dir, 10)) {
            PathMatcher matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + globPattern);
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> matcher.matches(p.getFileName()))
                    .map(p -> baseDir.relativize(p).toString().replace('\\', '/'))
                    .limit(100)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Errore ricerca: " + e.getMessage(), e);
        }
    }

    private Path resolveAndValidate(String relativePath) {
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("Accesso negato: percorso fuori dalla directory base");
        }
        return resolved;
    }

    private long safeSize(Path p) {
        try {
            return Files.isDirectory(p) ? -1 : Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private String safeLastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toString();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
