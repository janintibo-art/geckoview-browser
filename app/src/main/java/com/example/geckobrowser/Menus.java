package com.example.geckobrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Menus de l'application : liste sombre a l'accent de la marque, avec
 * pictogramme, libelle, valeur courante et chevron pour les sous-menus.
 * Remplace les listes standard, qui juraient avec l'interface du navigateur.
 */
public class Menus {

    public static class Item {
        final String icon, title, value;
        final boolean submenu;
        final Runnable action;

        Item(String icon, String title, String value, boolean submenu, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.value = value;
            this.submenu = submenu;
            this.action = action;
        }
    }

    private final Activity activity;
    private final String title;
    private final List<Item> items = new ArrayList<>();
    private Runnable back;

    public Menus(Activity activity, String title) {
        this.activity = activity;
        this.title = title;
    }

    /** Entree simple. */
    public Menus add(String icon, String title, Runnable action) {
        items.add(new Item(icon, title, null, false, action));
        return this;
    }

    /** Entree affichant la valeur courante sous le libelle. */
    public Menus add(String icon, String title, String value, Runnable action) {
        items.add(new Item(icon, title, value, false, action));
        return this;
    }

    /** Entree ouvrant un sous-menu. */
    public Menus sub(String icon, String title, String value, Runnable action) {
        items.add(new Item(icon, title, value, true, action));
        return this;
    }

    /** Bouton de retour vers le menu parent. */
    public Menus back(Runnable r) {
        this.back = r;
        return this;
    }

    public void show() {
        final ArrayAdapter<Item> adapter =
            new ArrayAdapter<Item>(activity, R.layout.menu_row, items) {
                @Override
                public View getView(int pos, View convert, ViewGroup parent) {
                    View v = convert;
                    if (v == null) {
                        v = LayoutInflater.from(getContext())
                                .inflate(R.layout.menu_row, parent, false);
                    }
                    Item it = getItem(pos);
                    if (it == null) return v;

                    ((TextView) v.findViewById(R.id.m_icon)).setText(it.icon);
                    ((TextView) v.findViewById(R.id.m_title)).setText(it.title);

                    TextView val = v.findViewById(R.id.m_value);
                    if (it.value != null && !it.value.isEmpty()) {
                        val.setText(it.value);
                        val.setVisibility(View.VISIBLE);
                    } else {
                        val.setVisibility(View.GONE);
                    }

                    v.findViewById(R.id.m_chevron)
                     .setVisibility(it.submenu ? View.VISIBLE : View.GONE);
                    return v;
                }
            };

        AlertDialog.Builder b = new AlertDialog.Builder(activity, R.style.GeckoDialog)
            .setTitle(title)
            .setAdapter(adapter, (d, which) -> {
                Item it = items.get(which);
                if (it.action != null) it.action.run();
            });

        if (back != null) b.setNegativeButton("Retour", (d, w) -> back.run());
        else b.setNegativeButton("Fermer", null);

        b.show();
    }

    // -----------------------------------------------------------------------
    /** Boite de dialogue d'information, au meme style. */
    public static void info(Context ctx, String title, String message) {
        new AlertDialog.Builder(ctx, R.style.GeckoDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Compris", null)
            .show();
    }

    /** Liste a choix unique, au meme style. */
    public static AlertDialog.Builder choice(Context ctx, String title) {
        return new AlertDialog.Builder(ctx, R.style.GeckoDialog).setTitle(title);
    }
}
