package com.xingchen.wallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Local ISNet-Anime inference. Mirrors original model's 0-1 RGB + letterbox preprocessing.
 * Original reference: https://github.com/SkyTNT/anime-segmentation/blob/main/inference.py
 */
final class AnimeSegmenter {
    private static final int S = 1024;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession session;

    synchronized Bitmap segment(Bitmap source, File model) throws Exception {
        if (!model.isFile() || model.length() < 10_000_000L) {
            throw new IllegalStateException("请先安装 isnet-anime.onnx 模型");
        }
        if (session == null) {
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            opts.setInterOpNumThreads(1);
            try { session = environment.createSession(model.getAbsolutePath(), opts); }
            finally { opts.close(); }
        }

        final int sourceW = source.getWidth(), sourceH = source.getHeight();
        final float ratio = Math.min(S / (float) sourceW, S / (float) sourceH);
        final int fitW = Math.max(1, Math.round(sourceW * ratio));
        final int fitH = Math.max(1, Math.round(sourceH * ratio));
        final int padX = (S - fitW) / 2, padY = (S - fitH) / 2;
        Bitmap canvasBitmap = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.BLACK);
        Paint quality = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(source, null, new Rect(padX, padY, padX + fitW, padY + fitH), quality);
        int[] pix = new int[S * S];
        canvasBitmap.getPixels(pix, 0, S, 0, 0, S, S);
        canvasBitmap.recycle();

        FloatBuffer data = ByteBuffer.allocateDirect(3 * S * S * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        // NCHW RGB float32, 0..1, no ImageNet mean/std normalization.
        for (int pos = 0; pos < pix.length; pos++) {
            int c = pix[pos];
            data.put(pos, (Color.red(c) & 0xff) / 255.0f);
            data.put(S*S + pos, (Color.green(c) & 0xff) / 255.0f);
            data.put(2*S*S + pos, (Color.blue(c) & 0xff) / 255.0f);
        }
        data.rewind();
        float[][][][] mask;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, data, new long[]{1, 3, S, S});
             OrtSession.Result out = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), input))) {
            Object result = out.get(0).getValue();
            if (!(result instanceof float[][][][])) throw new IllegalStateException("模型输出格式不是 [1,1,1024,1024]");
            mask = (float[][][][]) result;
        }
        float[][] alpha = mask[0][0];
        int[] argb = new int[S*S];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float a = Math.max(0f, Math.min(1f, alpha[y][x]));
                int byteAlpha = (int)(255f * a + 0.5f);
                argb[y*S+x] = (byteAlpha << 24) | 0x00ffffff;
            }
        }
        Bitmap allMask = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        allMask.setPixels(argb, 0, S, 0, 0, S, S);
        Bitmap cropMask = Bitmap.createBitmap(allMask, padX, padY, fitW, fitH);
        Bitmap foreground = Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888);
        Canvas output = new Canvas(foreground);
        output.drawBitmap(source, 0, 0, null);
        Paint matte = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        matte.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        output.drawBitmap(cropMask, null, new Rect(0, 0, sourceW, sourceH), matte);
        matte.setXfermode(null);
        cropMask.recycle();
        allMask.recycle();
        return foreground;
    }
}
