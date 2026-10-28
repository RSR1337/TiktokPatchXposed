package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * How many settings of a group are on, as a small "5/13" chip. Its width does not grow with the
 * number of settings; with none on, the count is dimmed.
 */
@android.annotation.SuppressLint("ViewConstructor")
final class Meter extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int primary;
    private final int secondary;
    private int total;
    private int filled;

    Meter(Context context, int primary, int secondary, int background) {
        super(context);
        this.primary = primary;
        this.secondary = secondary;
        fill.setColor(background);
        float density = context.getResources().getDisplayMetrics().density;
        text.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.create(Fonts.semibold(context), Typeface.NORMAL));
        text.setFontFeatureSettings("tnum");
        setMinimumHeight(Math.round(22 * density));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(int filled, int total) {
        if (filled == this.filled && total == this.total) return;
        this.filled = filled;
        this.total = total;
        requestLayout();
        invalidate();
    }

    private String count() { return String.valueOf(filled); }

    private String rest() { return "/" + total; }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        // Sized for the widest count this total allows, so the chip does not jump as switches change.
        float width = text.measureText(String.valueOf(total) + "/" + total) + 16 * density;
        setMeasuredDimension(Math.round(width), Math.round(22 * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        box.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(box, 8 * density, 8 * density, fill);
        String count = count();
        String rest = rest();
        float width = text.measureText(count + rest);
        float x = (getWidth() - width) / 2f;
        float y = getHeight() / 2f - (text.descent() + text.ascent()) / 2f;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        // "5/13" reads left to right in every language.
        if (rtl) x = (getWidth() - width) / 2f;
        text.setColor(filled > 0 ? primary : secondary);
        canvas.drawText(count, x, y, text);
        text.setColor(secondary);
        canvas.drawText(rest, x + text.measureText(count), y, text);
    }
}
