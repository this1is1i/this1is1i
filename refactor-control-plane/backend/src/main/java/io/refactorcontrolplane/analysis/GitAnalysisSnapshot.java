package io.refactorcontrolplane.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Read-only object snapshot. No checkout, hooks, build tools or repository code are executed. */
public record GitAnalysisSnapshot(String commit, List<Entry> entries) {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    public GitAnalysisSnapshot { entries = List.copyOf(entries); }
    public record Entry(String path, String blobId, String text, String limitation) {}

    public static GitAnalysisSnapshot capture(Path repository) {
        String commit = utf8(run(repository, new byte[0], 4096, "rev-parse", "--verify", "HEAD^{commit}")).trim();
        if (!commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalStateException("Invalid Git commit identity");
        byte[] tree = run(repository, new byte[0], MAX_TOTAL_BYTES, "ls-tree", "-r", "-l", "-z", "--full-tree", commit);
        var blobs = new TreeMap<String, Blob>();
        var skipped = new ArrayList<Entry>();
        int total = 0;
        for (String row : utf8(tree).split("\u0000")) {
            if (row.isEmpty()) continue;
            int tab = row.indexOf('\t');
            String[] metadata = row.substring(0, tab).trim().split("\\s+");
            String path = row.substring(tab + 1);
            if (excluded(path)) continue;
            if (!metadata[0].equals("100644") && !metadata[0].equals("100755")) {
                boolean blob = metadata[1].equals("blob");
                skipped.add(new Entry(path, blob ? metadata[2] : null, null,
                        blob ? "Symbolic link content was not followed" : "Submodule commit " + metadata[2] + " was not followed"));
                continue;
            }
            if (!relevant(path)) continue;
            long size = Long.parseLong(metadata[3]);
            if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES) {
                skipped.add(new Entry(path, metadata[2], null, "Analysis content size limit reached"));
                continue;
            }
            total += (int) size;
            blobs.put(path, new Blob(metadata[2], (int) size));
        }
        String queries = String.join("", blobs.values().stream().map(blob -> blob.id() + "\n").toList());
        byte[] contents = blobs.isEmpty() ? new byte[0] : run(repository, queries.getBytes(StandardCharsets.US_ASCII),
                MAX_TOTAL_BYTES + tree.length + 4096, "cat-file", "--batch");
        var entries = new ArrayList<>(skipped);
        int offset = 0;
        for (var item : blobs.entrySet()) {
            Blob blob = item.getValue();
            int end = offset;
            while (end < contents.length && contents[end] != '\n') end++;
            String header = new String(contents, offset, end - offset, StandardCharsets.US_ASCII);
            if (!header.equals(blob.id() + " blob " + blob.size()) || end + 1L + blob.size() >= contents.length) {
                throw new IllegalStateException("Git blob framing or identity mismatch");
            }
            offset = end + 1;
            byte[] bytes = Arrays.copyOfRange(contents, offset, offset + blob.size());
            offset += blob.size();
            if (contents[offset++] != '\n') throw new IllegalStateException("Invalid Git blob separator");
            try {
                String text = utf8(bytes);
                if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Binary content");
                entries.add(new Entry(item.getKey(), blob.id(), text, null));
            } catch (IllegalArgumentException exception) {
                entries.add(new Entry(item.getKey(), blob.id(), null, "Content is binary or not UTF-8"));
            }
        }
        if (offset != contents.length) throw new IllegalStateException("Unexpected trailing Git object output");
        entries.sort(Comparator.comparing(Entry::path));
        return new GitAnalysisSnapshot(commit, entries);
    }

    private static boolean excluded(String path) {
        return Arrays.stream(path.split("/")).anyMatch(Set.of("target", "node_modules", ".git", ".m2-repository")::contains);
    }
    private static boolean relevant(String path) {
        return path.endsWith("/pom.xml") || path.equals("pom.xml") || path.endsWith("/package.json") || path.equals("package.json")
                || path.matches(".*\\.(java|jsp|js|ts|vue|py|sql|gradle|kts)$");
    }
    private record Blob(String id, int size) {}
    private static String utf8(byte[] data) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
        catch (java.nio.charset.CharacterCodingException exception) { throw new IllegalArgumentException("Invalid UTF-8 Git content", exception); }
    }
    private static byte[] limited(InputStream stream, int limit) throws IOException {
        try (stream; var result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if ((long) result.size() + count > limit) throw new IOException("Git output exceeds analysis limit");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
    private static byte[] run(Path repository, byte[] input, int limit, String... args) {
        var command = new ArrayList<>(List.of("git", "--no-replace-objects", "--no-lazy-fetch", "-C", repository.toAbsolutePath().toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_NAMESPACE")) builder.environment().remove(key);
        Process process = null;
        try {
            process = builder.start(); Process child = process;
            var stdout = new FutureTask<>(() -> limited(child.getInputStream(), limit));
            var stderr = new FutureTask<>(() -> limited(child.getErrorStream(), 1024 * 1024));
            var stdin = new FutureTask<Void>(() -> { try (var stream = child.getOutputStream()) { stream.write(input); } return null; });
            Thread.ofVirtual().start(stdout); Thread.ofVirtual().start(stderr); Thread.ofVirtual().start(stdin);
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Git snapshot read timed out");
            byte[] output = stdout.get(5, TimeUnit.SECONDS); byte[] error = stderr.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException("Cannot read Git snapshot: " + new String(error, StandardCharsets.UTF_8));
            stdin.get(5, TimeUnit.SECONDS);
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Git snapshot read interrupted", exception);
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Cannot read Git snapshot", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
