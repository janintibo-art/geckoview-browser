#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute groupes, apercus et mise en veille aux onglets GeckoBrowser."""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
BACKUP = MAIN.with_name("MainActivity.java.before-tab-workspace")
MARKER = "TAB_WORKSPACE_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"ancre {label!r} trouvee {count} fois au lieu d'une")
    return text.replace(old, new, 1)


if not MAIN.is_file():
    fail(f"fichier introuvable: {MAIN}")

src = MAIN.read_text(encoding="utf-8")
if MARKER in src:
    print("Espace de travail des onglets deja installe.")
    sys.exit(0)

for required in ("SessionStore", "PrivacyCockpit", "SmartSelectionDelegate"):
    if required not in src:
        fail(f"la version attendue de MainActivity.java manque {required}")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

# ---------------------------------------------------------------------------
# Metadonnees propres a chaque onglet.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''        /** Langue detectee par Gecko, pour la traduction de page. */
        String langTag;
    }''',
    '''        /** Langue detectee par Gecko, pour la traduction de page. */
        String langTag;

        // TAB_WORKSPACE_V1 — organisation, apercu et economie de memoire.
        String id = java.util.UUID.randomUUID().toString();
        String group = "";
        boolean pinned = false;
        boolean sleeping = false;
        long lastUsed = System.currentTimeMillis();
    }''',
    "champs Tab",
)

src = replace_once(
    src,
    '''        final Tab tab = new Tab();
        tab.priv = priv;
        tab.state = restoredState;''',
    '''        final Tab tab = new Tab();
        tab.priv = priv;
        tab.state = restoredState;
        tab.lastUsed = System.currentTimeMillis();''',
    "initialisation Tab",
)

# Un apercu frais est pris apres le chargement de la page active.
src = replace_once(
    src,
    '''                if (isRealPage(tab.url)) {
                    splash.postDelayed(MainActivity.this::hideSplash, 300);
                }
            }''',
    '''                if (isRealPage(tab.url)) {
                    splash.postDelayed(MainActivity.this::hideSplash, 300);
                }
                if (success && !tab.priv && s == session) {
                    geckoView.postDelayed(() -> captureTabPreview(tab), 380);
                }
            }''',
    "capture apres chargement",
)

# Suivi de l'activite et reveil d'une session mise en veille.
src = replace_once(
    src,
    '''        hideFindBar();
        active = index;
        Tab t = tabs.get(index);
        session = t.session;''',
    '''        hideFindBar();
        long now = System.currentTimeMillis();
        if (active >= 0 && active < tabs.size() && active != index) {
            tabs.get(active).lastUsed = now;
        }
        active = index;
        Tab t = tabs.get(index);
        if (t.sleeping) {
            if ((t.pendingState == null || t.pendingState.isEmpty())
                    && t.state != null && !t.state.isEmpty()) {
                t.pendingState = t.state;
            }
            if (t.pending == null || t.pending.isEmpty()) t.pending = t.url;
            t.sleeping = false;
        }
        t.lastUsed = now;
        session = t.session;''',
    "reveil onglet",
)

src = replace_once(
    src,
    '''        updateTabButton();
        scheduleSessionSave();
    }

    /** Retient ce qui vient d'etre ferme''',
    '''        updateTabButton();
        scheduleSessionSave();
        autoSleepInactiveTabs();
    }

    /** Retient ce qui vient d'etre ferme''',
    "veille automatique apres selection",
)

# Nettoyage de l'apercu lorsque le dernier onglet est ramene a l'accueil.
src = replace_once(
    src,
    '''        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            session.loadUri(homeUrl());
            return;
        }

        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);''',
    '''        if (tabs.size() == 1) {
            TabPreviewStore.delete(this, t.id);
            t.id = java.util.UUID.randomUUID().toString();
            t.url = "";
            t.title = "";
            t.group = "";
            t.pinned = false;
            t.sleeping = false;
            t.lastUsed = System.currentTimeMillis();
            session.loadUri(homeUrl());
            scheduleSessionSave();
            return;
        }

        TabPreviewStore.delete(this, t.id);
        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);''',
    "fermeture onglet",
)

# Liste d'onglets enrichie.
old_show_tabs = '''    private void showTabs() {
        Menus m = new Menus(this, tabs.size() + " onglet(s)");
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab t = tabs.get(i);
            String mark = (i == active ? "\\u25CF" : (t.priv ? "\\u25D1" : "\\u25CB"));
            String host = t.url.isEmpty() ? "vide" : t.url;
            if (host.length() > 46) host = host.substring(0, 46) + "…";
            m.add(mark, tabLabel(t), host, () -> selectTab(index));
        }
        m.add("\\u002B", "Nouvel onglet", () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\\u25D1", "Nouvel onglet prive", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\\u2327", "Fermer l'onglet courant", tabLabel(tabs.get(active)),
              () -> closeTab(active));
        if (tabs.size() > 1) {
            m.add("\\u2327", "Fermer tous les autres", this::closeOthers);
        }
        m.back(this::showMenu).show();
    }'''

new_show_tabs = '''    private void showTabs() {
        autoSleepInactiveTabs();
        if (active >= 0 && active < tabs.size()) captureTabPreview(tabs.get(active));

        Menus m = new Menus(this, tabs.size() + " onglet(s)");
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab t = tabs.get(i);
            String mark = i == active ? "\\u25CF"
                    : (t.pinned ? "\\u2605" : (t.sleeping ? "\\u25CC"
                    : (t.priv ? "\\u25D1" : "\\u25CB")));
            m.add(mark, tabLabel(t), tabMeta(t), () -> showTabPreview(index));
        }
        m.add("\\u002B", "Nouvel onglet", () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\\u25D1", "Nouvel onglet prive", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });
        m.sub("\\u25A6", "Groupes d'onglets", groupSummary(), this::showTabGroups);
        m.sub("\\u23F8", "Mise en veille", sleepSettingName(), this::showSleepMenu);
        m.add("\\u263E", "Mettre les onglets inactifs en veille",
              "les onglets epingles restent ouverts", this::sleepAllInactiveTabs);
        m.add("\\u2327", "Fermer l'onglet courant", tabLabel(tabs.get(active)),
              () -> closeTab(active));
        if (tabs.size() > 1) {
            m.add("\\u2327", "Fermer tous les autres",
                  "les onglets epingles sont conserves", this::closeOthers);
        }
        m.back(this::showMenu).show();
    }'''
src = replace_once(src, old_show_tabs, new_show_tabs, "showTabs")

old_close_others = '''    private void closeOthers() {
        Tab keep = tabs.get(active);
        for (Tab t : tabs) {
            if (t != keep) {
                try { t.session.close(); } catch (Exception ignored) { }
            }
        }
        tabs.clear();
        tabs.add(keep);
        selectTab(0);
        scheduleSessionSave();
    }'''

new_close_others = '''    private void closeOthers() {
        Tab keep = tabs.get(active);
        java.util.List<Tab> kept = new java.util.ArrayList<>();
        for (Tab t : tabs) {
            if (t == keep || t.pinned) {
                kept.add(t);
            } else {
                TabPreviewStore.delete(this, t.id);
                try { t.session.close(); } catch (Exception ignored) { }
            }
        }
        tabs.clear();
        tabs.addAll(kept);
        selectTab(tabs.indexOf(keep));
        scheduleSessionSave();
    }'''
src = replace_once(src, old_close_others, new_close_others, "closeOthers")

workspace_methods = r'''

    // -----------------------------------------------------------------------
    //  Espace de travail des onglets : groupes, apercus, epinglage et veille.
    // -----------------------------------------------------------------------
    private int tabDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hostForTab(Tab t) {
        if (t == null || t.url == null || t.url.isEmpty()) return "vide";
        if (t.url.startsWith("moz-extension://")) return "accueil";
        try {
            String host = Uri.parse(t.url).getHost();
            if (host != null && !host.isEmpty()) return host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) { }
        String text = t.url;
        return text.length() > 42 ? text.substring(0, 42) + "…" : text;
    }

    private String groupName(Tab t) {
        return t == null || t.group == null || t.group.trim().isEmpty()
                ? "Sans groupe" : t.group.trim();
    }

    private String tabMeta(Tab t) {
        java.util.List<String> bits = new java.util.ArrayList<>();
        if (t.pinned) bits.add("epingle");
        if (t.sleeping) bits.add("en veille");
        if (t.priv) bits.add("prive");
        if (!"Sans groupe".equals(groupName(t))) bits.add(groupName(t));
        bits.add(hostForTab(t));
        bits.add(lastUsedText(t.lastUsed));
        return android.text.TextUtils.join(" · ", bits);
    }

    private String lastUsedText(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000L);
        if (minutes < 1) return "maintenant";
        if (minutes < 60) return "il y a " + minutes + " min";
        long hours = minutes / 60;
        if (hours < 24) return "il y a " + hours + " h";
        return "il y a " + (hours / 24) + " j";
    }

    private void captureTabPreview(Tab tab) {
        if (tab == null || tab.priv || tab.sleeping || tab.session == null
                || tab.session != session || !tab.session.isOpen()) return;
        try {
            geckoView.capturePixels().accept(
                bitmap -> {
                    if (bitmap != null) TabPreviewStore.save(this, tab.id, bitmap);
                },
                error -> { }
            );
        } catch (Throwable ignored) { }
    }

    private void showTabPreview(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        if (index == active && !tab.priv && !tab.sleeping
                && tab.session != null && tab.session.isOpen()) {
            try {
                geckoView.capturePixels().accept(
                    bitmap -> runOnUiThread(() -> {
                        if (bitmap != null) TabPreviewStore.save(this, tab.id, bitmap);
                        showTabPreviewDialog(index);
                    }),
                    error -> runOnUiThread(() -> showTabPreviewDialog(index))
                );
                return;
            } catch (Throwable ignored) { }
        }
        showTabPreviewDialog(index);
    }

    private void showTabPreviewDialog(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = tabDp(14);
        box.setPadding(pad, pad, pad, tabDp(4));

        android.graphics.Bitmap bitmap = tab.priv ? null : TabPreviewStore.load(this, tab.id);
        if (bitmap != null) {
            android.widget.ImageView image = new android.widget.ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bitmap);
            android.widget.LinearLayout.LayoutParams imageParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            tabDp(210));
            imageParams.bottomMargin = tabDp(12);
            box.addView(image, imageParams);
        } else {
            TextView empty = new TextView(this);
            empty.setText(tab.priv
                    ? "Aucun apercu n'est enregistre en navigation privee."
                    : (tab.sleeping ? "Onglet en veille · apercu indisponible"
                                    : "L'apercu sera cree apres le prochain affichage."));
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(tabDp(8), tabDp(28), tabDp(8), tabDp(28));
            box.addView(empty, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        TextView details = new TextView(this);
        details.setText(tabMeta(tab) + "\n" + (tab.url == null ? "" : tab.url));
        details.setTextIsSelectable(true);
        details.setTextSize(12f);
        box.addView(details);

        Menus.dialog(this)
            .setTitle(tabLabel(tab))
            .setView(box)
            .setPositiveButton(index == active ? "Afficher" : "Ouvrir",
                    (d, w) -> selectTab(index))
            .setNeutralButton("Actions", (d, w) -> showTabActions(index))
            .setNegativeButton("Retour", (d, w) -> showTabs())
            .show();
    }

    private void showTabActions(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        Menus m = new Menus(this, tabLabel(tab));
        m.add("\u25B6", "Ouvrir", tabMeta(tab), () -> selectTab(index));
        m.add("\u25A3", "Apercu visuel", () -> showTabPreview(index));
        m.add("\u25A6", "Deplacer dans un groupe", groupName(tab),
              () -> moveTabToGroup(index));
        m.add(tab.pinned ? "\u2606" : "\u2605",
              tab.pinned ? "Desepingler" : "Epingler",
              tab.pinned ? "autoriser la mise en veille automatique"
                         : "garder cet onglet actif et le proteger des fermetures groupées",
              () -> togglePinned(index));
        if (tab.sleeping) {
            m.add("\u263C", "Reveiller", () -> selectTab(index));
        } else {
            m.add("\u263E", "Mettre en veille",
                  "libere le moteur tout en gardant l'historique", () -> sleepTab(index, false));
        }
        m.add("\u2327", "Fermer cet onglet", () -> closeTab(index));
        m.back(this::showTabs).show();
    }

    private void togglePinned(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        tab.pinned = !tab.pinned;
        if (tab.pinned && tab.sleeping) selectTab(index);
        scheduleSessionSave();
        Toast.makeText(this, tab.pinned ? "Onglet epingle" : "Onglet desepingle",
                Toast.LENGTH_SHORT).show();
        showTabActions(index);
    }

    private java.util.List<String> knownGroups() {
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
        groups.add("Personnel");
        groups.add("Travail");
        groups.add("Lecture");
        groups.add("Projet");
        try {
            JSONArray saved = new JSONArray(prefs.getString("tabGroups", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i, "").trim();
                if (!value.isEmpty()) groups.add(value);
            }
        } catch (Throwable ignored) { }
        for (Tab tab : tabs) {
            String value = tab.group == null ? "" : tab.group.trim();
            if (!value.isEmpty()) groups.add(value);
        }
        return new java.util.ArrayList<>(groups);
    }

    private void rememberGroup(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return;
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>(knownGroups());
        groups.add(clean);
        JSONArray out = new JSONArray();
        for (String group : groups) out.put(group);
        prefs.edit().putString("tabGroups", out.toString()).apply();
    }

    private void moveTabToGroup(int index) {
        if (index < 0 || index >= tabs.size()) return;
        java.util.List<String> groups = knownGroups();
        String[] labels = new String[groups.size() + 2];
        labels[0] = "Sans groupe";
        for (int i = 0; i < groups.size(); i++) labels[i + 1] = groups.get(i);
        labels[labels.length - 1] = "Nouveau groupe…";

        Menus.dialog(this)
            .setTitle("Deplacer l'onglet")
            .setItems(labels, (d, which) -> {
                if (which == 0) {
                    tabs.get(index).group = "";
                    scheduleSessionSave();
                    showTabActions(index);
                } else if (which == labels.length - 1) {
                    createGroupForTab(index);
                } else {
                    tabs.get(index).group = groups.get(which - 1);
                    rememberGroup(tabs.get(index).group);
                    scheduleSessionSave();
                    showTabActions(index);
                }
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void createGroupForTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nom du groupe");
        Menus.dialog(this)
            .setTitle("Nouveau groupe")
            .setView(input)
            .setPositiveButton("Creer", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                tabs.get(index).group = name;
                rememberGroup(name);
                scheduleSessionSave();
                showTabActions(index);
            })
            .setNegativeButton("Annuler", null)
            .show();
    }

    private int groupCount(String group) {
        int count = 0;
        for (Tab tab : tabs) {
            String value = tab.group == null ? "" : tab.group.trim();
            if (group == null || group.isEmpty()) {
                if (value.isEmpty()) count++;
            } else if (group.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private String groupSummary() {
        java.util.LinkedHashSet<String> used = new java.util.LinkedHashSet<>();
        for (Tab tab : tabs) {
            String group = tab.group == null ? "" : tab.group.trim();
            if (!group.isEmpty()) used.add(group);
        }
        return used.isEmpty() ? "aucun groupe" : used.size() + " groupe(s) utilise(s)";
    }

    private void showTabGroups() {
        Menus m = new Menus(this, "Groupes d'onglets");
        int loose = groupCount("");
        if (loose > 0) {
            m.add("\u25CB", "Sans groupe", loose + " onglet(s)",
                  () -> showGroupTabs(""));
        }
        for (String group : knownGroups()) {
            int count = groupCount(group);
            if (count <= 0) continue;
            final String selected = group;
            m.add("\u25A6", group, count + " onglet(s)",
                  () -> showGroupTabs(selected));
        }
        m.add("\u002B", "Nouveau groupe pour l'onglet courant",
              () -> createGroupForTab(active));
        m.back(this::showTabs).show();
    }

    private void showGroupTabs(String group) {
        String title = group == null || group.isEmpty() ? "Sans groupe" : group;
        Menus m = new Menus(this, title);
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            String value = tab.group == null ? "" : tab.group.trim();
            boolean match = group == null || group.isEmpty() ? value.isEmpty() : group.equals(value);
            if (!match) continue;
            final int index = i;
            String mark = i == active ? "\u25CF"
                    : (tab.pinned ? "\u2605" : (tab.sleeping ? "\u25CC" : "\u25CB"));
            m.add(mark, tabLabel(tab), tabMeta(tab), () -> showTabPreview(index));
        }
        m.back(this::showTabGroups).show();
    }

    private void sleepTab(int index, boolean quiet) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        if (index == active) {
            if (!quiet) Toast.makeText(this,
                    "Ouvrez un autre onglet avant de mettre celui-ci en veille",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (tab.pinned || tab.priv || tab.sleeping) return;

        try { if (tab.session.isOpen()) tab.session.flushSessionState(); }
        catch (Throwable ignored) { }
        if (tab.state != null && !tab.state.isEmpty()) tab.pendingState = tab.state;
        tab.pending = tab.url;
        try { if (tab.session.isOpen()) tab.session.close(); }
        catch (Throwable ignored) { }
        tab.sleeping = true;
        scheduleSessionSave();
        if (!quiet) {
            Toast.makeText(this, "Onglet mis en veille", Toast.LENGTH_SHORT).show();
            showTabs();
        }
    }

    private void sleepAllInactiveTabs() {
        int slept = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (i == active || tab.pinned || tab.priv || tab.sleeping) continue;
            sleepTab(i, true);
            slept++;
        }
        Toast.makeText(this, slept + " onglet(s) mis en veille", Toast.LENGTH_SHORT).show();
        showTabs();
    }

    private int sleepMinutes() {
        return prefs.getInt("tabSleepMinutes", 30);
    }

    private String sleepSettingName() {
        int minutes = sleepMinutes();
        if (minutes <= 0) return "automatique desactivee";
        if (minutes < 60) return "apres " + minutes + " min";
        return "apres " + (minutes / 60) + " h";
    }

    private void autoSleepInactiveTabs() {
        int minutes = sleepMinutes();
        if (minutes <= 0 || tabs.size() < 2) return;
        long threshold = System.currentTimeMillis() - minutes * 60000L;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (i == active || tab.pinned || tab.priv || tab.sleeping) continue;
            if (tab.lastUsed > 0 && tab.lastUsed < threshold) sleepTab(i, true);
        }
    }

    private void showSleepMenu() {
        Menus m = new Menus(this, "Mise en veille");
        m.add("\u263E", "Mettre les onglets inactifs en veille",
              "action immediate", this::sleepAllInactiveTabs);
        m.add("\u23F1", "Delai automatique", sleepSettingName(), this::showSleepPicker);
        m.add("\u24D8", "Fonctionnement",
              "l'historique est conserve, le moteur est ferme",
              () -> Menus.dialog(this)
                    .setTitle("Mise en veille des onglets")
                    .setMessage("Un onglet en veille libere sa session Gecko et reduit la memoire "
                            + "utilisee. Son adresse, son historique, son defilement, son zoom et "
                            + "ses formulaires restent enregistres. Il se reveille automatiquement "
                            + "lorsque vous l'ouvrez. Les onglets prives et epingles ne sont jamais "
                            + "mis en veille automatiquement.")
                    .setPositiveButton("Compris", null)
                    .show());
        m.back(this::showTabs).show();
    }

    private void showSleepPicker() {
        final int[] values = { 0, 5, 15, 30, 60, 120 };
        final String[] labels = {
            "Desactivee", "Apres 5 minutes", "Apres 15 minutes",
            "Apres 30 minutes", "Apres 1 heure", "Apres 2 heures"
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == sleepMinutes()) checked = i;
        final int initial = checked;
        Menus.dialog(this)
            .setTitle("Veille automatique")
            .setSingleChoiceItems(labels, initial, (d, which) -> {
                prefs.edit().putInt("tabSleepMinutes", values[which]).apply();
                d.dismiss();
                autoSleepInactiveTabs();
                showSleepMenu();
            })
            .setNegativeButton("Annuler", null)
            .show();
    }
'''

src = replace_once(
    src,
    '''    // -----------------------------------------------------------------------
    //  Restauration complete de session
    // -----------------------------------------------------------------------''',
    workspace_methods + '''

    // -----------------------------------------------------------------------
    //  Restauration complete de session
    // -----------------------------------------------------------------------''',
    "bloc espace de travail",
)

# Metadonnees persistantes.
src = replace_once(
    src,
    '''                o.put("url", t.url);
                o.put("title", t.title);
                if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);''',
    '''                o.put("url", t.url);
                o.put("title", t.title);
                o.put("id", t.id);
                if (t.group != null && !t.group.isEmpty()) o.put("group", t.group);
                o.put("pinned", t.pinned);
                o.put("sleeping", t.sleeping);
                o.put("lastUsed", t.lastUsed);
                if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);''',
    "sauvegarde metadonnees",
)

src = replace_once(
    src,
    '''            if (SessionStore.write(this, arr, savedActive)) {
                // Supprime l'ancien format seulement apres une ecriture reussie.
                prefs.edit().remove("session").remove("sessionActive").apply();
            }
        } catch (Exception ignored) { }''',
    '''            if (SessionStore.write(this, arr, savedActive)) {
                // Supprime l'ancien format seulement apres une ecriture reussie.
                prefs.edit().remove("session").remove("sessionActive").apply();
            }

            java.util.Set<String> previewIds = new java.util.HashSet<>();
            for (Tab tab : tabs) if (!tab.priv) previewIds.add(tab.id);
            TabPreviewStore.cleanup(this, previewIds);
        } catch (Exception ignored) { }''',
    "nettoyage apercus",
)

src = replace_once(
    src,
    '''                setupSession(false, u, true, encoded);
                tabs.get(tabs.size() - 1).title = o.optString("title", "");
                if (i == wanted) target = tabs.size() - 1;''',
    '''                setupSession(false, u, true, encoded);
                Tab restored = tabs.get(tabs.size() - 1);
                restored.title = o.optString("title", "");
                restored.id = o.optString("id", restored.id);
                restored.group = o.optString("group", "");
                restored.pinned = o.optBoolean("pinned", false);
                restored.sleeping = o.optBoolean("sleeping", false);
                restored.lastUsed = o.optLong("lastUsed", System.currentTimeMillis());
                if (i == wanted) target = tabs.size() - 1;''',
    "restauration metadonnees",
)

src = replace_once(
    src,
    '''    protected void onPause() {
        flushAndSaveTabs();
        super.onPause();
    }''',
    '''    protected void onPause() {
        if (active >= 0 && active < tabs.size()) captureTabPreview(tabs.get(active));
        autoSleepInactiveTabs();
        flushAndSaveTabs();
        super.onPause();
    }''',
    "onPause",
)

MAIN.write_text(src, encoding="utf-8")
print("Espace de travail des onglets installe dans MainActivity.java")
print(f"Sauvegarde: {BACKUP}")
