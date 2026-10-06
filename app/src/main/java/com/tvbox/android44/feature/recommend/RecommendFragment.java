package com.tvbox.android44.feature.recommend;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.ui.ChipAdapter;
import com.tvbox.android44.data.repository.RecommendRepository;
import com.tvbox.android44.domain.model.AiRecommendItem;
import com.tvbox.android44.feature.main.MainActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * AI 推荐页（文档 09 §3/§4）：
 * - 文字输入 / 快捷词 / 换一批；点击条目按 searchKeyword 进入普通多来源搜索。
 * - 加载中禁止重复提交；重提取消旧请求。
 * - AI 失败不清空上一批结果，仅在状态行显示非阻断错误。
 * - 语音可降级：无识别服务隐藏入口；API 23+ 动态申请麦克风权限。
 */
public class RecommendFragment extends Fragment {

    private static final String[] QUICK_WORDS = {
            "最近热播的悬疑剧", "适合全家看的喜剧电影", "高分国产电视剧",
            "经典香港警匪片", "近期好看的综艺", "烧脑科幻电影",
            "治愈系日剧", "适合孩子看的动画片"
    };
    private static final int VISIBLE_CHIPS = 4;
    private static final int REQUEST_VOICE = 1001;
    private static final int REQUEST_MIC = 1002;

    private EditText input;
    private TextView status;
    private TextView voiceButton;
    private TextView askButton;
    private StateLayout state;
    private RecyclerView listView;
    private ChipAdapter chipsAdapter;

    private final RecommendAdapter adapter = new RecommendAdapter();
    private RecommendRepository.Handle handle;
    private boolean busy;
    private int chipOffset;
    private String lastQuery = "";
    private int requestGeneration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ++requestGeneration;
        busy = false;
        View root = inflater.inflate(R.layout.fragment_recommend, container, false);
        input = root.findViewById(R.id.recommend_input);
        voiceButton = root.findViewById(R.id.recommend_voice);
        askButton = root.findViewById(R.id.recommend_ask);
        status = root.findViewById(R.id.recommend_status);
        state = root.findViewById(R.id.recommend_state);
        listView = root.findViewById(R.id.recommend_list);

        listView.setLayoutManager(new LinearLayoutManager(getActivity()));
        int spacing = (int) (getResources().getDisplayMetrics().density * 10);
        listView.addItemDecoration(new com.tvbox.android44.common.ui.VerticalSpacingDecoration(spacing));
        adapter.setListener(new RecommendAdapter.OnItemClick() {
            @Override
            public void onItemClick(AiRecommendItem item) {
                if (getActivity() instanceof MainActivity) {
                    hideKeyboard();
                    ((MainActivity) getActivity()).switchToSearchWithQuery(item.searchKeyword);
                }
            }
        });
        listView.setAdapter(adapter);

        RecyclerView chips = root.findViewById(R.id.recommend_chips);
        chips.setLayoutManager(
                new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
        chipsAdapter = new ChipAdapter(new ChipAdapter.OnChipClick() {
            @Override
            public void onChipClick(ChipAdapter.Chip chip, int position) {
                input.setText(chip.label);
                ask(chip.label);
            }
        });
        chips.setAdapter(chipsAdapter);
        rebuildChips();

        askButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ask(input.getText().toString());
            }
        });
        root.findViewById(R.id.recommend_shuffle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chipOffset = (chipOffset + VISIBLE_CHIPS) % QUICK_WORDS.length;
                rebuildChips();
                String query = !lastQuery.isEmpty() ? lastQuery
                        : QUICK_WORDS[chipOffset];
                ask(query);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    ask(input.getText().toString());
                    return true;
                }
                return false;
            }
        });

        // 语音降级：系统无识别服务时不显示入口，也不视为异常（文档 00 §5）
        if (!speechServiceAvailable()) {
            voiceButton.setVisibility(View.GONE);
        } else {
            voiceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startVoiceIfPermitted();
                }
            });
        }

        if (adapter.getItemCount() == 0) {
            state.showEmpty(getString(R.string.recommend_empty));
        }
        return root;
    }

    private void rebuildChips() {
        List<ChipAdapter.Chip> list = new ArrayList<ChipAdapter.Chip>();
        for (int i = 0; i < VISIBLE_CHIPS; i++) {
            String word = QUICK_WORDS[(chipOffset + i) % QUICK_WORDS.length];
            list.add(new ChipAdapter.Chip("quick-" + (chipOffset + i) % QUICK_WORDS.length,
                    word, false));
        }
        chipsAdapter.setChips(list);
    }

    private boolean speechServiceAvailable() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        return !getActivity().getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    private void startVoiceIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        startVoice();
    }

    private void startVoice() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (Exception e) {
            // 识别服务异常时回退为文字输入，不崩溃
            Toast.makeText(getActivity(), R.string.voice_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (!isAdded() || getView() == null || isHidden()) return;
        if (requestCode == REQUEST_MIC
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice();
        } else if (requestCode == REQUEST_MIC) {
            Toast.makeText(getActivity(), R.string.voice_permission_denied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_VOICE || !isAdded() || getView() == null || isHidden()) {
            return;
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            List<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty() && !results.get(0).trim().isEmpty()) {
                String text = results.get(0).trim();
                input.setText(text);
                ask(text);
                return;
            }
        }
        Toast.makeText(getActivity(), R.string.voice_empty, Toast.LENGTH_SHORT).show();
    }

    // ===== 请求 =====

    private void ask(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            Toast.makeText(getActivity(), R.string.recommend_need_query, Toast.LENGTH_SHORT).show();
            return;
        }
        if (query.length() > AppConstants.SEARCH_QUERY_MAX_LENGTH) {
            query = query.substring(0, AppConstants.SEARCH_QUERY_MAX_LENGTH);
        }
        if (busy && query.equals(lastQuery)) {
            Toast.makeText(getActivity(), R.string.recommend_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (handle != null) {
            handle.cancel();
        }
        final String q = query;
        final int generation = ++requestGeneration;
        lastQuery = q;
        busy = true;
        hideKeyboard();
        if (adapter.getItemCount() == 0) {
            state.showLoading(getString(R.string.recommend_loading));
        } else {
            setStatus(getString(R.string.recommend_refreshing, q));
        }

        handle = TvBoxApp.get().recommend().ask(q, new RecommendRepository.Callback() {
            @Override
            public void onResult(Result<List<AiRecommendItem>> result) {
                if (!isAdded() || getView() == null || generation != requestGeneration) {
                    return;
                }
                busy = false;
                if (result.isCancelled()) {
                    return;
                }
                if (result.isSuccess() && result.data() != null && !result.data().isEmpty()) {
                    adapter.setItems(result.data());
                    state.showContent();
                    setStatus(getString(R.string.recommend_count, q, adapter.getItemCount()));
                    return;
                }
                String message = result.asFailure() != null
                        ? result.asFailure().userMessage : getString(R.string.recommend_no_content);
                if (result.asFailure() != null && result.asFailure().kind == com.tvbox.android44.common.ErrorKind.PERMISSION) {
                    String guidance = getString(R.string.recommend_setup_hint, message);
                    if (adapter.getItemCount() > 0) {
                        state.showContent();
                        setStatus(getString(R.string.recommend_failed_previous, guidance));
                    } else {
                        state.showEmpty(guidance);
                        setStatus(null);
                    }
                    return;
                }
                if (adapter.getItemCount() > 0) {
                    // 非阻断错误：保留上一批结果
                    setStatus(getString(R.string.recommend_failed_previous, message));
                } else {
                    state.showError(getString(R.string.error_retry, message), new StateLayout.OnRetryListener() {
                        @Override
                        public void onRetry() {
                            ask(q);
                        }
                    });
                    setStatus(null);
                }
            }
        });
    }

    private void setStatus(String text) {
        if (text == null) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void hideKeyboard() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && input.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    @Override
    public void onDestroyView() {
        ++requestGeneration;
        busy = false;
        if (handle != null) {
            handle.cancel();
        }
        super.onDestroyView();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden && busy) {
            ++requestGeneration;
            if (handle != null) handle.cancel();
            busy = false;
            if (getView() != null) {
                if (adapter.getItemCount() == 0) state.showEmpty(getString(R.string.recommend_cancelled));
                else setStatus(getString(R.string.recommend_cancelled));
            }
        }
    }

    // ===== 结果列表 =====

    static final class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.Holder> {

        interface OnItemClick {
            void onItemClick(AiRecommendItem item);
        }

        private final List<AiRecommendItem> items = new ArrayList<AiRecommendItem>();
        private OnItemClick listener;

        RecommendAdapter() {
            setHasStableIds(true);
            setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        }

        void setListener(OnItemClick listener) {
            this.listener = listener;
        }

        void setItems(List<AiRecommendItem> list) {
            final List<AiRecommendItem> old = new ArrayList<AiRecommendItem>(items);
            LinkedHashMap<String, AiRecommendItem> unique = new LinkedHashMap<String, AiRecommendItem>();
            for (AiRecommendItem item : list) {
                if (!unique.containsKey(key(item))) unique.put(key(item), item);
            }
            final List<AiRecommendItem> next = new ArrayList<AiRecommendItem>(unique.values());
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return next.size(); }
                @Override public boolean areItemsTheSame(int before, int after) {
                    return key(old.get(before)).equals(key(next.get(after)));
                }
                @Override public boolean areContentsTheSame(int before, int after) {
                    String a = old.get(before).reason, b = next.get(after).reason;
                    return a == null ? b == null : a.equals(b);
                }
            });
            items.clear();
            items.addAll(next);
            diff.dispatchUpdatesTo(this);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recommend, parent, false);
            FocusScaler.attach(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, int position) {
            final AiRecommendItem item = items.get(position);
            holder.title.setText(item.title);
            holder.reason.setText(item.reason == null || item.reason.isEmpty()
                    ? holder.itemView.getContext().getString(R.string.no_description) : item.reason);
            holder.hint.setText(holder.itemView.getContext().getString(R.string.search_title_hint, item.searchKeyword));
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        int current = holder.getBindingAdapterPosition();
                        if (current != RecyclerView.NO_POSITION) listener.onItemClick(items.get(current));
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override public long getItemId(int position) {
            return PageFocusState.stableId(key(items.get(position)));
        }

        private static String key(AiRecommendItem item) {
            return item.title + "\u001f" + item.searchKeyword;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView reason;
            final TextView hint;

            Holder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.recommend_item_title);
                reason = itemView.findViewById(R.id.recommend_item_reason);
                hint = itemView.findViewById(R.id.recommend_item_hint);
            }
        }
    }
}
