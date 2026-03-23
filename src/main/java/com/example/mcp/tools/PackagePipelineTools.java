package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package pipeline tools for managing the publish lifecycle on Gitea, Maven Central, and GitHub.
 *
 * <p>Design is generic (type parameter for maven/npm/go/docker), but initial implementation
 * covers Maven only. Gitea Package Registry API is used for check/publish operations.</p>
 */
@Service
@ConditionalOnProperty(name = "mcp.package-pipeline.enabled", havingValue = "true", matchIfMissing = false)
public class PackagePipelineTools {

    private static final Logger log = LoggerFactory.getLogger(PackagePipelineTools.class);
    private static final String DEFAULT_GROUP = "io.github.massimilianopili";
    private static final String GITEA_BASE = "http://gitea:3000";
    private static final String GITEA_API = GITEA_BASE + "/api/v1";
    private static final String GITEA_PKG = GITEA_BASE + "/api/packages/sol_root/maven";
    private static final String CENTRAL_BASE = "https://repo.maven.apache.org/maven2";
    private static final String M2_HOME = System.getProperty("user.home") + "/.m2/repository";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String giteaToken;

    public PackagePipelineTools() {
        this.giteaToken = System.getenv("MCP_GITEA_TOKEN");
        log.info("PackagePipelineTools initialized (Gitea token: {})", giteaToken != null ? "present" : "MISSING");
    }

    // ── Tool 1: gitea_check_registry ─────────────────────────────────────

    @ReactiveTool(name = "gitea_check_registry",
            description = "Check availability of Maven packages across local M2 cache, Gitea registry, and Maven Central. " +
                    "Provide pomPath to scan a pom.xml, or artifactId+version for a single check, " +
                    "or neither to scan all local packages of the groupId.")
    public Mono<String> giteaCheckRegistry(
            @ToolParam(description = "Path to pom.xml to scan for custom dependencies", required = false) String pomPath,
            @ToolParam(description = "Single artifactId to check", required = false) String artifactId,
            @ToolParam(description = "Version (required if artifactId is set)", required = false) String version,
            @ToolParam(description = "Group ID (default: io.github.massimilianopili)", required = false) String groupId) {

        String group = groupId != null && !groupId.isBlank() ? groupId : DEFAULT_GROUP;

        return Mono.fromCallable(() -> {
            List<String[]> artifacts; // [artifactId, version]

            if (artifactId != null && !artifactId.isBlank()) {
                if (version == null || version.isBlank()) {
                    return "ERROR: version is required when artifactId is specified";
                }
                artifacts = List.<String[]>of(new String[]{artifactId, version});
            } else if (pomPath != null && !pomPath.isBlank()) {
                artifacts = parsePomDependencies(Path.of(pomPath), group);
            } else {
                artifacts = scanLocalM2(group);
            }

            if (artifacts.isEmpty()) {
                return "No artifacts found for group " + group;
            }

            StringBuilder sb = new StringBuilder();
            String groupPath = group.replace('.', '/');
            int missing = 0;

            sb.append(String.format("=== Package Registry Check (%d artifacts, group: %s) ===\n", artifacts.size(), group));
            sb.append(String.format("%-35s %-8s  local  gitea  central\n", "ARTIFACT", "VERSION"));
            sb.append("-".repeat(78)).append("\n");

            for (String[] art : artifacts) {
                String id = art[0], ver = art[1];
                String jarName = id + "-" + ver + ".jar";

                boolean local = Files.exists(Path.of(M2_HOME, groupPath, id, ver, jarName));
                boolean gitea = httpHead(GITEA_PKG + "/" + groupPath + "/" + id + "/" + ver + "/" + jarName);
                boolean central = httpHead(CENTRAL_BASE + "/" + groupPath + "/" + id + "/" + ver + "/" + jarName);

                String warn = (!gitea || !central) ? "  ← MISSING" : "";
                if (!gitea || !central) missing++;

                sb.append(String.format("%-35s %-8s  %-5s  %-5s  %-5s%s\n",
                        id, ver, b(local), b(gitea), b(central), warn));
            }

            sb.append("-".repeat(78)).append("\n");
            if (missing > 0) {
                sb.append(String.format("WARNING: %d artifact(s) missing from at least one registry", missing));
            } else {
                sb.append("All artifacts available on all registries");
            }
            return sb.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool 2: gitea_publish_package ────────────────────────────────────

    @ReactiveTool(name = "gitea_publish_package",
            description = "Publish a Maven artifact from local M2 cache to Gitea Package Registry. " +
                    "Uploads POM + JAR. Use overwrite=true to replace an existing version (DELETE + PUT).")
    public Mono<String> giteaPublishPackage(
            @ToolParam(description = "Artifact ID (e.g. mcp-redis-tools)") String artifactId,
            @ToolParam(description = "Version (e.g. 0.1.1)") String version,
            @ToolParam(description = "Group ID (default: io.github.massimilianopili)", required = false) String groupId,
            @ToolParam(description = "Overwrite existing version (default: false)", required = false) Boolean overwrite) {

        String group = groupId != null && !groupId.isBlank() ? groupId : DEFAULT_GROUP;
        boolean ow = overwrite != null && overwrite;

        return Mono.fromCallable(() -> {
            if (giteaToken == null || giteaToken.isBlank()) {
                return "ERROR: MCP_GITEA_TOKEN not set";
            }

            String groupPath = group.replace('.', '/');
            Path localDir = Path.of(M2_HOME, groupPath, artifactId, version);
            Path pomFile = localDir.resolve(artifactId + "-" + version + ".pom");
            Path jarFile = localDir.resolve(artifactId + "-" + version + ".jar");

            if (!Files.exists(pomFile)) {
                return "ERROR: POM not found at " + pomFile;
            }
            if (!Files.exists(jarFile)) {
                return "ERROR: JAR not found at " + jarFile;
            }

            StringBuilder result = new StringBuilder();

            // Delete existing if overwrite
            if (ow) {
                String pkgName = URLEncoder.encode(group + ":" + artifactId, StandardCharsets.UTF_8);
                int delStatus = httpDelete(GITEA_API + "/packages/sol_root/maven/" + pkgName + "/" + version);
                result.append(String.format("DELETE existing: %d\n", delStatus));
            }

            // Upload POM
            String basePath = GITEA_PKG + "/" + groupPath + "/" + artifactId + "/" + version + "/";
            int pomStatus = httpPut(basePath + artifactId + "-" + version + ".pom", pomFile);
            result.append(String.format("POM upload: %d\n", pomStatus));

            // Upload JAR
            int jarStatus = httpPut(basePath + artifactId + "-" + version + ".jar", jarFile);
            result.append(String.format("JAR upload: %d\n", jarStatus));

            boolean success = (pomStatus == 201 || pomStatus == 409) && (jarStatus == 201 || jarStatus == 409);
            result.append(success ? "OK — package published" : "WARN — check status codes above");

            return result.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool 3: package_publish_public ───────────────────────────────────

    @ReactiveTool(name = "package_publish_public",
            description = "Trigger publication to public registry by creating a v* tag on Gitea, " +
                    "which activates the release.yml CI workflow. For Maven this publishes to Maven Central.")
    public Mono<String> packagePublishPublic(
            @ToolParam(description = "Artifact ID / repository name (e.g. mcp-redis-tools)") String artifactId,
            @ToolParam(description = "Version to publish (e.g. 0.1.2)") String version,
            @ToolParam(description = "Dry run — show what would happen without executing (default: false)", required = false) Boolean dryRun) {

        boolean dry = dryRun != null && dryRun;
        String tagName = "v" + version;

        return Mono.fromCallable(() -> {
            if (giteaToken == null || giteaToken.isBlank()) {
                return "ERROR: MCP_GITEA_TOKEN not set";
            }

            String repoApi = GITEA_API + "/repos/sol_root/" + artifactId;

            // Check repo exists
            int repoStatus = httpGetStatus(repoApi);
            if (repoStatus != 200) {
                return "ERROR: Repository sol_root/" + artifactId + " not found on Gitea (HTTP " + repoStatus + ")";
            }

            // Check tag doesn't already exist
            JsonNode tags = httpGetJson(repoApi + "/tags");
            if (tags != null && tags.isArray()) {
                for (JsonNode tag : tags) {
                    if (tagName.equals(tag.path("name").asText())) {
                        return "ERROR: Tag " + tagName + " already exists on " + artifactId + ". Delete it first or bump version.";
                    }
                }
            }

            if (dry) {
                return String.format("DRY RUN — would create tag '%s' on sol_root/%s\n" +
                        "This triggers release.yml → Maven Central publish (GPG sign + OSSRH deploy)", tagName, artifactId);
            }

            // Create tag
            String body = String.format("{\"tag_name\":\"%s\",\"target\":\"main\"}", tagName);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(repoApi + "/tags"))
                    .header("Authorization", "token " + giteaToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 201) {
                return String.format("Tag '%s' created on sol_root/%s (HTTP 201)\n" +
                        "CI workflow release.yml should trigger → Maven Central publish.\n" +
                        "Monitor: gitea_get_workflow_run(owner=\"sol_root\", repo=\"%s\")", tagName, artifactId, artifactId);
            } else {
                return String.format("WARN: Tag creation returned HTTP %d\nBody: %s",
                        resp.statusCode(), resp.body().substring(0, Math.min(500, resp.body().length())));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool 4: github_mirror_push ───────────────────────────────────────

    @ReactiveTool(name = "github_mirror_push",
            description = "Force-push a Gitea repository to its GitHub mirror. " +
                    "Triggers the mirror.yml workflow via Gitea Actions API dispatch.")
    public Mono<String> githubMirrorPush(
            @ToolParam(description = "Repository name (e.g. mcp-redis-tools)") String artifactId) {

        return Mono.fromCallable(() -> {
            if (giteaToken == null || giteaToken.isBlank()) {
                return "ERROR: MCP_GITEA_TOKEN not set";
            }

            String dispatchUrl = GITEA_API + "/repos/sol_root/" + artifactId +
                    "/actions/workflows/mirror.yml/dispatches";

            String body = "{\"ref\":\"main\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(dispatchUrl))
                    .header("Authorization", "token " + giteaToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 204 || resp.statusCode() == 201) {
                return String.format("Mirror workflow triggered for sol_root/%s → github.com/MassimilianoPili/%s",
                        artifactId, artifactId);
            } else {
                // Fallback: try direct git push from local repo
                Path repoDir = Path.of("/data/massimiliano/Vari/" + artifactId);
                if (Files.isDirectory(repoDir.resolve(".git"))) {
                    ProcessBuilder pb = new ProcessBuilder("git", "push", "--force",
                            "git@github.com:MassimilianoPili/" + artifactId + ".git", "HEAD:main", "--tags");
                    pb.directory(repoDir.toFile());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int exit = p.waitFor();
                    return String.format("API dispatch returned %d, used git push fallback (exit: %d)\n%s",
                            resp.statusCode(), exit, output);
                }
                return String.format("WARN: Dispatch returned HTTP %d and no local repo at %s\n%s",
                        resp.statusCode(), repoDir, resp.body().substring(0, Math.min(500, resp.body().length())));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool 5: package_pipeline_status ──────────────────────────────────

    @ReactiveTool(name = "package_pipeline_status",
            description = "Dashboard showing publish status of all packages or a specific one. " +
                    "Shows: local version, Gitea registry, Maven Central, last CI status, GitHub mirror.")
    public Mono<String> packagePipelineStatus(
            @ToolParam(description = "Artifact ID to check (omit for all)", required = false) String artifactId,
            @ToolParam(description = "Group ID (default: io.github.massimilianopili)", required = false) String groupId) {

        String group = groupId != null && !groupId.isBlank() ? groupId : DEFAULT_GROUP;

        return Mono.fromCallable(() -> {
            if (giteaToken == null || giteaToken.isBlank()) {
                return "ERROR: MCP_GITEA_TOKEN not set";
            }

            List<String[]> artifacts;
            if (artifactId != null && !artifactId.isBlank()) {
                // Find version from local M2
                String ver = findLatestLocalVersion(group, artifactId);
                if (ver == null) return "ERROR: " + artifactId + " not found in local M2";
                artifacts = List.<String[]>of(new String[]{artifactId, ver});
            } else {
                artifacts = scanLocalM2(group);
            }

            if (artifacts.isEmpty()) {
                return "No artifacts found for group " + group;
            }

            String groupPath = group.replace('.', '/');
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Package Pipeline Status (%d packages) ===\n", artifacts.size()));
            sb.append(String.format("%-35s %-8s  local  gitea  central  ci        github\n", "PACKAGE", "VER"));
            sb.append("-".repeat(95)).append("\n");

            for (String[] art : artifacts) {
                String id = art[0], ver = art[1];
                String jarName = id + "-" + ver + ".jar";

                boolean local = Files.exists(Path.of(M2_HOME, groupPath, id, ver, jarName));
                boolean gitea = httpHead(GITEA_PKG + "/" + groupPath + "/" + id + "/" + ver + "/" + jarName);
                boolean central = httpHead(CENTRAL_BASE + "/" + groupPath + "/" + id + "/" + ver + "/" + jarName);

                // CI status from latest workflow run
                String ciStatus = getLatestCiStatus(id);

                // GitHub mirror check
                boolean github = httpHead("https://raw.githubusercontent.com/MassimilianoPili/" + id + "/main/pom.xml");

                sb.append(String.format("%-35s %-8s  %-5s  %-5s  %-7s  %-8s  %s\n",
                        id, ver, b(local), b(gitea), b(central), ciStatus, b(github)));
            }

            sb.append("-".repeat(95));
            return sb.toString();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Helper methods ───────────────────────────────────────────────────

    private String b(boolean v) { return v ? "Y" : "N"; }

    private boolean httpHead(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (url.contains("gitea") && giteaToken != null) {
                builder.header("Authorization", "token " + giteaToken);
            }
            HttpResponse<Void> resp = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private int httpGetStatus(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "token " + giteaToken)
                    .GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private JsonNode httpGetJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "token " + giteaToken)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return mapper.readTree(resp.body());
            }
        } catch (Exception e) {
            log.debug("httpGetJson failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    private int httpPut(String url, Path file) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "token " + giteaToken)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            log.warn("httpPut failed for {}: {}", url, e.getMessage());
            return -1;
        }
    }

    private int httpDelete(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "token " + giteaToken)
                    .DELETE()
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            log.warn("httpDelete failed for {}: {}", url, e.getMessage());
            return -1;
        }
    }

    private List<String[]> parsePomDependencies(Path pomPath, String group) {
        List<String[]> result = new ArrayList<>();
        if (!Files.exists(pomPath)) return result;

        try {
            String content = Files.readString(pomPath);
            // Simple regex to find dependencies with matching groupId
            Pattern depPattern = Pattern.compile(
                    "<dependency>\\s*<groupId>" + Pattern.quote(group) + "</groupId>\\s*" +
                            "<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>",
                    Pattern.DOTALL);
            Matcher m = depPattern.matcher(content);
            while (m.find()) {
                result.add(new String[]{m.group(1), m.group(2)});
            }
        } catch (IOException e) {
            log.warn("Failed to parse pom at {}: {}", pomPath, e.getMessage());
        }
        return result;
    }

    private List<String[]> scanLocalM2(String group) {
        List<String[]> result = new ArrayList<>();
        Path groupDir = Path.of(M2_HOME, group.replace('.', '/'));
        if (!Files.isDirectory(groupDir)) return result;

        try (DirectoryStream<Path> artifacts = Files.newDirectoryStream(groupDir)) {
            for (Path artDir : artifacts) {
                if (!Files.isDirectory(artDir)) continue;
                String artId = artDir.getFileName().toString();
                String ver = findLatestLocalVersion(group, artId);
                if (ver != null) {
                    result.add(new String[]{artId, ver});
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan M2 at {}: {}", groupDir, e.getMessage());
        }
        // Sort by artifactId
        result.sort((a, b) -> a[0].compareTo(b[0]));
        return result;
    }

    private String findLatestLocalVersion(String group, String artifactId) {
        Path artDir = Path.of(M2_HOME, group.replace('.', '/'), artifactId);
        if (!Files.isDirectory(artDir)) return null;

        // Find directories that contain a .jar file (actual versions, not metadata)
        TreeMap<String, Path> versions = new TreeMap<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(artDir)) {
            for (Path verDir : dirs) {
                if (!Files.isDirectory(verDir)) continue;
                String ver = verDir.getFileName().toString();
                if (Files.exists(verDir.resolve(artifactId + "-" + ver + ".jar"))) {
                    versions.put(ver, verDir);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return versions.isEmpty() ? null : versions.lastKey();
    }

    private String getLatestCiStatus(String repoName) {
        try {
            JsonNode runs = httpGetJson(GITEA_API + "/repos/sol_root/" + repoName + "/actions/workflows?limit=1");
            if (runs == null) {
                // Try workflow_runs directly
                runs = httpGetJson(GITEA_API + "/repos/sol_root/" + repoName + "/actions/runners");
            }
            // Fallback: check via workflow runs list
            JsonNode workflowRuns = httpGetJson(GITEA_API + "/repos/sol_root/" + repoName +
                    "/actions/workflows/release.yml/runs?limit=1");
            if (workflowRuns != null && workflowRuns.has("workflow_runs")) {
                JsonNode latest = workflowRuns.path("workflow_runs").path(0);
                if (!latest.isMissingNode()) {
                    return latest.path("conclusion").asText("unknown");
                }
            }
            return "n/a";
        } catch (Exception e) {
            return "err";
        }
    }
}
