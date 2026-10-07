package com.tvbox.android44.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.tvbox.android44.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 28})
public class LegacyApiTest {
    public static class TestActivity extends BaseActivity { }

    @Test public void lifecycleTracksDestructionOnApi16() {
        ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class)
                .create().start().resume();
        assertFalse(controller.get().isActivityDestroyed());
        controller.pause().stop().destroy();
        assertTrue(controller.get().isActivityDestroyed());
    }

    @Test public void fontWrapperScalesInflatedViewsWithoutMutatingApplicationResources() {
        Context base = RuntimeEnvironment.getApplication();
        float before = base.getResources().getConfiguration().fontScale;
        Context scaled = FontScale.withScale(base, FontScale.SCALE_XLARGE);
        scaled.setTheme(R.style.Theme_TvBox);
        TextView label = (TextView) LayoutInflater.from(scaled).inflate(R.layout.item_episode, null);
        assertEquals(FontScale.SCALE_XLARGE, scaled.getResources().getConfiguration().fontScale, 0.001f);
        assertEquals(before, base.getResources().getConfiguration().fontScale, 0.001f);
        // TextView resolves its XML textSize through a rounded pixel dimension.
        assertEquals(Math.round(13 * scaled.getResources().getDisplayMetrics().density * FontScale.SCALE_XLARGE),
                label.getTextSize(), 0.1f);
    }

    @Test public void generatedQrRoundTripsUtf8ConfigurationUrl() throws Exception {
        String content = "http://192.168.1.2:9979/token?name=测试源";
        Bitmap bitmap = QrCode.encode(content, 320);
        assertNotNull(bitmap);
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        BinaryBitmap input = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(
                bitmap.getWidth(), bitmap.getHeight(), pixels)));
        assertEquals(content, new QRCodeReader().decode(input).getText());
    }

}
