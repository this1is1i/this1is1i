package io.refactorcontrolplane.ownership;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PathLeaseRegistry {

    private final Map<UUID, PathLease> leases = new LinkedHashMap<>();
    private final Clock clock;
    private final RepositoryPathPolicy pathPolicy;
    private Instant timeWatermark = Instant.MIN;

    public PathLeaseRegistry(Clock clock, RepositoryPathPolicy pathPolicy) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy must not be null");
    }

    public synchronized LeaseReservation reserve(LeaseRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant now = observeTime();
        String resourcePath = normalize(request.resourcePath());

        List<LeaseConflict> conflicts = leases.values().stream()
                .filter(existing -> existing.isActiveAt(now))
                .filter(existing -> !existing.taskExecutionId().equals(request.taskExecutionId()))
                .filter(existing -> pathPolicy.overlaps(existing.resourcePath(), resourcePath))
                .filter(existing -> !existing.mode().isCompatibleWith(request.mode()))
                .map(existing -> new LeaseConflict(
                        existing.leaseId(),
                        existing.taskExecutionId(),
                        existing.resourcePath(),
                        existing.mode(),
                        "Requested " + request.mode() + " conflicts with active " + existing.mode()
                                + " lease on overlapping path " + existing.resourcePath()))
                .toList();

        if (!conflicts.isEmpty()) {
            return LeaseReservation.rejected(conflicts);
        }

        PathLease lease = new PathLease(
                UUID.randomUUID(),
                request.ownerId(),
                request.taskExecutionId(),
                resourcePath,
                request.mode(),
                1,
                now,
                now,
                now.plus(request.ttl()));
        leases.put(lease.leaseId(), lease);
        return LeaseReservation.accepted(lease);
    }

    public synchronized PathLease heartbeat(
            UUID leaseId,
            String taskExecutionId,
            long expectedVersion,
            Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = observeTime();
        PathLease current = ownedLease(leaseId, taskExecutionId, expectedVersion);

        PathLease renewed = new PathLease(
                current.leaseId(),
                current.ownerId(),
                current.taskExecutionId(),
                current.resourcePath(),
                current.mode(),
                current.version() + 1,
                current.startedAt(),
                now,
                now.plus(ttl));
        leases.put(leaseId, renewed);
        return renewed;
    }

    public synchronized void release(UUID leaseId, String taskExecutionId, long expectedVersion) {
        ownedLease(leaseId, taskExecutionId, expectedVersion);
        leases.remove(leaseId);
    }

    public synchronized List<PathLease> activeLeases() {
        Instant now = observeTime();
        return leases.values().stream()
                .filter(lease -> lease.isActiveAt(now))
                .toList();
    }

    public synchronized void restore(PathLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        Instant now = observeTime();
        if (!lease.isActiveAt(now)) return;
        PathLease existing = leases.get(lease.leaseId());
        if (existing != null && existing.version() > lease.version()) return;
        leases.put(lease.leaseId(), lease);
    }

    public synchronized void removeForRecovery(UUID leaseId) {
        leases.remove(Objects.requireNonNull(leaseId, "leaseId must not be null"));
    }

    private Instant observeTime() {
        Instant observed = clock.instant();
        if (observed.isAfter(timeWatermark)) {
            timeWatermark = observed;
        }
        leases.values().removeIf(lease -> !lease.isActiveAt(timeWatermark));
        return timeWatermark;
    }

    private PathLease ownedLease(UUID leaseId, String taskExecutionId, long expectedVersion) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        if (taskExecutionId == null || taskExecutionId.isBlank()) {
            throw new IllegalArgumentException("taskExecutionId must not be blank");
        }
        PathLease current = leases.get(leaseId);
        if (current == null) {
            throw new LeaseNotFoundException(leaseId);
        }
        if (!current.taskExecutionId().equals(taskExecutionId)) {
            throw new LeaseOwnerMismatchException(taskExecutionId, current.taskExecutionId());
        }
        if (current.version() != expectedVersion) {
            throw new LeaseVersionConflictException(expectedVersion, current.version());
        }
        return current;
    }

    static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        String slashPath = rawPath.trim().replace('\\', '/');
        if (slashPath.startsWith("/") || slashPath.matches("^[A-Za-z]:($|/.*)")) {
            throw new IllegalArgumentException("resourcePath must be repository-relative: " + rawPath);
        }

        List<String> segments = new ArrayList<>();
        for (String segment : slashPath.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("resourcePath must not contain traversal: " + rawPath);
            }
            if (segment.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("resourcePath must not contain NUL");
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "." : String.join("/", segments);
    }

}
