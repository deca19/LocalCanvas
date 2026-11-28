package com.localcanvas.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Theme-aware UI component factory with WindowInsets and accessibility support. */
public final class UiKit {
    public final boolean dark;
    public final int bg, surface, button, text, muted, accent, good, warn;
    private final Context ctx;

    public UiKit(Context c, boolean darkTheme) {
        this.ctx = c;
        this.dark = darkTheme;
        if (darkTheme) {
            bg = 0xFF080E20; surface = 0xFF111A31; button = 0xFF1B2A4A;
            text = 0xFFF5F3FF; muted = 0xFFAAB4D0; accent = 0xFFB69CFF;
            good = 0xFF5EE0AD; warn = 0xFFFFB86C;
        } else {
            bg = 0xFFF3F5FC; surface = 0xFFFFFFFF; button = 0xFFE2E8F6;
            text = 0xFF161622; muted = 0xFF5E6075; accent = 0xFF6B4EFF;
            good = 0xFF0E9F6E; warn = 0xFFD97706;
        }
    }

    public int dp(int n) {
        return (int) (n * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public LinearLayout col() {
        LinearLayout x = new LinearLayout(ctx);
        x.setOrientation(LinearLayout.VERTICAL);
        return x;
    }

    public LinearLayout row() {
        LinearLayout x = new LinearLayout(ctx);
        x.setOrientation(LinearLayout.HORIZONTAL);
        return x;
    }

    public LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    public LinearLayout.LayoutParams lp(int w, int h, float f) { return new LinearLayout.LayoutParams(w, h, f); }

    public TextView text(String s, float size, int color) {
        TextView v = new TextView(ctx);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setPadding(dp(12), dp(7), dp(12), dp(7));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(true);
        return v;
    }

    public Button button(String label) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setContentDescription(label);
        b.setTextColor(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(button);
        gd.setCornerRadius(dp(10));
        b.setBackground(gd);
        return b;
    }

    public Button accentButton(String label) {
        Button b = button(label);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(accent);
        gd.setCornerRadius(dp(10));
        b.setBackground(gd);
        b.setTextColor(dark ? 0xFF0B0B12 : 0xFFFFFFFF);
        return b;
    }

    public EditText input(String hint, String value) {
        EditText x = new EditText(ctx);
        x.setHint(hint);
        x.setContentDescription(hint);
        x.setText(value);
        x.setTextColor(text);
        x.setHintTextColor(muted);
        x.setTextSize(14);
        x.setMinHeight(dp(48));
        x.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(dark ? 0xFF0C1530 : 0xFFF8FAFF);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), dark ? 0xFF2B3B61 : 0xFFD5DCEE);
        x.setBackground(gd);
        return x;
    }

    public void card(LinearLayout container, View v) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(surface);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), dark ? 0xFF243454 : 0xFFE1E6F2);
        v.setBackground(gd);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.setMargins(dp(10), dp(5), dp(10), dp(5));
        container.addView(v, p);
    }

    public void field(LinearLayout box, String name, View v) {
        box.addView(text(name, 12, muted));
        box.addView(v, lp(-1, -2));
    }

    public void applyEdgeToEdge(Activity act, View root) {
        act.getWindow().setStatusBarColor(bg);
        act.getWindow().setNavigationBarColor(bg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }
}
