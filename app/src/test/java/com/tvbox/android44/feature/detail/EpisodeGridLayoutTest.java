package com.tvbox.android44.feature.detail;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.tvbox.android44.R;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.domain.model.PlayEpisode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28})
public class EpisodeGridLayoutTest {
    private void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void checkLayout(int screenDp, float fontScale) {
        Context context = com.tvbox.android44.common.FontScale.withScale(
                RuntimeEnvironment.getApplication(), fontScale);
        context.setTheme(R.style.Theme_TvBox);
        float density = context.getResources().getDisplayMetrics().density;
        View detail = LayoutInflater.from(context).inflate(R.layout.activity_detail, null);
        RecyclerView grid = detail.findViewById(R.id.detail_episodes);
        final int[] clicked = {-1};
        EpisodeAdapter adapter = new EpisodeAdapter(index -> clicked[0] = index);
        EpisodeGridLayoutManager manager = new EpisodeGridLayoutManager(context, adapter);
        grid.setLayoutManager(manager);
        grid.addItemDecoration(new GridSpacingDecoration((int) (8 * density)));
        grid.setAdapter(adapter);
        List<PlayEpisode> episodes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            episodes.add(new PlayEpisode(i, "第" + (i + 1) + "集", "https://example.com/" + i));
        }
        adapter.setEpisodes("line", episodes, 1);
        measure(detail, (int) (screenDp * density), (int) (540 * density));
        assertTrue(grid.getWidth() > 0);
        assertTrue(manager.getSpanCount() >= 1 && manager.getSpanCount() <= 6);
        assertTrue("Narrow projection screen must reduce the old six columns",
                screenDp > 720 || manager.getSpanCount() < 6);
        TextView first = (TextView) grid.findViewHolderForAdapterPosition(0).itemView;
        assertTrue("Episode text must fit its actual content width",
                first.getWidth() - first.getPaddingLeft() - first.getPaddingRight()
                        >= first.getPaint().measureText(first.getText().toString()));
        assertEquals(0, first.getLayout().getEllipsisCount(0));
        assertTrue(first.isFocusable());
        first.performClick();
        assertEquals(0, clicked[0]);
        // Reflow after a width change, retaining the selected item and callback.
        measure(detail, (int) (480 * density), (int) (540 * density));
        assertTrue(manager.getSpanCount() < 6);
        assertTrue(grid.findViewHolderForAdapterPosition(1).itemView.isSelected());
    }

    @Test public void narrowNormalFontFitsAndReflows() { checkLayout(640, 1f); }
    @Test public void narrowLargeFontFitsAndReflows() { checkLayout(640, 1.36f); }
    @Test public void widerTvKeepsReadableEpisodes() { checkLayout(1280, 1f); }
}
