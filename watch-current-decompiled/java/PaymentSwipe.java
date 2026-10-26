package jp.beeflat.watchhomeptt;

/* JADX INFO: loaded from: classes.dex */
final class PaymentSwipe {
    private static final long FLICK_MS = 650;
    private boolean candidate;
    private boolean directionChosen;
    private float distance;
    private float slop;
    private float startX;
    private float startY;
    private long startedAt;

    PaymentSwipe() {
    }

    void begin(float f, float f2, long j, boolean z, float f3, float f4) {
        cancel();
        this.startX = f;
        this.startY = f2;
        this.startedAt = j;
        this.slop = f3;
        this.distance = f4;
        this.candidate = z;
    }

    void move(float f, float f2) {
        if (this.candidate) {
            float f3 = f - this.startX;
            float f4 = f2 - this.startY;
            if (this.directionChosen || Math.hypot(f3, f4) <= this.slop) {
                return;
            }
            this.directionChosen = true;
            if (f4 <= Math.abs(f3) * 1.8f) {
                this.candidate = false;
            }
        }
    }

    /* JADX WARN: Code duplicated, block: B:13:0x0030  */
    boolean release(float f, float f2, long j) {
        boolean z;
        float f3 = f - this.startX;
        float f4 = f2 - this.startY;
        move(f, f2);
        if (this.candidate) {
            long j2 = this.startedAt;
            if (j <= j2 || j - j2 > FLICK_MS || f4 < this.distance || f4 <= Math.abs(f3) * 1.8f) {
                z = false;
            } else {
                z = true;
            }
        } else {
            z = false;
        }
        cancel();
        return z;
    }

    void cancel() {
        this.candidate = false;
        this.directionChosen = false;
    }
}
