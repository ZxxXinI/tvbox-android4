package com.tvbox.android44.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.tvbox.android44.R;
import com.tvbox.android44.common.ui.QrImageView;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28})
public class QrDisplayTest {
    @Test public void bothSessionUrlsRoundTripWithFourModuleQuietZones() throws Exception {
        for (int port : new int[]{9978, 9979}) for (int side : new int[]{200, 320, 480}) {
            String url = "http://192.168.123.123:" + port + "/abcdef0123456789ab";
            Bitmap bitmap = QrCode.encode(url, side); assertNotNull(bitmap);
            assertEquals(Bitmap.DENSITY_NONE, bitmap.getDensity());
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 4); hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            int modules = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 1, 1, hints).getWidth();
            int scale = side / modules;
            int firstBlackX = side, firstBlackY = side;
            int[] pixels = new int[side * side];
            bitmap.getPixels(pixels, 0, side, 0, 0, side, side);
            for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                int color = pixels[y * side + x];
                assertTrue(color == Color.BLACK || color == Color.WHITE);
                if (color == Color.BLACK) { firstBlackX = Math.min(x, firstBlackX); firstBlackY = Math.min(y, firstBlackY); }
            }
            assertTrue(firstBlackX >= 4 * scale); assertTrue(firstBlackY >= 4 * scale);
            assertEquals(url, new QRCodeReader().decode(new BinaryBitmap(
                    new HybridBinarizer(new RGBLuminanceSource(side, side, pixels)))).getText());
        }
    }

    @Test public void measuredViewKeepsPixelDimensionsAndNoFiltering() {
        Context context = RuntimeEnvironment.getApplication(); context.setTheme(R.style.Theme_TvBox);
        QrImageView image = new QrImageView(context, null);
        final boolean[] ready = {false};
        image.setCode("http://192.168.1.2:9978/abcdef0123456789ab", success -> ready[0] = success);
        image.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        image.layout(0, 0, 240, 240);
        assertTrue(ready[0]);
        BitmapDrawable drawable = (BitmapDrawable) image.getDrawable();
        assertEquals(240, drawable.getBitmap().getWidth());
        assertEquals(240, drawable.getIntrinsicWidth());
        assertFalse(drawable.getPaint().isFilterBitmap());
        assertTrue(image.isFocusable());
    }

    @Test public void crampedLargeFontDialogHasScrollableCloseAndManualFallback() {
        Context context = FontScale.withScale(RuntimeEnvironment.getApplication(), 1.36f);
        context.setTheme(R.style.Theme_TvBox);
        ScrollView dialog = (ScrollView) LayoutInflater.from(context).inflate(R.layout.dialog_qr_config, null);
        View close = dialog.findViewById(R.id.qr_close);
        dialog.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY));
        dialog.layout(0, 0, 400, 260);
        assertTrue(close.isFocusable()); assertTrue(close.requestFocus());
        assertNotNull(dialog.findViewById(R.id.qr_url));
        assertTrue(dialog.getChildAt(0).getHeight() >= dialog.getHeight());
        assertNull(QrCode.encode("http://192.168.123.123:9979/abcdef0123456789ab", 64));
    }
}
