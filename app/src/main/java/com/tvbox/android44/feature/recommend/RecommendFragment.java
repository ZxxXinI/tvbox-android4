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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.ui.ChipAdapter;
import com.tvbox.android44.data.repository.RecommendRepository;
import com.tvbox.android44.domain.model.AiRecommendItem;
import com.tvbox.android44.feature.main.MainActivity;

import java.util.ArrayList;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
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
            state.showEmpty("输入需求或选择快捷词，按「推荐」获取 AI 影视推荐");
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
            Toast.makeText(getActivity(), "语音识别不可用，请使用文字输入", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_MIC
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice();
        } else if (requestCode == REQUEST_MIC) {
            Toast.makeText(getActivity(), "未授予麦克风权限，语音不可用，请使用文字输入",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_VOICE) {
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
        Toast.makeText(getActivity(), "未识别到语音内容，请使用文字输入", Toast.LENGTH_SHORT).show();
    }

    // ===== 请求 =====

    private void ask(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            Toast.makeText(getActivity(), "请先输入推荐需求", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(getActivity(), "正在请求中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (query.length() > AppConstants.SEARCH_QUERY_MAX_LENGTH) {
            query = query.substring(0, AppConstants.SEARCH_QUERY_MAX_LENGTH);
        }
        if (handle != null) {
            handle.cancel();
        }
        final String q = query;
        lastQuery = q;
        busy = true;
        hideKeyboard();
        if (adapter.getItemCount() == 0) {
            state.showLoading("AI 正在思考…");
        } else {
            setStatus("正在按「" + q + "」重新推荐…");
        }

        handle = TvBoxApp.get().recommend().ask(q, new RecommendRepository.Callback() {
            @Override
            public void onResult(Result<List<AiRecommendItem>> result) {
                if (!isAdded()) {
                    return;
                }
                busy = false;
                if (result.isCancelled()) {
                    return;
                }
                if (result.isSuccess() && result.data() != null && !result.data().isEmpty()) {
                    adapter.setItems(result.data());
                    state.showContent();
                    setStatus("按「" + q + "」推荐了 " + result.data().size()
                            + " 部作品，按确认键搜索片源");
                    if (listView.findFocus() == null && listView.getChildCount() > 0) {
                        listView.getChildAt(0).requestFocus();
                    }
                    return;
                }
                String message = result.asFailure() != null
                        ? result.asFailure().userMessage : "AI 未返回内容，请重试";
                if (result.asFailure() != null && result.asFailure().kind == com.tvbox.android44.common.ErrorKind.PERMISSION) {
                    // Key 未配置：清空旧结果，引导去设置（此后无法使用推荐）
                    adapter.setItems(new ArrayList<AiRecommendItem>());
                    state.showEmpty(message + "。可在「设置 → AI 推荐」中扫码配置。");
                    setStatus(null);
                    return;
                }
                if (adapter.getItemCount() > 0) {
                    // 非阻断错误：保留上一批结果
                    setStatus("本次推荐失败：" + message + "（当前展示上一批结果）");
                } else {
                    state.showError(message + "，请重试。", new StateLayout.OnRetryListener() {
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
        if (handle != null) {
            handle.cancel();
        }
        super.onDestroyView();
    }

    // ===== 结果列表 =====

    static final class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.Holder> {

        interface OnItemClick {
            void onItemClick(AiRecommendItem item);
        }

        private final List<AiRecommendItem> items = new ArrayList<AiRecommendItem>();
        private OnItemClick listener;

        void setListener(OnItemClick listener) {
            this.listener = listener;
        }

        void setItems(List<AiRecommendItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
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
                    ? "—" : item.reason);
            holder.hint.setText("搜索《" + item.searchKeyword + "》");
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
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
