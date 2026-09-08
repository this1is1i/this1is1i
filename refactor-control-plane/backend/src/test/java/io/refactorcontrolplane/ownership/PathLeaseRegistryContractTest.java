package io.refactorcontrolplane.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PathLeaseRegistryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Test
    void rejectsIncompatibleLeaseOnAnOverlappingPathWithAnExplicitReason() {
        AdjustableClock clock = new AdjustableClock(NOW);
        PathLeaseRegistry registry = registry(clock);
        LeaseReservation first = registry.reserve(request(
                "developer-a", "execution-a", "src/main", OwnershipMode.EXCLUSIVE_WRITE));

        clock.set(NOW.plusSeconds(1));
        LeaseReservation blocked = registry.reserve(request(
                "developer-b", "execution-b", "src/main/java/Article.java",
                OwnershipMode.SHARED_WRITE));

        assertTrue(first.accepted());
        assertFalse(blocked.accepted());
        assertEquals(1, blocked.conflicts().size());
        assertEquals(first.lease().orElseThrow().leaseId(), blocked.conflicts().getFirst().leaseId());
        assertTrue(blocked.conflicts().getFirst().reason().contains("EXCLUSIVE_WRITE"));
    }

    @Test
    void permitsCompatibleModesAndNonOverlappingExclusiveWrites() {
        PathLeaseRegistry registry = registry(new AdjustableClock(NOW));

        assertTrue(registry.reserve(request(
                "developer-a", "execution-a", "generated", OwnershipMode.SHARED_WRITE)).accepted());
        assertTrue(registry.reserve(request(
                "developer-b", "execution-b", "generated/api", OwnershipMode.SHARED_WRITE)).accepted());
        assertTrue(registry.reserve(request(
                "reviewer", "execution-review", "generated", OwnershipMode.READ)).accepted());
        assertTrue(registry.reserve(request(
                "developer-c", "execution-c", "src/main", OwnershipMode.EXCLUSIVE_WRITE)).accepted());
        assertTrue(registry.reserve(request(
                "developer-d", "execution-d", "src/test", OwnershipMode.EXCLUSIVE_WRITE)).accepted());
    }

    @Test
    void expiredLeaseIsPrunedWhenAReplacementIsReserved() {
        AdjustableClock clock = new AdjustableClock(NOW);
        PathLeaseRegistry registry = registry(clock);
        PathLease expired = registry.reserve(request(
                "developer-a", "execution-a", "src/main", OwnershipMode.EXCLUSIVE_WRITE))
                .lease().orElseThrow();

        clock.set(NOW.plus(FIVE_MINUTES).plusNanos(1));
        PathLease replacement = registry.reserve(request(
                "developer-b", "execution-b", "src/main", OwnershipMode.EXCLUSIVE_WRITE))
                .lease().orElseThrow();

        assertNotEquals(expired.leaseId(), replacement.leaseId());
        assertThrows(LeaseNotFoundException.class,
                () -> registry.release(expired.leaseId(), "execution-a", expired.version()));
        clock.set(NOW.plus(FIVE_MINUTES).plusSeconds(1));
        assertEquals(1, registry.activeLeases().size());
        assertEquals(replacement.leaseId(),
                registry.activeLeases().getFirst().leaseId());
    }

    @Test
    void anExpiredLeaseCannotBeRevivedWithAClockRollbackAfterReplacement() {
        AdjustableClock clock = new AdjustableClock(NOW);
        PathLeaseRegistry registry = registry(clock);
        PathLease expired = registry.reserve(request(
                "developer-a", "execution-a", "src/main", OwnershipMode.EXCLUSIVE_WRITE))
                .lease().orElseThrow();

        clock.set(NOW.plus(FIVE_MINUTES));
        assertTrue(registry.reserve(request(
                "developer-b", "execution-b", "src/main", OwnershipMode.EXCLUSIVE_WRITE)).accepted());

        clock.set(NOW.plus(FIVE_MINUTES).minusSeconds(1));
        assertThrows(LeaseNotFoundException.class, () -> registry.heartbeat(
                expired.leaseId(),
                "execution-a",
                expired.version(),
                FIVE_MINUTES));
    }

    @Test
    void heartbeatAndReleaseUseVersionAndExecutionConditions() {
        AdjustableClock clock = new AdjustableClock(NOW);
        PathLeaseRegistry registry = registry(clock);
        PathLease acquired = registry.reserve(request(
                "developer-a", "execution-a", "src/main", OwnershipMode.EXCLUSIVE_WRITE))
                .lease().orElseThrow();

        clock.set(NOW.plusSeconds(30));
        PathLease renewed = registry.heartbeat(
                acquired.leaseId(), "execution-a", acquired.version(), FIVE_MINUTES);

        assertEquals(acquired.version() + 1, renewed.version());
        assertEquals(NOW.plusSeconds(30), renewed.heartbeatAt());
        assertThrows(LeaseVersionConflictException.class,
                () -> registry.release(acquired.leaseId(), "execution-a", acquired.version()));
        assertThrows(LeaseOwnerMismatchException.class,
                () -> registry.release(acquired.leaseId(), "execution-b", renewed.version()));

        registry.release(acquired.leaseId(), "execution-a", renewed.version());
        clock.set(NOW.plusSeconds(31));
        assertTrue(registry.activeLeases().isEmpty());
    }

    @Test
    void normalizesRepositoryRelativePathsAndRejectsTraversal() {
        PathLeaseRegistry registry = registry(new AdjustableClock(NOW));
        PathLease lease = registry.reserve(request(
                "developer-a", "execution-a", "src\\main\\./java/", OwnershipMode.EXCLUSIVE_WRITE))
                .lease().orElseThrow();

        assertEquals("src/main/java", lease.resourcePath());
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(request(
                "developer-b", "execution-b", "../outside", OwnershipMode.EXCLUSIVE_WRITE)));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(request(
                "developer-b", "execution-b", "C:\\outside", OwnershipMode.EXCLUSIVE_WRITE)));
    }

    @Test
    void projectPathPolicyBlocksCaseAliasesOnCaseInsensitiveWorktrees() {
        PathLeaseRegistry registry = new PathLeaseRegistry(
                new AdjustableClock(NOW), RepositoryPathPolicy.caseInsensitive());

        assertTrue(registry.reserve(request(
                "developer-a", "execution-a", "src/Main", OwnershipMode.EXCLUSIVE_WRITE)).accepted());
        assertFalse(registry.reserve(request(
                "developer-b", "execution-b", "src/main/Article.java",
                OwnershipMode.EXCLUSIVE_WRITE)).accepted());
    }

    private static LeaseRequest request(
            String ownerId,
            String executionId,
            String resourcePath,
            OwnershipMode mode) {
        return new LeaseRequest(ownerId, executionId, resourcePath, mode, FIVE_MINUTES);
    }

    private static PathLeaseRegistry registry(Clock clock) {
        return new PathLeaseRegistry(clock, RepositoryPathPolicy.caseSensitive());
    }

    private static final class AdjustableClock extends Clock {

        private Instant instant;

        private AdjustableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
