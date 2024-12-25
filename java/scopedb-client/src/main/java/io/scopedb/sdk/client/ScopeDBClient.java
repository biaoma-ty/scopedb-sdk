/*
 * Copyright 2024 ScopeDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.scopedb.sdk.client;

import com.google.gson.annotations.SerializedName;
import dev.failsafe.RetryPolicy;
import dev.failsafe.RetryPolicyBuilder;
import dev.failsafe.retrofit.FailsafeCall;
import io.scopedb.sdk.client.arrow.ArrowBatchConvertor;
import io.scopedb.sdk.client.exception.ScopeDBException;
import io.scopedb.sdk.client.request.FetchStatementParams;
import io.scopedb.sdk.client.request.IngestData;
import io.scopedb.sdk.client.request.IngestRequest;
import io.scopedb.sdk.client.request.IngestResponse;
import io.scopedb.sdk.client.request.ResultFormat;
import io.scopedb.sdk.client.request.StatementRequest;
import io.scopedb.sdk.client.request.StatementResponse;
import io.scopedb.sdk.client.request.StatementStatus;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.ResponseBody;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.jetbrains.annotations.NotNull;
import retrofit2.Call;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class ScopeDBClient {
    private interface ScopeDBService {
        @POST("/v1/ingest")
        Call<IngestResponse> ingest(@Body IngestRequest request);

        @POST("/v1/statements")
        Call<StatementResponse> submit(@Body StatementRequest request);

        @GET("/v1/statements/{statement_id}")
        Call<StatementResponse> fetch(@Path("statement_id") String statementId, @Query("format") ResultFormat format);
    }

    private static final class EnumConverterFactory extends Converter.Factory {
        private EnumConverterFactory() {}

        @Override
        public Converter<?, String> stringConverter(
                @NotNull Type type, Annotation @NotNull [] annotations, @NotNull Retrofit retrofit) {
            if (getRawType(type).isEnum()) {
                return new EnumConverter();
            }
            return null;
        }

        private static final class EnumConverter implements Converter<Enum<?>, String> {
            @Override
            public String convert(@NotNull Enum<?> o) {
                try {
                    final Field f = o.getClass().getField(o.name());
                    final SerializedName name = f.getAnnotation(SerializedName.class);
                    if (name != null) {
                        return name.value();
                    }
                } catch (Exception ignored) {
                    // passthrough
                }
                return o.name();
            }
        }
    }

    private final ScopeDBService service;

    public ScopeDBClient(ScopeDBConfig config) {
        final Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(config.getEndpoint())
                .addConverterFactory(GsonConverterFactory.create())
                .addConverterFactory(new EnumConverterFactory())
                .build();
        this.service = retrofit.create(ScopeDBService.class);
    }

    public CompletableFuture<IngestResponse> ingestArrowBatch(List<VectorSchemaRoot> batches, String statement) {
        final RetryPolicyBuilder<Response<IngestResponse>> retryPolicyBuilder = createSharedRetryPolicyBuilder();
        final RetryPolicy<Response<IngestResponse>> retryPolicy = retryPolicyBuilder.build();

        final String rows = ArrowBatchConvertor.writeArrowBatch(batches);
        final IngestRequest request = IngestRequest.builder()
                .data(IngestData.builder().rows(rows).build())
                .statement(statement)
                .build();

        final CompletableFuture<IngestResponse> f = new CompletableFuture<>();
        final Call<IngestResponse> call = service.ingest(request);
        FailsafeCall.with(retryPolicy).compose(call).executeAsync().whenComplete((r, t) -> resolveResponse(r, t, f));
        return f;
    }

    public CompletableFuture<StatementResponse> submit(StatementRequest request, boolean waitUntilDone) {
        final RetryPolicyBuilder<Response<StatementResponse>> retryPolicyBuilder = createSharedRetryPolicyBuilder();
        final RetryPolicy<Response<StatementResponse>> retryPolicy = retryPolicyBuilder.build();

        final CompletableFuture<StatementResponse> f = new CompletableFuture<>();
        final Call<StatementResponse> call = service.submit(request);
        FailsafeCall.with(retryPolicy).compose(call).executeAsync().whenComplete((r, t) -> {
            if (waitUntilDone && r.isSuccessful()) {
                final StatementResponse resp = r.body();
                if (resp != null && resp.getStatus() != StatementStatus.Finished) {
                    final FetchStatementParams params = FetchStatementParams.builder()
                            .statementId(resp.getStatementId())
                            .format(request.getFormat())
                            .build();
                    fetch(params, true).whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            f.completeExceptionally(throwable);
                        } else {
                            f.complete(response);
                        }
                    });
                    return;
                }
            }

            resolveResponse(r, t, f);
        });
        return f;
    }

    public CompletableFuture<StatementResponse> fetch(FetchStatementParams params, boolean retryUntilDone) {
        final RetryPolicyBuilder<Response<StatementResponse>> retryPolicyBuilder = createSharedRetryPolicyBuilder();
        final RetryPolicy<Response<StatementResponse>> retryPolicy;
        if (retryUntilDone) {
            retryPolicy = retryPolicyBuilder
                    .handleResultIf(response -> {
                        // statement is not done; retry
                        if (response.isSuccessful()) {
                            final StatementResponse statementResponse = response.body();
                            return statementResponse != null
                                    && statementResponse.getStatus() != StatementStatus.Finished;
                        }

                        // all non-200 responses are considered as permanently failed
                        return false;
                    })
                    .build();
        } else {
            retryPolicy = retryPolicyBuilder.build();
        }

        final CompletableFuture<StatementResponse> f = new CompletableFuture<>();
        final Call<StatementResponse> call = service.fetch(params.getStatementId(), params.getFormat());
        FailsafeCall.with(retryPolicy).compose(call).executeAsync().whenComplete((r, t) -> resolveResponse(r, t, f));
        return f;
    }

    private static <T> RetryPolicyBuilder<Response<T>> createSharedRetryPolicyBuilder() {
        return RetryPolicy.<Response<T>>builder()
                .withJitter(0.15)
                .withMaxDuration(Duration.ofSeconds(60))
                .withDelay(Duration.ofSeconds(1));
    }

    private static <T> void resolveResponse(Response<T> r, Throwable t, CompletableFuture<T> f) {
        if (t != null) {
            f.completeExceptionally(t);
            return;
        }

        if (r.isSuccessful()) {
            f.complete(r.body());
            return;
        }

        try (final ResponseBody error = r.errorBody()) {
            if (error != null) {
                f.completeExceptionally(ScopeDBException.fromResponse(r.code(), error.string()));
            } else {
                f.completeExceptionally(ScopeDBException.fromResponse(r.code(), null));
            }
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
    }
}
