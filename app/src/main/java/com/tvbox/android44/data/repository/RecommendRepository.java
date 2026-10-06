package com.tvbox.android44.data.repository;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.ErrorKind;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.remote.AiClient;
import com.tvbox.android44.data.remote.CancelScope;
import com.tvbox.android44.domain.model.AiProvider;
import com.tvbox.android44.domain.model.AiRecommendItem;
import com.tvbox.android44.domain.parser.AiRecommendParser;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

/** AI 推荐仓库：Chat Completions 兼容请求 + 容错解析。 */
public class RecommendRepository {

    public interface Callback {
        void onResult(Result<List<AiRecommendItem>> result);
    }

    public static final String SYSTEM_PROMPT =
            "你是一个影视推荐助手。请根据用户需求推荐 6 到 8 部影视作品。"
                    + "只输出 JSON，不要输出其他说明文字。格式："
                    + "{\"recommendations\":[{\"title\":\"片名\","
                    + "\"searchKeyword\":\"搜索关键词（只包含片名，不带年份、季数和解释）\","
                    + "\"reason\":\"一句话推荐理由\"}]}。"
                    + "片名使用通用中文译名，便于中文影视站搜索。";

    private final ExecutorService executor;
    private final SettingsRepository settings;
    private final AiClient client;

    public RecommendRepository(ExecutorService executor, SettingsRepository settings) {
        this(executor, settings, new AiClient());
    }

    RecommendRepository(ExecutorService executor, SettingsRepository settings, AiClient client) {
        this.executor = executor;
        this.settings = settings;
        this.client = client;
    }

    public Handle ask(final String userQuery, final Callback cb) {
        final AiProvider provider = settings.aiProvider();
        final String model = settings.aiModel();
        final String key = settings.aiApiKey();
        final CancelScope scope = new CancelScope();
        if (provider == null || key.isEmpty() || model.isEmpty()) {
            deliver(scope, cb, new Result.Failure<List<AiRecommendItem>>(
                    ErrorKind.PERMISSION, "请先在设置中配置 AI 提供方、模型和 API Key", null));
            return new Handle(scope, null);
        }
        final FutureTask<Result<List<AiRecommendItem>>> task =
                new FutureTask<Result<List<AiRecommendItem>>>(
                        new java.util.concurrent.Callable<Result<List<AiRecommendItem>>>() {
                            @Override
                            public Result<List<AiRecommendItem>> call() {
                                try {
                                    AiClient.ChatResult chat = client.chat(
                                            provider.apiBase, key, model,
                                            SYSTEM_PROMPT, userQuery.trim(), scope);
                                    if (chat.httpCode == 401 || chat.httpCode == 403) {
                                        return new Result.Failure<List<AiRecommendItem>>(
                                                ErrorKind.PERMISSION, "API Key 无效或无权限，请检查配置", null);
                                    }
                                    if (chat.httpCode == 429) {
                                        return new Result.Failure<List<AiRecommendItem>>(
                                                ErrorKind.HTTP, "请求过于频繁，请稍后重试", null);
                                    }
                                    if (chat.content == null) {
                                        return new Result.Failure<List<AiRecommendItem>>(
                                                ErrorKind.EMPTY_BODY, "AI 未返回内容，请重试", null);
                                    }
                                    List<AiRecommendItem> items = AiRecommendParser.parse(chat.content);
                                    if (items.isEmpty()) {
                                        return new Result.Failure<List<AiRecommendItem>>(
                                                ErrorKind.PARSE, "AI 返回格式无法识别，请重试", null);
                                    }
                                    return new Result.Success<List<AiRecommendItem>>(items);
                                } catch (Exception e) {
                                    if (scope.isCancelled()) {
                                        return cancelled();
                                    }
                                    ErrorKind kind = e instanceof AiClient.ResponseException
                                            ? ((AiClient.ResponseException) e).kind : ErrorKind.fromException(e);
                                    return new Result.Failure<List<AiRecommendItem>>(
                                            kind, kind == ErrorKind.PARSE ? "AI 返回格式无法识别，请重试"
                                            : kind == ErrorKind.EMPTY_BODY ? "AI 未返回内容，请重试" : kind.userMessage(), e);
                                }
                            }
                        }) {
            @Override protected void done() {
                if (scope.isCancelled() || isCancelled()) return;
                try {
                    deliver(scope, cb, get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.CancellationException ignored) {
                } catch (java.util.concurrent.ExecutionException e) {
                    deliver(scope, cb, new Result.Failure<List<AiRecommendItem>>(
                            ErrorKind.OTHER, "推荐请求失败，请重试", e.getCause()));
                }
            }
        };
        executor.execute(task);
        return new Handle(scope, task);
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> cancelled() {
        return (Result<T>) Result.Cancelled.INSTANCE;
    }

    private static void deliver(final CancelScope scope, final Callback cb, final Result<List<AiRecommendItem>> r) {
        TvBoxApp.get().executors().main(new Runnable() {
            @Override
            public void run() {
                if (!scope.isCancelled()) cb.onResult(r);
            }
        });
    }

    public static final class Handle {
        private final CancelScope scope;
        private final FutureTask<?> task;

        Handle(CancelScope scope, FutureTask<?> task) {
            this.scope = scope;
            this.task = task;
        }

        public void cancel() {
            if (scope != null) {
                scope.cancel();
            }
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}
