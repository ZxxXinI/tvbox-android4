package com.tvbox.android44.common;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/** 二维码生成（只生成，不申请相机权限）。 */
public final class QrCode {

    private QrCode() {
    }

    public static Bitmap encode(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.MARGIN, 4);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            int modules = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints).getWidth();
            if (sizePx < modules * 4) return null; // Too small to display reliably; show the manual URL.
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
            Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
