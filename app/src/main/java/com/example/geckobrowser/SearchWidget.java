package com.example.geckobrowser;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Widget d'ecran d'accueil : barre de recherche, navigation privee, favoris.
 * Chaque zone ouvre l'application avec une action precise.
 */
public class SearchWidget extends AppWidgetProvider {

    public static final String ACTION = "com.example.geckobrowser.WIDGET";
    public static final String EXTRA = "action";

    static PendingIntent intentFor(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(ACTION + "." + action);   // distingue les intentions
        i.putExtra(EXTRA, action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_search);
            v.setOnClickPendingIntent(R.id.w_search, intentFor(ctx, "search", 1));
            v.setOnClickPendingIntent(R.id.w_private, intentFor(ctx, "private", 2));
            v.setOnClickPendingIntent(R.id.w_bookmarks, intentFor(ctx, "bookmarks", 3));
            mgr.updateAppWidget(id, v);
        }
    }

    /** Force le rafraichissement depuis l'application. */
    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, SearchWidget.class));
        if (ids != null && ids.length > 0) {
            new SearchWidget().onUpdate(ctx, mgr, ids);
        }
    }
}
