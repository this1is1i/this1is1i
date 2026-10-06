package io.refactorcontrolplane.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Builds immutable merges without reading source-repository merge drivers or running its hooks. */
class IntegrationGit {
    static final String ZERO = "0".repeat(40);
    private final Path repository;
    private final Path root;
    IntegrationGit(Path repository) {
        this.repository = repository.toAbsolutePath().normalize();
        this.root = this.repository.getParent().resolve(".refactor-integrations");
    }
    Path directory(String id) { return root.resolve(UUID.fromString(id).toString()); }
    Path bare(String id) { return directory(id).resolve("objects.git"); }
    String target(String ref) {
        if (!ref.startsWith("refs/heads/") || ref.contains("..") || ref.contains("\n"))
            throw new IllegalArgumentException("Integration target must be a branch ref");
        return checked(repository, "rev-parse", "--verify", ref + "^{commit}").strip();
    }
    record Assembly(String commit, String conflictingTask, List<String> conflictingPaths, String evidence) {}

    Set<String> changedPaths(io.refactorcontrolplane.mcp.McpProjectRuntime.IntegrationInput input) {
        String output = checked(repository, "diff", "--no-ext-diff", "--no-renames", "--name-only", "-z",
                input.execution().baseCommit(), input.execution().commitSha(), "--");
        return new TreeSet<>(Arrays.asList(output.split("\0")));
    }

    void initialize(String id, String fingerprint) {
        Path dir = directory(id), owner = dir.resolve("owner"), bare = bare(id);
        try {
            Files.createDirectories(root);
            if (!Files.exists(dir)) {
                Path staging = Files.createTempDirectory(root, ".prepare-");
                Files.writeString(staging.resolve("owner"), fingerprint, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                publishDirectory(staging, dir);
            }
            if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(owner, StandardCharsets.UTF_8).equals(fingerprint)
                    || Files.isSymbolicLink(dir)) throw new IllegalStateException("Integration directory ownership mismatch");
            Path empty = dir.resolve("empty"); Files.createDirectories(empty);
            if (!Files.exists(bare.resolve("HEAD"))) checked(dir, "init", "--bare", "--template=" + empty, bare.toString());
            Path objects = Path.of(checked(repository, "rev-parse", "--path-format=absolute", "--git-path", "objects").strip()).toRealPath();
            String alternate = objects.toString().replace('\\', '/') + "\n";
            Path alternates = bare.resolve("objects/info/alternates");
            if (Files.exists(alternates)) {
                if (!Files.readString(alternates, StandardCharsets.UTF_8).equals(alternate))
                    throw new IllegalStateException("Unexpected integration object source");
            } else Files.writeString(alternates, alternate, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) { throw new IllegalStateException("Cannot initialize owned integration directory", ex); }
    }

    /** Same-filesystem directory rename, without REPLACE_EXISTING: never adopt a racing directory. */
    void publishDirectory(Path staging, Path destination) throws IOException { Files.move(staging, destination); }

    Assembly assemble(IntegrationService.Plan plan) {
        initialize(plan.id(), plan.fingerprint());
        String current = plan.targetCommit();
        for (var input : plan.inputs()) {
            var result = run(bare(plan.id()), new byte[0], 1024 * 1024,
                    "merge-tree", "--write-tree", "--name-only", "-z", current, input.execution().commitSha());
            if (result.timedOut() || result.truncated()) throw new IllegalStateException("Git merge exceeded execution bounds");
            if (result.exitCode() == 1) {
                String[] parts = result.out().split("\0", -1);
                List<String> paths = new ArrayList<>();
                for (int i = 1; i < parts.length && !parts[i].isEmpty(); i++) paths.add(parts[i]);
                return new Assembly(null, input.taskId(), paths, result.out() + result.err());
            }
            if (!result.passed()) throw new IllegalStateException("Git merge failed: " + result.err());
            String tree = result.out().split("\0", -1)[0].strip();
            if (!tree.matches("[0-9a-f]{40}")) throw new IllegalStateException("Invalid merge tree");
            current = checked(bare(plan.id()), "commit-tree", tree, "-p", current, "-p", input.execution().commitSha(),
                    "-m", "Integration " + plan.fingerprint() + " task " + input.taskId()).strip();
        }
        return new Assembly(current, null, List.of(), "");
    }

    void publish(IntegrationService.Plan plan, String commit) {
        StringBuilder revisions = new StringBuilder(commit).append('\n').append('^').append(plan.targetCommit()).append('\n');
        plan.inputs().forEach(input -> revisions.append('^').append(input.execution().commitSha()).append('\n'));
        var pack = run(bare(plan.id()), revisions.toString().getBytes(StandardCharsets.UTF_8), 32 * 1024 * 1024,
                "pack-objects", "--stdout", "--revs");
        if (!pack.passed()) throw new IllegalStateException("Cannot export bounded integration objects");
        var imported = run(repository, pack.stdout(), 65536, "index-pack", "--stdin");
        if (!imported.passed()) throw new IllegalStateException("Cannot import integration objects");
        String branch = branch(plan.id());
        var existing = run(repository, new byte[0], 65536, "show-ref", "--verify", "--quiet", branch);
        if (existing.passed() && target(branch).equals(commit)) return;
        if (existing.exitCode() != 1) throw new IllegalStateException("Integration ref already exists or cannot be inspected");
        var published = run(repository, new byte[0], 65536, "update-ref", branch, commit, ZERO);
        if (!published.passed() && !target(branch).equals(commit))
            throw new IllegalStateException("Integration ref changed concurrently; external winner preserved");
    }
    static String branch(String id) { return "refs/heads/codex/integration-" + UUID.fromString(id); }
    Path validationTree(IntegrationService.Plan plan, String commit, String attempt) {
        initialize(plan.id(), plan.fingerprint());
        Path tree = directory(plan.id()).resolve("validation-" + UUID.fromString(attempt));
        if (Files.exists(tree)) throw new IllegalStateException("Validation directory already exists; use a new attempt");
        checked(bare(plan.id()), "worktree", "add", "--detach", tree.toString(), commit);
        return tree;
    }
    boolean clean(Path tree, String commit) {
        return checked(tree, "rev-parse", "HEAD").strip().equals(commit)
                && checked(tree, "status", "--porcelain", "--untracked-files=no").isEmpty();
    }
    String checked(Path cwd, String... args) {
        var result = run(cwd, new byte[0], 1024 * 1024, args);
        if (!result.passed()) throw new IllegalStateException("Git operation failed: " + result.err());
        return result.out();
    }
    private ControlledProcess.Result run(Path cwd, byte[] input, int limit, String... args) {
        var command = new ArrayList<>(List.of("git", "--no-replace-objects", "--no-lazy-fetch", "-c", "core.hooksPath=" + root.resolve("disabled-hooks"),
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-C", cwd.toString()));
        command.addAll(List.of(args));
        return ControlledProcess.run(cwd, command, Map.of(
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null",
                "GIT_TERMINAL_PROMPT", "0", "GIT_AUTHOR_NAME", "Integration Builder", "GIT_AUTHOR_EMAIL", "integration@localhost",
                "GIT_COMMITTER_NAME", "Integration Builder", "GIT_COMMITTER_EMAIL", "integration@localhost",
                "GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z"), input, 30, limit);
    }
}
