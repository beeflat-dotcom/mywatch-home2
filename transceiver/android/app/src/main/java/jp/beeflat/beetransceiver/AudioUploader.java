package jp.beeflat.beetransceiver;



import android.app.job.JobInfo;

import android.app.job.JobScheduler;

import android.content.ComponentName;

import android.content.ContentResolver;

import android.content.Context;

import android.content.SharedPreferences;

import android.net.ConnectivityManager;

import android.net.Network;

import android.net.NetworkCapabilities;

import android.net.Uri;

import android.util.Base64;

import org.json.JSONArray;

import org.json.JSONObject;

import java.io.BufferedInputStream;

import java.io.File;

import java.io.FileInputStream;

import java.io.InputStream;

import java.io.OutputStream;

import java.net.HttpURLConnection;

import java.net.URL;

import java.nio.charset.StandardCharsets;

import java.security.MessageDigest;

import java.util.ArrayList;

import java.util.List;

import java.util.UUID;



final class AudioUploader {

    private static final String PREF_QUEUE = "audio_upload_queue_v1";

    private static final int JOB_ID = 51616;



    static final class Item {

        final String fileName, uri, path, uploadId;

        Item(String fileName, String uri, String path) { this(fileName, uri, path, UUID.randomUUID().toString()); }

        Item(String fileName, String uri, String path, String uploadId) {

            this.fileName=fileName; this.uri=uri == null ? "" : uri; this.path=path == null ? "" : path; this.uploadId=uploadId;

        }

        JSONObject json() throws Exception { JSONObject o=new JSONObject(); o.put("file",fileName);o.put("uri",uri);o.put("path",path);o.put("id",uploadId);return o; }

        static Item from(JSONObject o) { return new Item(o.optString("file"),o.optString("uri"),o.optString("path"),o.optString("id",UUID.randomUUID().toString())); }

    }



    static synchronized void enqueue(Context context, Item item) {

        if (item == null || item.fileName.isEmpty()) return;

        List<Item> q = load(context);

        for (Item x:q) if (x.uploadId.equals(item.uploadId)) { schedule(context); return; }

        q.add(item); save(context,q); schedule(context);

    }

    static void schedule(Context context) {
        JobScheduler js=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js==null)return;
        try{
            JobInfo.Builder b=new JobInfo.Builder(JOB_ID,new ComponentName(context,AudioUploadJobService.class));
            b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            b.setPersisted(true);
            b.setBackoffCriteria(30000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            int result=js.schedule(b.build());
            if(result!=JobScheduler.RESULT_SUCCESS){
                BeeLog.write(context,"upload_schedule_failed","result="+result);
            }
        }catch(RuntimeException e){
            // Keep the main activity usable even when the device refuses background jobs.
            BeeLog.write(context,"upload_schedule_error",e.toString());
        }
    }

    static boolean drain(Context context) {

        List<Item> snapshot; synchronized(AudioUploader.class){ snapshot=load(context); }

        if(snapshot.isEmpty()) return true;

        String endpoint=resolveEndpoint(context); if(endpoint.isEmpty()) return false;

        boolean all=true;

        for(Item item:snapshot){

            try {

                if(uploadOne(context,endpoint,item)) { synchronized(AudioUploader.class){ remove(context,item.uploadId); } }

                else all=false;

            } catch(Exception e){ all=false; }

        }

        synchronized(AudioUploader.class){ return all && load(context).isEmpty(); }

    }

    private static String resolveEndpoint(Context context) {
        if(isWifi(context)){
            String lan=trimSlash(ServerDiscovery.discover());
            if(!lan.isEmpty()) return lan;
        }
        return trimSlash(EndpointDiscovery.resolve(context));
    }

    private static boolean isWifi(Context context) {

        try {

            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if(cm==null)return false; Network n=cm.getActiveNetwork(); if(n==null)return false;

            NetworkCapabilities c=cm.getNetworkCapabilities(n); return c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

        } catch(Exception e){return false;}

    }

    private static boolean uploadOne(Context context,String base,Item item)throws Exception{

        String sha=sha256(open(context,item)); long size=size(context,item); if(size<=0)return false;

        HttpURLConnection c=(HttpURLConnection)new URL(base+"/audio-upload").openConnection();

        c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setDoOutput(true);c.setRequestMethod("POST");

        c.setRequestProperty("Authorization","Bearer "+UploadConfig.token(context));

        c.setRequestProperty("Content-Type","application/octet-stream");

        c.setRequestProperty("X-Bee-Filename-B64",b64(item.fileName));

        c.setRequestProperty("X-Bee-Sha256",sha);c.setRequestProperty("X-Bee-Upload-Id",item.uploadId);

        c.setRequestProperty("X-Bee-Device-B64",b64(android.os.Build.MODEL));

        if(size<=Integer.MAX_VALUE)c.setFixedLengthStreamingMode((int)size); else c.setFixedLengthStreamingMode(size);

        try(InputStream in=new BufferedInputStream(open(context,item));OutputStream out=c.getOutputStream()){

            byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))>=0){if(n>0)out.write(buf,0,n);}out.flush();

        }

        int code=c.getResponseCode(); InputStream resp=code>=200&&code<300?c.getInputStream():c.getErrorStream();

        String text=readText(resp); c.disconnect(); if(code<200||code>=300)return false;

        JSONObject o=new JSONObject(text); if(!o.optBoolean("ok",false)||!sha.equalsIgnoreCase(o.optString("sha256")))return false;

        deleteLocal(context,item); return true;

    }

    private static InputStream open(Context c,Item i)throws Exception{ if(!i.uri.isEmpty())return c.getContentResolver().openInputStream(Uri.parse(i.uri)); return new FileInputStream(new File(i.path)); }

    private static long size(Context c,Item i)throws Exception{

        if(!i.path.isEmpty())return new File(i.path).length();

        try(android.os.ParcelFileDescriptor pfd=c.getContentResolver().openFileDescriptor(Uri.parse(i.uri),"r")){return pfd==null?0:pfd.getStatSize();}

    }

    private static void deleteLocal(Context c,Item i){ try{if(!i.uri.isEmpty())c.getContentResolver().delete(Uri.parse(i.uri),null,null);else if(!i.path.isEmpty())new File(i.path).delete();}catch(Exception ignored){} }

    private static String sha256(InputStream in)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream x=in){byte[]b=new byte[64*1024];int n;while((n=x.read(b))>=0)if(n>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b));return s.toString();}

    private static String b64(String s){return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}

    private static String readText(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in){byte[]b=new byte[4096];StringBuilder s=new StringBuilder();int n;while((n=x.read(b))>=0)if(n>0)s.append(new String(b,0,n,StandardCharsets.UTF_8));return s.toString();}}

    private static String trimSlash(String s){s=s==null?"":s.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}

    private static synchronized List<Item> load(Context c){List<Item>out=new ArrayList<>();try{SharedPreferences p=c.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE);JSONArray a=new JSONArray(p.getString(PREF_QUEUE,"[]"));for(int i=0;i<a.length();i++)out.add(Item.from(a.getJSONObject(i)));}catch(Exception ignored){}return out;}

    private static synchronized void save(Context c,List<Item>q){JSONArray a=new JSONArray();try{for(Item i:q)a.put(i.json());}catch(Exception ignored){}c.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE).edit().putString(PREF_QUEUE,a.toString()).apply();}

    private static synchronized void remove(Context c,String id){List<Item>q=load(c),n=new ArrayList<>();for(Item i:q)if(!i.uploadId.equals(id))n.add(i);save(c,n);}

    private AudioUploader(){}

}