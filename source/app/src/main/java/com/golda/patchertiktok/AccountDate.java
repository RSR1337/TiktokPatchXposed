package com.golda.patchertiktok;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

final class AccountDate {
    private static final String VIEW = "com.golda.patchertiktok.accountDate";
    private static final long FIRST = 1388534400L;

    private AccountDate() { }

    static long createdSeconds(String uid, long nowSeconds) {
        if (uid == null) return 0;
        long id;
        try {
            id = Long.parseLong(uid.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
        long seconds = id >>> 32;
        return seconds >= FIRST && seconds <= nowSeconds + 86400 ? seconds : 0;
    }

    static void show(TextView name, Object user) {
        Object uid = Reflect.call(user, "getUid");
        long created = Prefs.on(Prefs.ACCOUNT_DATE) && uid instanceof String
                ? createdSeconds((String) uid, System.currentTimeMillis() / 1000) : 0;
        View row = name;
        LinearLayout column = null;
        for (int depth = 0; depth < 5 && row.getParent() instanceof View; depth++) {
            View parent = (View) row.getParent();
            if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                column = (LinearLayout) parent;
                break;
            }
            row = parent;
        }
        if (column == null) return;
        TextView date = (TextView) Hooks.getExtra(column, VIEW);
        if (created == 0) {
            if (date != null) date.setVisibility(View.GONE);
            return;
        }
        if (date == null) {
            date = new TextView(name.getContext());
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            Hooks.setExtra(column, VIEW, date);
        }
        date.setTypeface(Typeface.create(name.getTypeface(), Typeface.NORMAL));
        date.setTextColor((name.getCurrentTextColor() & 0x00FFFFFF) | 0x99000000);
        date.setText(String.format(locale(name), I18n.get(I18n.S.ACCOUNT_CREATED), format(name, created)));
        date.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams params = params(date, name, column);
        int index = column.indexOfChild(row) + 1;
        if (date.getParent() == column && column.indexOfChild(date) == index) {
            ViewGroup.LayoutParams old = date.getLayoutParams();
            if (!(old instanceof LinearLayout.LayoutParams) || ((LinearLayout.LayoutParams) old).gravity != params.gravity
                    || ((LinearLayout.LayoutParams) old).getMarginStart() != params.getMarginStart()) {
                date.setLayoutParams(params);
            }
            return;
        }
        if (date.getParent() instanceof ViewGroup) ((ViewGroup) date.getParent()).removeView(date);
        column.addView(date, column.indexOfChild(row) + 1, params);
    }

    private static LinearLayout.LayoutParams params(TextView date, TextView name, LinearLayout column) {
        // Not MATCH_PARENT: TikTok's wrap-content name column would clip the date to the name's width.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(2 * name.getResources().getDisplayMetrics().density);
        params.gravity = Gravity.START;
        View screen = name.getRootView();
        if (name.getWidth() == 0 || column.getWidth() == 0 || screen.getWidth() == 0) return params;
        int[] at = new int[2];
        int[] box = new int[2];
        int[] whole = new int[2];
        name.getLocationOnScreen(at);
        column.getLocationOnScreen(box);
        screen.getLocationOnScreen(whole);
        float slack = 24 * name.getResources().getDisplayMetrics().density;
        boolean centered = Math.abs(at[0] + name.getWidth() / 2f - (whole[0] + screen.getWidth() / 2f)) < slack;
        boolean rtl = column.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int start = rtl ? box[0] + column.getWidth() - column.getPaddingRight() - at[0] - name.getWidth()
                : at[0] - box[0] - column.getPaddingLeft();
        params.gravity = centered ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        params.setMarginStart(centered ? 0 : Math.max(0, start));
        date.setGravity(centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        return params;
    }

    private static Locale locale(View view) {
        return view.getResources().getConfiguration().getLocales().get(0);
    }

    private static String format(View view, long seconds) {
        Locale locale = locale(view);
        String skeleton = DateFormat.is24HourFormat(view.getContext()) ? "dMMMMyyyyHHmmss" : "dMMMMyyyyhmmssa";
        return new java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                .format(new Date(seconds * 1000));
    }
}
