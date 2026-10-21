package jp.beeflat.beetransceiver;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BeeLog {
    static synchronized void write(Context c,String event,String detail){
        try{
            File f=new File(c.getFilesDir(),"bee_transceiver_events.log");
            String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.JAPAN).format(new Date());
            String line=ts+"\t"+clean(event)+"\t"+clean(detail)+"\n";
            try(FileOutputStream out=new FileOutputStream(f,true)){out.write(line.getBytes(StandardCharsets.UTF_8));}
            if(f.length()>512*1024L){File old=new File(c.getFilesDir(),"bee_transceiver_events.old.log");if(old.exists())old.delete();f.renameTo(old);}
        }catch(Exception ignored){}
    }
    private static String clean(String s){return s==null?"":s.replace('\r',' ').replace('\n',' ').replace('\t',' ');}
    private BeeLog(){}
}
