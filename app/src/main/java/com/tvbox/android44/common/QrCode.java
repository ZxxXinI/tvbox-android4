package com.tvbox.android44.common;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/** 二维码生成（只生成，不申请相机权限）。 */
public final class QrCode {

    private QrCode() {
    }

    public static Bitmap encode(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(content,
                    BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
