package com.example.geckobrowser;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * Widget compact : nombre d'elements bloques et etat du blocage.
 * Un appui ouvre l'application en basculant l'interrupteur.
 */
public class StatsWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        SharedPreferences p = ctx.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE);
        int count = p.getInt("blockedCount", 0);
        boolean on = p.getBoolean("blockerEnabled", true);

        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_stats);

            v.setTextViewText(R.id.w_count, count > 9999 ? "9999+" : String.valueOf(count));
            v.setTextColor(R.id.w_count, on ? 0xFF6FAE5F : 0xFF8A9099);
            v.setImageViewResource(R.id.w_dot,
                    on ? R.drawable.widget_dot_on : R.drawable.widget_dot_off);
            v.setTextViewText(R.id.w_state,
                    ctx.getString(on ? R.string.widget_on : R.string.widget_off));

            v.setOnClickPendingIntent(R.id.w_root,
                    SearchWidget.intentFor(ctx, "toggle", 4));

            mgr.updateAppWidget(id, v);
        }
    }

    /** Met a jour le compteur et l'etat depuis l'application. */
    static void publish(Context ctx, int count, boolean enabled) {
        ctx.getSharedPreferences("geckobrowser", Context.MODE_PRIVATE)
           .edit()
           .putInt("blockedCount", count)
           .putBoolean("blockerEnabled", enabled)
           .apply();

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, StatsWidget.class));
        if (ids != null && ids.length > 0) {
            new StatsWidget().onUpdate(ctx, mgr, ids);
        }
    }
}
