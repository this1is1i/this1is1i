package io.refactorcontrolplane.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.refactorcontrolplane.executor.ExecutionProtocol.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Submit once; all subsequent interactions are status queries for the same durable ID. */
public final class HttpTaskExecutor implements TaskExecutor {
    private final ExecutorSettings.Definition settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    public HttpTaskExecutor(ExecutorSettings.Definition settings, ObjectMapper mapper) {
        this.settings = settings; this.mapper = mapper;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Result start(Request request) { return exchange(request, true); }
    @Override public Result query(Request request) { return exchange(request, false); }
    private Result exchange(Request request, boolean submit) {
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var builder = HttpRequest.newBuilder(URI.create(settings.baseUrl().replaceAll("/+$", "") + "/executions/" + request.execution().executionId()))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds())).header("Authorization", "Bearer " + settings.token())
                    .header("Accept", "application/json");
            if (submit) builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
            else builder.GET();
            // Limiting the subscriber bounds chunked responses too; timeout covers the complete body.
            future = client.sendAsync(builder.build(), info -> new LimitedBody());
            var response = future.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() == 404 && !submit) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            var json = mapper.readTree(response.body());
            ExecutorModelRun.validate(json);
            if (!json.path("sequence").isIntegralNumber() || !json.path("sequence").canConvertToLong())
                throw new IllegalStateException("Executor sequence must be an integer");
            if (json.has("checks")) {
                if (!json.path("checks").isArray() || json.path("checks").size() > 10) throw new IllegalStateException("Invalid executor checks");
                for (var check : json.path("checks")) if (!check.path("exitCode").isIntegralNumber()
                        || !check.path("exitCode").canConvertToInt() || !check.path("timedOut").isBoolean() || !check.path("truncated").isBoolean())
                    throw new IllegalStateException("Executor check requires exact exit code, timeout and truncation fields");
            }
            return mapper.treeToValue(json, Result.class);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
        catch (java.io.IOException | ExecutionException | TimeoutException ex) { return null; }
        finally { if (future != null && !future.isDone()) future.cancel(true); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long received;
        private boolean rejected;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if (rejected) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > 65536) { rejected = true; subscription.cancel(); delegate.onError(new java.io.IOException("Executor response exceeds limit")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!rejected) delegate.onError(error); }
        public void onComplete() { if (!rejected) delegate.onComplete(); }
    }
}
