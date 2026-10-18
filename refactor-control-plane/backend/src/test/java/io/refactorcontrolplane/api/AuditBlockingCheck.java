package io.refactorcontrolplane.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

/** Real validation process used to prove audit reads do not wait for the integration service monitor. */
public final class AuditBlockingCheck {
    public static void main(String[] args) throws Exception {
        Path barrier=Path.of(args[0]);
        if(!Files.exists(barrier.resolve("block")))return;
        Files.writeString(barrier.resolve("entered"),"running",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(!Files.exists(barrier.resolve("release"))) {
            if(System.nanoTime()>deadline)throw new IllegalStateException("Audit concurrency barrier timed out");
            Thread.sleep(25);
        }
    }
}
