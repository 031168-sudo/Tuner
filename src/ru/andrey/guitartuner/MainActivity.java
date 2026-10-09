package ru.andrey.guitartuner;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

public class MainActivity extends Activity implements TunerView.Listener {

    private static final int REQ_MIC = 1;
    private static final int SAMPLE_RATE = 44100;
    private static final int FRAME = 4096;
    private static final int HOP = 2048;

    private TunerView view;
    private volatile boolean running;
    private Thread audioThread;
    private AudioTrack tone;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) {
            win.setStatusBarColor(0xFF121417);
            win.setNavigationBarColor(0xFF121417);
        }
        view = new TunerView(this);
        view.setListener(this);
        setContentView(view);
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasMic()) {
            view.setPermission(true);
            startAudio();
        } else {
            view.setPermission(false);
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
        }
    }

    @Override
    protected void onPause() {
        stopAudio();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        view.setPermission(ok);
        if (ok) startAudio();
    }

    @Override
    public void onTapWithoutPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // Might be permanently denied: request anyway; if nothing happens, user must use settings
                view.setMessage("Нужен доступ к микрофону.\nЕсли окно не появилось —\nНастройки → Приложения →\nМой тюнер → Разрешения.");
            }
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private synchronized void startAudio() {
        if (running) return;
        running = true;
        audioThread = new Thread(new Runnable() {
            @Override public void run() { audioLoop(); }
        }, "tuner-audio");
        audioThread.start();
    }

    private synchronized void stopAudio() {
        running = false;
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) { }
            audioThread = null;
        }
    }

    private void audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, FRAME * 2 * 2);
        AudioRecord rec = null;
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                rec = new AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) break;
                rec.release();
                rec = null;
            } catch (Exception e) {
                rec = null;
            }
        }
        if (rec == null) {
            view.post(new Runnable() {
                @Override public void run() { view.setMessage("Не удалось открыть микрофон"); }
            });
            running = false;
            return;
        }

        PitchDetector det = new PitchDetector(SAMPLE_RATE, FRAME);
        short[] frame = new short[FRAME];
        short[] hop = new short[HOP];
        int filled = 0;
        try {
            rec.startRecording();
            while (running) {
                int n = rec.read(hop, 0, HOP);
                if (n <= 0) continue;
                // slide window
                if (filled + n > FRAME) {
                    int shift = filled + n - FRAME;
                    System.arraycopy(frame, shift, frame, 0, filled - shift);
                    filled -= shift;
                }
                System.arraycopy(hop, 0, frame, filled, n);
                filled += n;
                if (filled < FRAME) continue;

                final float f = det.detect(frame, FRAME);
                final float rms = det.lastRms;
                view.post(new Runnable() {
                    @Override public void run() { view.onPitch(f, rms); }
                });
            }
        } catch (Exception ignored) {
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        }
    }

    @Override
    public void onPlayTone(float freq) {
        if (tone != null) {
            try { tone.stop(); } catch (Exception ignored) { }
            tone.release();
            tone = null;
        }
        int sr = SAMPLE_RATE;
        int len = (int) (sr * 2.5);
        short[] data = new short[len];
        double[] harm = {1.0, 0.5, 0.3, 0.18, 0.1};
        double norm = 0;
        for (double a : harm) norm += a;
        for (int i = 0; i < len; i++) {
            double t = (double) i / sr;
            double env = Math.min(1.0, t / 0.01) * Math.exp(-t * 1.4);
            double v = 0;
            for (int k = 0; k < harm.length; k++) {
                v += harm[k] * Math.exp(-t * k * 0.8) * Math.sin(2 * Math.PI * freq * (k + 1) * t);
            }
            data[i] = (short) (v / norm * env * 0.9 * 32767);
        }
        tone = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, len * 2, AudioTrack.MODE_STATIC);
        tone.write(data, 0, len);
        tone.play();
    }

    @Override
    protected void onDestroy() {
        if (tone != null) tone.release();
        super.onDestroy();
    }
}
