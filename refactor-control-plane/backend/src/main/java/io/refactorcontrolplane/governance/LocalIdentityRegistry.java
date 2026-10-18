package io.refactorcontrolplane.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Project-local development identities; no accounts or credentials are enabled by default. */
@Service
public final class LocalIdentityRegistry {
    private final List<Account> accounts;

    public LocalIdentityRegistry(@Value("${control-plane.identity-file:}") String file, ObjectMapper mapper) {
        if (file.isBlank()) { accounts = List.of(); return; }
        try {
            String json;
            if (file.startsWith("classpath:")) {
                try (var input = new ClassPathResource(file.substring(10)).getInputStream()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            }
            accounts = List.of(mapper.readValue(json, Account[].class));
            Set<String> subjects = new HashSet<>();
            Set<String> hashes = new HashSet<>();
            for (Account account : accounts) {
                if (account.subjectId() == null || account.subjectId().isBlank()
                        || account.projectId() == null || account.projectId().isBlank()
                        || account.roles() == null || account.roles().isEmpty()
                        || account.tokenSha256() == null || !account.tokenSha256().matches("[0-9a-f]{64}")
                        || !subjects.add(account.projectId() + ":" + account.subjectId())
                        || !hashes.add(account.tokenSha256())) {
                    throw new IllegalArgumentException("Invalid or duplicate local identity");
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load project-local identities", exception);
        }
    }

    public Principal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new SecurityException("Authentication required");
        String digest = digest(authorization.substring(7));
        return accounts.stream().filter(account -> MessageDigest.isEqual(
                        account.tokenSha256().getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII)))
                .map(account -> new Principal(account.subjectId(), account.projectId(), account.roles()))
                .findFirst().orElseThrow(() -> new SecurityException("Invalid local credential"));
    }

    public void requireAssignee(String projectId, String subjectId) {
        if (accounts.stream().noneMatch(account -> account.projectId().equals(projectId)
                && account.subjectId().equals(subjectId))) throw new SecurityException("Assignee is not a project member");
    }

    public List<Member> projectMembers(String projectId) {
        return accounts.stream().filter(account -> account.projectId().equals(projectId))
                .map(account -> new Member(account.subjectId(), account.roles().stream().map(Enum::name).sorted().toList()))
                .sorted(Comparator.comparing(Member::subjectId)).toList();
    }

    public record Member(String subjectId, List<String> roles) {}

    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Account(String subjectId, String projectId, Set<ProjectRole> roles, String tokenSha256) {}
    public record Principal(String subjectId, String projectId, Set<ProjectRole> roles) {
        public Principal { roles = Set.copyOf(roles); }
    }
}
