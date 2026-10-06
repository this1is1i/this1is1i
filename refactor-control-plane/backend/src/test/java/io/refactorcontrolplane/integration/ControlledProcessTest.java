package io.refactorcontrolplane.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledProcessTest {
    @TempDir Path directory;
    List<String> fixture(String body) throws Exception {
        Path source = directory.resolve("Probe.java");
        Files.writeString(source, "class Probe { public static void main(String[] args) throws Exception { " + body + " } }", StandardCharsets.UTF_8);
        return List.of(IntegrationWorkflowTest.java("java"), source.toString());
    }
    @Test void stdoutAndStderrAreDrainedAndBoundedWithoutDeadlock() throws Exception {
        var command = fixture("for(int i=0;i<100000;i++){System.out.print('x');System.err.print('y');}");
        var result = ControlledProcess.run(directory, command, Map.of(), new byte[0], 20, 4096);
        assertEquals(0, result.exitCode()); assertTrue(result.truncated());
        assertEquals(4096, result.stdout().length); assertEquals(4096, result.stderr().length);
        assertFalse(result.passed());
    }
    @Test void timeoutTerminatesTheProcess() throws Exception {
        var command = fixture("Thread.sleep(60000);");
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            var result = ControlledProcess.run(directory, command, Map.of(), new byte[0], 2, 4096);
            assertTrue(result.timedOut()); assertFalse(result.passed());
        });
    }
    @Test void interruptCleansUpBeforeWaitingForOutputReaders() throws Exception {
        var command = fixture("java.nio.file.Files.writeString(java.nio.file.Path.of(\"process.pid\"),Long.toString(ProcessHandle.current().pid()));Thread.sleep(60000);");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { ControlledProcess.run(directory, command, Map.of(), new byte[0], 60, 4096); }
            catch (Throwable ex) { failure.set(ex); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(directory.resolve("process.pid")) && System.nanoTime() < deadline) Thread.sleep(20);
        thread.interrupt(); thread.join(5000);
        assertFalse(thread.isAlive(), "Interrupted execution must not wait for the child to exit itself");
        assertInstanceOf(IllegalStateException.class, failure.get());
        long pid = Long.parseLong(Files.readString(directory.resolve("process.pid"), StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void backgroundChildrenAreRejectedAndCleanedUp() throws Exception {
        Files.writeString(directory.resolve("Child.java"), "class Child {public static void main(String[] args)throws Exception {Thread.sleep(60000);}}", StandardCharsets.UTF_8);
        var command = fixture("var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty(\"java.home\"),\"bin\",\"java\").toString(),\"Child.java\").inheritIO().start();"
                + "java.nio.file.Files.writeString(java.nio.file.Path.of(\"child.pid\"),Long.toString(child.pid()));Thread.sleep(500);");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IllegalStateException.class,
                () -> ControlledProcess.run(directory, command, Map.of(), new byte[0], 8, 4096)));
        long pid = Long.parseLong(Files.readString(directory.resolve("child.pid"), StandardCharsets.UTF_8));
        ProcessHandle.of(pid).ifPresent(child -> {
            try { child.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ex) { fail("Child process survived cleanup", ex); }
        });
    }
}
