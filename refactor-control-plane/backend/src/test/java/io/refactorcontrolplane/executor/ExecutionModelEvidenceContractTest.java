package io.refactorcontrolplane.executor;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import io.refactorcontrolplane.governance.EvidenceStore;
import io.refactorcontrolplane.mcp.McpProjectRuntime.Execution;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class ExecutionModelEvidenceContractTest {
    @Test void verifiesTypedReceiptsAfterJsonStorageNormalizesSmallLongsToIntegerNodes() {
        var mapper = new ObjectMapper();
        var db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        try {
            var evidence = new EvidenceStore(new JdbcTemplate(db), mapper); var service = new ExecutionModelEvidence(evidence, mapper);
            var request = new Request("a".repeat(64), "b".repeat(64), 1, 1L, "developer", "http", "c".repeat(64),
                    new Execution("TASK", "execution", "branch", ".", "d".repeat(40), null), Set.of("src"), Set.of(".git"), List.of(), Instant.now());
            var model = new ExecutorModelRun("model-1", "model", "v1", "1".repeat(64), "2".repeat(64), "3".repeat(64), 0, List.of("unused"), List.of());
            var result = new Result("execution", request.requestHash(), 2, "SUCCEEDED", "e".repeat(40), List.of(), List.of(model));
            var binding = new EvidenceStore.Binding(request.planScope(), "TASK", "execution", result.commitSha(), 1, 1, 1, 1);
            String id = service.record(request, result, binding);
            assertEquals(id, service.record(request, result, binding));
            assertDoesNotThrow(() -> service.verify(new Run(request, "SUCCEEDED", result, "now", id)));
            var runs = new ArrayList<ExecutorModelRun>();
            for (int i = 0; i < 12; i++) runs.add(new ExecutorModelRun("model-" + i, "model", "v1", "1".repeat(64), "2".repeat(64), "3".repeat(64), 0,
                    Collections.nCopies(50, "t".repeat(100)), List.of()));
            var wire = mapper.valueToTree(new Result("execution", request.requestHash(), 2, "SUCCEEDED", result.commitSha(), List.of(), runs));
            var tools = (com.fasterxml.jackson.databind.node.ArrayNode) wire.path("modelRuns").get(11).path("requestedTools");
            while (assertDoesNotThrow(() -> mapper.writeValueAsBytes(wire)).length > 65536) tools.remove(tools.size() - 1);
            int gap = 65536 - assertDoesNotThrow(() -> mapper.writeValueAsBytes(wire)).length;
            if (gap > 0 && gap < 3) { tools.set(0, mapper.getNodeFactory().textNode("t".repeat(97))); gap += 3; }
            if (gap >= 3) tools.add("t".repeat(gap - 3));
            assertEquals(65536, assertDoesNotThrow(() -> mapper.writeValueAsBytes(wire)).length);
            var boundedResponse = assertDoesNotThrow(() -> mapper.treeToValue(wire, Result.class));
            assertThrows(IllegalArgumentException.class, () -> service.validate(request, boundedResponse), "A bounded HTTP body can still produce oversized bound evidence");
        } finally { db.shutdown(); }
    }
}
