package ru.andrey.guitartuner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

public class TunerView extends View {

    public interface Listener {
        void onPlayTone(float freq);
        void onTapWithoutPermission();
    }

    // Standard tuning, 6th string (low) to 1st (high)
    static final String[] NAMES = {"E", "A", "D", "G", "B", "E"};
    static final int[] OCTAVES = {2, 2, 3, 3, 3, 4};
    static final int[] NUMBERS = {6, 5, 4, 3, 2, 1};
    static final float[] FREQS = {82.41f, 110.00f, 146.83f, 196.00f, 246.94f, 329.63f};

    private static final int BG = 0xFF121417;
    private static final int PANEL = 0xFF1E2227;
    private static final int TEXT = 0xFFE8EAED;
    private static final int DIM = 0xFF8A9099;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFFF5252;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private Listener listener;
    private boolean hasPermission = false;
    private String message = null;

    // Pitch state
    private final float[] history = new float[5];
    private int historyCount = 0, historyPos = 0;
    private float freq = -1;
    private long lastPitchTime = 0;
    private int currentString = -1;
    private float targetCents = 0;
    private float shownCents = 0;
    private float level = 0;

    private int locked = -1;
    private final boolean[] tuned = new boolean[6];
    private long inTuneSince = 0;
    private int inTuneString = -1;

    // Touch
    private int downString = -1;
    private boolean longPressFired = false;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (downString >= 0 && listener != null) {
                longPressFired = true;
                listener.onPlayTone(FREQS[downString]);
                performHapticFeedback(0);
            }
        }
    };

    public TunerView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(BG);
        setKeepScreenOn(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setPermission(boolean granted) {
        hasPermission = granted;
        message = granted ? null : "Нужен доступ к микрофону.\nНажми на экран, чтобы разрешить.";
        invalidate();
    }

    public void setMessage(String m) { message = m; invalidate(); }

    /** Called on UI thread with each analysis frame. */
    public void onPitch(float f, float rms) {
        level = level * 0.7f + Math.min(1f, rms * 12f) * 0.3f;
        long now = SystemClock.uptimeMillis();
        if (f > 0) {
            history[historyPos] = f;
            historyPos = (historyPos + 1) % history.length;
            if (historyCount < history.length) historyCount++;
            float med = median();
            // Reject wild jumps until confirmed by the median
            freq = med;
            lastPitchTime = now;

            int s = locked >= 0 ? locked : nearestString(freq);
            currentString = s;
            targetCents = (float) (1200.0 * Math.log(freq / FREQS[s]) / Math.log(2));

            if (Math.abs(targetCents) < 4f) {
                if (inTuneString != s) { inTuneString = s; inTuneSince = now; }
                else if (now - inTuneSince > 700) tuned[s] = true;
            } else {
                inTuneString = -1;
                if (Math.abs(targetCents) > 15f && historyCount >= 3) tuned[s] = false;
            }
        } else if (now - lastPitchTime > 400) {
            historyCount = 0;
            inTuneString = -1;
        }
        postInvalidateOnAnimation();
    }

    private float median() {
        float[] t = new float[historyCount];
        System.arraycopy(history, 0, t, 0, historyCount);
        java.util.Arrays.sort(t);
        return t[historyCount / 2];
    }

    private static int nearestString(float f) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < FREQS.length; i++) {
            double d = Math.abs(Math.log(f / FREQS[i]));
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private float dp(float v) { return v * density; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        boolean active = freq > 0 && now - lastPitchTime < 1500;
        float fade = active ? 1f : 0.35f;

        // Smooth needle
        float goal = active ? Math.max(-50f, Math.min(50f, targetCents)) : 0f;
        shownCents += (goal - shownCents) * 0.25f;
        boolean animating = Math.abs(goal - shownCents) > 0.05f;

        float cx = w / 2f;
        float r = Math.min(w * 0.42f, h * 0.30f);
        float cy = h * 0.14f + r + dp(20);

        // Title
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(DIM);
        p.setTextSize(dp(14));
        c.drawText("Тюнер · строй E A D G B E", cx, h * 0.06f + dp(14), p);

        // Gauge arc background
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(10));
        p.setColor(PANEL);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, 210, 120, false, p);
        // In-tune zone (±5 cents)
        p.setColor(GREEN);
        p.setAlpha(110);
        c.drawArc(rect, 270 - 6, 12, false, p);
        p.setAlpha(255);

        // Ticks
        p.setStrokeCap(Paint.Cap.BUTT);
        for (int cents = -50; cents <= 50; cents += 5) {
            double a = Math.toRadians(270 + cents / 50.0 * 60);
            boolean major = cents % 10 == 0;
            float r1 = r - dp(major ? 26 : 20);
            float r2 = r - dp(12);
            p.setStrokeWidth(dp(major ? 2.5f : 1.5f));
            p.setColor(cents == 0 ? GREEN : DIM);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(13));
        p.setColor(DIM);
        for (int cents = -50; cents <= 50; cents += 50) {
            double a = Math.toRadians(270 + cents / 50.0 * 60);
            float rl = r - dp(44);
            String s = cents > 0 ? "+" + cents : String.valueOf(cents);
            c.drawText(s, cx + (float) Math.cos(a) * rl, cy + (float) Math.sin(a) * rl + dp(5), p);
        }

        // Needle color
        float absC = Math.abs(targetCents);
        int col = !active ? DIM : absC < 5 ? GREEN : absC < 15 ? AMBER : RED;

        double na = Math.toRadians(270 + shownCents / 50.0 * 60);
        float nl = r - dp(8);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(4));
        p.setColor(col);
        c.drawLine(cx, cy, cx + (float) Math.cos(na) * nl, cy + (float) Math.sin(na) * nl, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, dp(9), p);

        // Big note
        int s = currentString >= 0 ? currentString : (locked >= 0 ? locked : -1);
        if (locked >= 0) s = locked;
        float noteY = cy + r * 0.48f;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(r * 0.42f);
        p.setColor(s >= 0 ? (active ? col : TEXT) : DIM);
        p.setAlpha(s >= 0 ? (int) (255 * Math.max(fade, 0.6f)) : 255);
        String big = s >= 0 ? NAMES[s] : "—";
        c.drawText(big, cx, noteY, p);
        if (s >= 0) {
            float bw = p.measureText(big);
            p.setTextSize(r * 0.16f);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.valueOf(OCTAVES[s]), cx + bw / 2 + dp(2), noteY, p);
            p.setTextAlign(Paint.Align.CENTER);
        }
        p.setAlpha(255);
        p.setTypeface(Typeface.DEFAULT);

        // Hint + numbers
        float y = noteY + dp(34);
        p.setTextSize(dp(20));
        String hint;
        if (message != null) {
            hint = "";
        } else if (!active) {
            hint = locked >= 0 ? "Дёрни " + NUMBERS[locked] + "-ю струну" : "Дёрни любую струну";
            p.setColor(DIM);
        } else if (absC < 5) {
            hint = "✓ Точно";
            p.setColor(GREEN);
        } else if (targetCents < 0) {
            hint = absC > 50 ? "↑ Сильно ниже — подтяни" : "↑ Ниже — подтяни";
            p.setColor(col);
        } else {
            hint = absC > 50 ? "↓ Сильно выше — ослабь" : "↓ Выше — ослабь";
            p.setColor(col);
        }
        c.drawText(hint, cx, y, p);

        p.setTextSize(dp(14));
        p.setColor(DIM);
        if (s >= 0 && freq > 0) {
            String cents = (targetCents >= 0 ? "+" : "") + Math.round(targetCents) + " ¢";
            c.drawText(String.format(java.util.Locale.US, "%.1f Гц   %s   цель %.2f Гц",
                    freq, active ? cents : "", FREQS[s]), cx, y + dp(26), p);
        }

        // Input level bar
        float lw = w * 0.5f;
        float ly = y + dp(46);
        p.setColor(PANEL);
        rect.set(cx - lw / 2, ly, cx + lw / 2, ly + dp(4));
        c.drawRoundRect(rect, dp(2), dp(2), p);
        p.setColor(level > 0.05f ? GREEN : DIM);
        rect.set(cx - lw / 2, ly, cx - lw / 2 + lw * level, ly + dp(4));
        c.drawRoundRect(rect, dp(2), dp(2), p);

        // String buttons
        drawStrings(c, w, h);

        // Mode line
        p.setTextSize(dp(13));
        p.setColor(DIM);
        p.setTextAlign(Paint.Align.CENTER);
        String mode = locked >= 0
                ? "Струна " + NUMBERS[locked] + " зафиксирована · тап — авто"
                : "Тап — фикс. струну · удержание — звук";
        c.drawText(mode, cx, h - dp(24), p);

        if (message != null) {
            p.setColor(0xE6121417);
            c.drawRect(0, 0, w, h, p);
            p.setColor(TEXT);
            p.setTextSize(dp(18));
            String[] lines = message.split("\n");
            float my = h / 2f - (lines.length - 1) * dp(14);
            for (String line : lines) { c.drawText(line, cx, my, p); my += dp(28); }
        }

        if (animating) postInvalidateOnAnimation();
    }

    private float stringsY(int h) { return h - dp(110); }
    private float stringX(int i, int w) { return w * (i + 0.5f) / 6f; }
    private float stringR(int w) { return Math.min(w / 14f, dp(30)); }

    private void drawStrings(Canvas c, int w, int h) {
        float y = stringsY(h);
        float rr = stringR(w);
        for (int i = 0; i < 6; i++) {
            float x = stringX(i, w);
            boolean isLocked = locked == i;
            boolean isCurrent = currentString == i && freq > 0
                    && SystemClock.uptimeMillis() - lastPitchTime < 1500;
            p.setStyle(Paint.Style.FILL);
            p.setColor(isLocked ? 0xFF2B3A33 : PANEL);
            c.drawCircle(x, y, rr, p);
            if (isLocked || isCurrent) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.5f));
                p.setColor(isLocked ? GREEN : TEXT);
                c.drawCircle(x, y, rr, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(rr * 0.85f);
            p.setColor(TEXT);
            c.drawText(NAMES[i], x, y + rr * 0.3f, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(dp(12));
            p.setColor(DIM);
            c.drawText(String.valueOf(NUMBERS[i]), x, y - rr - dp(8), p);
            if (tuned[i]) {
                p.setColor(GREEN);
                p.setTextSize(dp(16));
                c.drawText("✓", x, y + rr + dp(20), p);
            }
        }
    }

    private int hitString(float x, float y) {
        int w = getWidth(), h = getHeight();
        float sy = stringsY(h);
        float rr = stringR(w) + dp(14);
        if (Math.abs(y - sy) > rr) return -1;
        int i = (int) (x / (w / 6f));
        return i >= 0 && i < 6 ? i : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hasPermission) {
                    if (listener != null) listener.onTapWithoutPermission();
                    return true;
                }
                downString = hitString(e.getX(), e.getY());
                longPressFired = false;
                if (downString >= 0) postDelayed(longPress, 450);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downString >= 0 && hitString(e.getX(), e.getY()) != downString) {
                    removeCallbacks(longPress);
                    downString = -1;
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (downString >= 0 && !longPressFired) {
                    locked = (locked == downString) ? -1 : downString;
                    if (freq > 0) {
                        int s = locked >= 0 ? locked : nearestString(freq);
                        currentString = s;
                        targetCents = (float) (1200.0 * Math.log(freq / FREQS[s]) / Math.log(2));
                    }
                    invalidate();
                }
                downString = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                downString = -1;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
