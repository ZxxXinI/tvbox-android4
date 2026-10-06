package com.tvbox.android44.data.local;

import android.content.Context;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * JSON 文件读写：UTF-8 with BOM 写入；读取去 BOM；
 * 损坏时保留 .corrupt 备份并返回 null（不让应用崩溃）。
 */
public final class JsonIo {

    private static final Gson GSON = new Gson();
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private JsonIo() {
    }

    public static <T> T read(File file, Class<T> type) {
        if (!file.exists()) {
            return null;
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            byte[] data;
            try {
                long len = raf.length();
                if (len > 8 * 1024 * 1024) {
                    return null;
                }
                data = new byte[(int) len];
                raf.readFully(data);
            } finally {
                raf.close();
            }
            int offset = 0;
            if (data.length >= 3 && data[0] == BOM[0] && data[1] == BOM[1] && data[2] == BOM[2]) {
                offset = 3;
            }
            String json = new String(data, offset, data.length - offset, "UTF-8");
            if (json.indexOf('\uFFFD') >= 0) {
                throw new IOException("检测到 U+FFFD");
            }
            return GSON.fromJson(json, type);
        } catch (Exception e) {
            // 保留损坏备份
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt");
            file.renameTo(backup); // If backup fails, preserve the original for recovery.
            return null;
        }
    }

    public static void write(File file, Object value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (parent != null && !parent.isDirectory()) throw new IOException("无法创建存储目录");
        File tmp = File.createTempFile(file.getName(), ".tmp", parent);
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            OutputStreamWriter w = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
            try {
                fos.write(BOM);
                w.write(GSON.toJson(value));
                w.flush();
                fos.getFD().sync();
            } finally {
                w.close();
            }
            // Android/Linux rename replaces atomically; never delete the last good file first.
            if (!tmp.renameTo(file)) throw new IOException("无法落盘 " + file.getName());
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }
}
