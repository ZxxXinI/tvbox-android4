package com.tvbox.android44.common;

import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.tvbox.android44.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28})
public class ChipTextColorTest {
    @Test public void categoriesAndEpisodesPreferReadableFocusColorEvenWhenSelected() {
        ContextThemeWrapper context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_TvBox);
        int light = context.getResources().getColor(R.color.text_primary);
        int dark = context.getResources().getColor(R.color.chip_selected_text);
        for (int layout : new int[]{R.layout.item_chip, R.layout.item_episode}) {
            TextView button = (TextView) LayoutInflater.from(context).inflate(layout, null);
            ColorStateList text = button.getTextColors();
            assertEquals(light, text.getDefaultColor());
            assertEquals(light, text.getColorForState(new int[]{android.R.attr.state_focused}, 0));
            assertEquals(light, text.getColorForState(
                    new int[]{android.R.attr.state_selected, android.R.attr.state_focused}, 0));
            assertEquals(dark, text.getColorForState(new int[]{android.R.attr.state_selected}, 0));
        }
    }
}
