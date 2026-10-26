package jp.beeflat.watchhomeptt;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class RecordingUploadJobService extends JobService {
    private volatile boolean cancelled;

    @Override public boolean onStartJob(JobParameters params) {
        cancelled = false;
        new Thread(() -> {
            boolean done = !cancelled && RecordingUploader.drain(getApplicationContext());
            jobFinished(params, !done);
        }, "WatchRecordingUpload").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return true;
    }
}
