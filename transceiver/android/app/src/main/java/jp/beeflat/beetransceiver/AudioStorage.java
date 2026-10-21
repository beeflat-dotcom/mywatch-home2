package jp.beeflat.beetransceiver;



import android.content.ContentResolver;

import android.content.ContentValues;

import android.content.Context;

import android.net.Uri;

import android.os.Build;

import android.os.Environment;

import android.os.ParcelFileDescriptor;

import android.provider.MediaStore;

import java.io.File;

import java.text.SimpleDateFormat;

import java.util.Date;

import java.util.Locale;



final class AudioStorage {

    static Target create(Context context, String deviceName, String kind, int part) throws Exception {

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.JAPAN).format(new Date());

        String safe = safeDeviceName(deviceName).replaceAll("[^0-9A-Za-z\\u3040-\\u30ff\\u3400-\\u9fff_-]", "_");

        if (safe.length() > 32) safe = safe.substring(0, 32);

        String suffix = part > 0 ? String.format(Locale.US, "_%03d", part) : "";

        String fileName = stamp + "_" + safe + "_" + kind + suffix + ".m4a";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ContentResolver resolver = context.getContentResolver();

            ContentValues values = new ContentValues();

            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);

            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");

            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BeeTransceiver");

            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (uri == null) throw new IllegalStateException("ダウンロード保存先を作成できません");

            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "w");

            if (pfd == null) { resolver.delete(uri, null, null); throw new IllegalStateException("保存ファイルを開けません"); }

            return new Target(resolver, uri, pfd, null, fileName);

        }

        File base = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BeeTransceiver");

        if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("保存フォルダを作成できません");

        return new Target(null, null, null, new File(base, fileName), fileName);

    }



    static final class Target {

        final ContentResolver resolver; final Uri uri; final ParcelFileDescriptor pfd; final File file; final String fileName;

        boolean done;

        Target(ContentResolver resolver, Uri uri, ParcelFileDescriptor pfd, File file, String fileName) {

            this.resolver=resolver; this.uri=uri; this.pfd=pfd; this.file=file; this.fileName=fileName;

        }

        AacM4aWriter openWriter() throws Exception { return pfd != null ? new AacM4aWriter(pfd.getFileDescriptor()) : new AacM4aWriter(file); }

        long currentSize(long fallback) {

            try {

                if (pfd != null) { long n = pfd.getStatSize(); if (n >= 0) return n; }

                if (file != null) return file.length();

            } catch (Exception ignored) {}

            return fallback;

        }

        AudioUploader.Item finish() throws Exception {

            if (done) throw new IllegalStateException("already finished");

            done = true;

            if (pfd != null) pfd.close();

            if (uri != null) {

                ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);

                resolver.update(uri, values, null, null);

                return new AudioUploader.Item(fileName, uri.toString(), "");

            }

            return new AudioUploader.Item(fileName, "", file == null ? "" : file.getAbsolutePath());

        }

        void abort() {

            if (done) return; done=true;

            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}

            if (uri != null && resolver != null) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}

            if (file != null) try { file.delete(); } catch (Exception ignored) {}

        }

    }

    private static String safeDeviceName(String name) { return name == null || name.trim().isEmpty() ? "device" : name.trim(); }

    private AudioStorage() {}

}