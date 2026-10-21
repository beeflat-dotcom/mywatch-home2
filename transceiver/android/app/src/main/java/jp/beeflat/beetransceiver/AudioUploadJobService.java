package jp.beeflat.beetransceiver;



import android.app.job.JobParameters;

import android.app.job.JobService;



public class AudioUploadJobService extends JobService {

    private volatile boolean cancelled;

    @Override public boolean onStartJob(JobParameters params) {

        cancelled=false;

        new Thread(() -> {

            boolean done=!cancelled && AudioUploader.drain(getApplicationContext());

            jobFinished(params,!done);

        },"BeeAudioUpload").start();

        return true;

    }

    @Override public boolean onStopJob(JobParameters params) { cancelled=true; return true; }

}