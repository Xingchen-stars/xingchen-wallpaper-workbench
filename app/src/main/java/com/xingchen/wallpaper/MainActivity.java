package com.xingchen.wallpaper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android entry point. All images are processed locally; only user-initiated model download uses the network. */
public class MainActivity extends Activity {
    private static final int CHOOSE_IMAGES = 201;
    private static final int CHOOSE_MODEL = 202;
    private static final String MODEL = "isnet-anime.onnx";
    private static final String[] MODEL_URLS = {
            "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx"
    };
    private WebView web;
    private ValueCallback<Uri[]> chooserCallback;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final Map<String, PendingFile> pending = new HashMap<>();
    private final AnimeSegmenter segmenter = new AnimeSegmenter();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff101324);
        getWindow().setNavigationBarColor(0xff101324);
        web = new WebView(this);
        setContentView(web);
        web.setBackgroundColor(0xff0e111d);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Never allow navigation to untrusted pages that could access JS interfaces.
                return !"file".equals(request.getUrl().getScheme());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (chooserCallback != null) chooserCallback.onReceiveValue(null);
                chooserCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(intent, "选择图片"), CHOOSE_IMAGES);
                    return true;
                } catch (Exception e) {
                    chooserCallback = null;
                    callback.onReceiveValue(null);
                    show("无法打开文件选择器：" + e.getMessage());
                    return true;
                }
            }
        });
        web.addJavascriptInterface(new AiBridge(), "AndroidAI");
        web.addJavascriptInterface(new StorageBridge(), "AndroidStorage");
        web.loadUrl("file:///android_asset/index.html");
    }

    private File modelFile() {
        File dir = new File(getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MODEL);
    }
    private void uiJS(String expression) { runOnUiThread(() -> { if(web!=null) web.evaluateJavascript(expression, null); }); }
    private void sendModel(String message, boolean ready) {
        uiJS("window.onAndroidModelStatus(" + JSONObject.quote(message) + "," + ready + ");");
    }
    private void show(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CHOOSE_IMAGES) {
            if (chooserCallback != null) {
                chooserCallback.onReceiveValue(parseChosenImages(resultCode, data));
                chooserCallback = null;
            }
            return;
        }
        if (requestCode == CHOOSE_MODEL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            workers.execute(() -> {
                File tmp = new File(getFilesDir(), "import-model.part");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (is == null) throw new IllegalStateException("无法打开模型文件");
                    byte[] buf = new byte[131072]; long n=0; int count;
                    while ((count=is.read(buf))>=0) { out.write(buf,0,count); n+=count; }
                    if(n < 10_000_000L) throw new IllegalStateException("模型文件过小，请选择原始 ONNX 权重");
                    replaceFile(tmp,modelFile());
                    sendModel("模型导入成功（"+(n/1024/1024)+"MB），可离线 AI 抠图",true);
                } catch(Exception e) { tmp.delete(); sendModel("模型导入失败："+e.getMessage(),false); }
            });
        }
    }
    private Uri[] parseChosenImages(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        Set<Uri> selected = new LinkedHashSet<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) return null;
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (flags != 0) {
            for (Uri uri : selected) {
                try { getContentResolver().takePersistableUriPermission(uri, flags); }
                catch (SecurityException ignored) { }
            }
        }
        return selected.toArray(new Uri[0]);
    }
    private static void replaceFile(File tmp, File target) throws Exception {
        if(target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧模型");
        if(!tmp.renameTo(target)) throw new IllegalStateException("模型保存失败");
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        workers.shutdownNow();
        if (web!=null) { web.removeJavascriptInterface("AndroidAI"); web.removeJavascriptInterface("AndroidStorage"); web.destroy(); web=null; }
    }

    public class AiBridge {
        @JavascriptInterface public String modelStatus() { return modelFile().length()>10_000_000L?"ready":"missing"; }
        @JavascriptInterface public void importModel() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent,CHOOSE_MODEL);
            });
        }
        @JavascriptInterface public void downloadModel() {
            workers.execute(() -> {
                if (modelFile().length()>10_000_000L) { sendModel("模型已经下载，可直接离线使用",true); return; }
                File part = new File(getFilesDir(), "download-model.part");
                String lastFailure = "网络错误";
                for (String link : MODEL_URLS) {
                    HttpURLConnection conn = null;
                    try {
                        URL target = new URL(link);
                        conn = (HttpURLConnection) target.openConnection();
                        conn.setConnectTimeout(18000);
                        conn.setReadTimeout(50000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setRequestProperty("User-Agent","XingchenWallpaperWorkbench/3.0");
                        if(conn.getResponseCode()!=200) throw new IllegalStateException("HTTP "+conn.getResponseCode());
                        long size = conn.getContentLengthLong();
                        long received=0,lastNotified=0;
                        try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(part)) {
                            byte[] buffer=new byte[131072]; int n;
                            while ((n=in.read(buffer))!=-1) {
                                out.write(buffer,0,n); received+=n;
                                if(received-lastNotified>2_000_000) {
                                    lastNotified=received;
                                    int mb=(int)(received/1024/1024);
                                    String text="正在下载模型："+mb+" MB"+(size>0?" / "+size/1024/1024+" MB":"");
                                    sendModel(text,false);
                                }
                            }
                        }
                        if(received < 10_000_000L) throw new IllegalStateException("模型文件不完整（"+received+"字节）");
                        replaceFile(part,modelFile());
                        sendModel("模型下载完成："+received/1024/1024+" MB；现在可离线抠图",true);
                        return;
                    }catch(Exception e){lastFailure=e.getMessage();part.delete();}
                    finally{if(conn!=null)conn.disconnect();}
                }
                sendModel("下载失败："+lastFailure+"。可手动下载 ONNX 后点击「从手机导入」",false);
            });
        }
        @JavascriptInterface public void segment(String id, String dataUri) {
            workers.execute(() -> {
                try {
                    if(modelFile().length()<10_000_000L) throw new IllegalStateException("请先下载或导入模型");
                    String data=dataUri.contains(",")?dataUri.substring(dataUri.indexOf(',')+1):dataUri;
                    byte[] raw=Base64.decode(data,Base64.DEFAULT);
                    Bitmap source=BitmapFactory.decodeByteArray(raw,0,raw.length);
                    if(source==null)throw new IllegalStateException("无法解析图片");
                    Bitmap foreground=segmenter.segment(source,modelFile());
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    if(!foreground.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("无法输出透明PNG");
                    foreground.recycle();source.recycle();
                    String encoded=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                    // Stream chunks to JS to avoid sending a multi-MB string in one WebView message.
                    for(int i=0;i<encoded.length();i+=60000) {
                        String chunk=encoded.substring(i,Math.min(encoded.length(),i+60000));
                        uiJS("window.onAndroidAiChunk("+JSONObject.quote(id)+","+JSONObject.quote(chunk)+");");
                    }
                    uiJS("window.onAndroidAiResult("+JSONObject.quote(id)+",true,\"\");");
                }catch(Throwable e){
                    uiJS("window.onAndroidAiResult("+JSONObject.quote(id)+",false,"+JSONObject.quote(e.getMessage()==null?e.toString():e.getMessage())+");");
                }
            });
        }
    }

    private static class PendingFile {
        final File file; final FileOutputStream output; final String name,mime;
        PendingFile(File file, FileOutputStream stream,String name,String mime) {this.file=file;this.output=stream;this.name=name;this.mime=mime;}
    }
    public class StorageBridge {
        @JavascriptInterface public synchronized String startFile(String name,String mime) {
            try {
                String id=UUID.randomUUID().toString();
                File tmp=new File(getCacheDir(),id+".part");
                pending.put(id,new PendingFile(tmp,new FileOutputStream(tmp),sanitize(name),mime));
                return id;
            } catch(Exception e) {throw new IllegalStateException(e);}
        }
        @JavascriptInterface public synchronized void appendChunk(String id,String base64) {
            PendingFile f=pending.get(id);
            if(f==null)throw new IllegalStateException("不存在的导出任务");
            try { f.output.write(Base64.decode(base64,Base64.DEFAULT)); }
            catch(Exception e) {throw new IllegalStateException(e);}
        }
        @JavascriptInterface public synchronized void cancelFile(String id) {
            PendingFile f=pending.remove(id);
            if(f!=null){try{f.output.close();}catch(Exception ignored){}f.file.delete();}
        }
        @JavascriptInterface public synchronized void finishFile(String id) {
            PendingFile f=pending.remove(id);
            if(f==null)throw new IllegalStateException("不存在的导出任务");
            try {f.output.close();}
            catch(Exception e) {throw new IllegalStateException(e);}
            workers.execute(() -> saveMedia(f));
        }
    }
    private static String sanitize(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]","_").replace("..","_");
    }
    private void saveMedia(PendingFile file) {
        Uri target=null;
        try {
            boolean picture=file.mime.startsWith("image/");
            Uri collection=picture?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,file.name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,file.mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,(picture?Environment.DIRECTORY_PICTURES:Environment.DIRECTORY_DOWNLOADS)+"/星辰壁纸工作台");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            target=getContentResolver().insert(collection,values);
            if(target==null)throw new IllegalStateException("创建文件失败");
            try(InputStream in=new java.io.FileInputStream(file.file);OutputStream out=getContentResolver().openOutputStream(target)) {
                if(out==null)throw new IllegalStateException("无法写入下载目录");
                byte[] buf=new byte[131072];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            getContentResolver().update(target,values,null,null);
            show("已保存："+file.name+"（"+(picture?"相册/星辰壁纸工作台":"下载/星辰壁纸工作台")+"）");
        }catch(Exception e){
            if(target!=null)getContentResolver().delete(target,null,null);
            show("保存失败："+e.getMessage());
        }finally{file.file.delete();}
    }
}
