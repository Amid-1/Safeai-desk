package ru.safeai.gateway.ai.provider;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractBufferingClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP exchange per adapter invocation. No hidden retry, redirect,
 * decompression or HTTP/2 stream replay. Buffer both success and error bodies
 * under the same byte and absolute-time bounds before Spring interprets status.
 */
@NullMarked
final class AiBoundedHttpRequestFactory implements ClientHttpRequestFactory {
    private final HttpClient client;
    private final long attemptNanos;
    private final int maxBytes;

    AiBoundedHttpRequestFactory(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        int connectMillis = Math.toIntExact(Math.max(1, connectTimeout.toMillis()));
        this.attemptNanos = connectTimeout.plus(readTimeout).toNanos();
        this.maxBytes = maxBytes;
        this.client = HttpClient.create()
            .protocol(HttpProtocol.HTTP11)
            .disableRetry(true)
            .followRedirect(false)
            .compress(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
            .responseTimeout(readTimeout);
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new AbstractBufferingClientHttpRequest() {
            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            protected ClientHttpResponse executeInternal(HttpHeaders headers, byte[] body) throws IOException {
                return exchange(uri, method, headers, body);
            }
        };
    }

    private ClientHttpResponse exchange(URI uri, HttpMethod method, HttpHeaders headers, byte[] body)
        throws IOException {
        long deadline = System.nanoTime() + attemptNanos;
        if (Thread.currentThread().isInterrupted()) throw interrupted();
        if (AiProviderDispatchDeadline.remaining(deadline) <= 0) throw timeout();
        var sender = client.headers(target -> {
                headers.forEach(target::set);
                target.set("Accept-Encoding", "identity");
            })
            .request(io.netty.handler.codec.http.HttpMethod.valueOf(method.name()))
            .uri(uri.toASCIIString());
        HttpClient.ResponseReceiver<?> receiver = body.length == 0 ? sender
            : sender.send(Mono.fromSupplier(() -> Unpooled.wrappedBuffer(body)));
        if (AiProviderDispatchDeadline.remaining(deadline) <= 0) throw timeout();
        var future = receiver.response((response, inbound) -> readResponse(method, response, inbound))
            .single().toFuture();
        try {
            long remaining = AiProviderDispatchDeadline.remaining(deadline);
            if (remaining <= 0) throw timeout();
            var response = future.get(remaining, TimeUnit.NANOSECONDS);

            if (response == null) {
                throw new IOException("AI provider returned no HTTP response");
            }

            return response;
        } catch (TimeoutException e) {
            throw timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted();
        } catch (ExecutionException e) {
            throw transportFailure(e);
        } finally {
            // Cancellation propagates to the Netty subscription and closes an
            // unfinished exchange. Any race with transmission remains ambiguous.
            if (!future.isDone()) future.cancel(true);
        }
    }

    private Mono<BufferedResponse> readResponse(
        HttpMethod method, HttpClientResponse response, ByteBufFlux inbound
    ) {
        int status = response.status().code();
        if (status < 200 || (status >= 300 && status < 400) || status >= 600) {
            return Mono.error(new IOException("Unexpected AI provider HTTP status"));
        }
        HttpHeaders headers = new HttpHeaders();
        response.responseHeaders().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
        var encodings = headers.get(HttpHeaders.CONTENT_ENCODING);
        if (encodings != null && (encodings.size() != 1
            || !"identity".equalsIgnoreCase(encodings.getFirst().trim()))) {
            return Mono.error(new IOException("Unsupported AI provider content encoding"));
        }
        var lengths = headers.get(HttpHeaders.CONTENT_LENGTH);
        if (lengths != null && (lengths.size() != 1 || headers.get(HttpHeaders.TRANSFER_ENCODING) != null)) {
            return Mono.error(new IOException("Invalid AI provider response framing"));
        }
        long declared = headers.getContentLength();
        if (declared > maxBytes) return Mono.error(new AiResponseTooLargeIOException(maxBytes));
        // A large declared length alone must not allocate a large buffer.
        var output = new ByteArrayOutputStream(
            Math.clamp(declared, 0, 8192)
        );
        return inbound.handle((buffer, sink) -> {
            int count = buffer.readableBytes();
            if (count > maxBytes - output.size()) {
                sink.error(new AiResponseTooLargeIOException(maxBytes));
                return;
            }
            byte[] bytes = new byte[count];
            buffer.readBytes(bytes);
            output.writeBytes(bytes);
        }).then(Mono.defer(() -> {
            if (!HttpMethod.HEAD.equals(method) && status != 204
                && declared >= 0 && output.size() != declared) {
                return Mono.error(new IOException("Incomplete AI provider response"));
            }
            return Mono.just(new BufferedResponse(
                HttpStatusCode.valueOf(status), headers, output.toByteArray()));
        }));
    }

    private static IOException transportFailure(ExecutionException failure) {
        for (Throwable cause = failure.getCause();
             cause != null;
             cause = cause.getCause()) {

            switch (cause) {
                case AiResponseTooLargeIOException oversized -> {
                    return oversized;
                }
                case ReadTimeoutException ignored -> {
                    return timeout();
                }
                case IOException io -> {
                    return io;
                }
                default -> {
                    // Continue inspecting the cause chain.
                }
            }
        }

        return new IOException("AI provider transport failed", failure);
    }

    private static HttpTimeoutException timeout() {
        return new HttpTimeoutException("AI provider HTTP deadline exceeded");
    }

    private static InterruptedIOException interrupted() {
        return new InterruptedIOException("AI provider HTTP interrupted");
    }

    private record BufferedResponse(HttpStatusCode status, HttpHeaders headers, byte[] bytes)
        implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return Integer.toString(status.value());
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() { /* No network resource remains. */ }
    }
}
