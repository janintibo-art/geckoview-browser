package com.example.geckobrowser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Identites de navigation basees sur les contextes de GeckoView.
 *
 * Chaque identite possede son propre contextId : cookies, localStorage et
 * autres donnees de site ne sont donc partages qu'entre les onglets qui
 * utilisent la meme identite.
 */
public final class ContainerManager {

    private ContainerManager() { }

    public static final String PERSONAL_ID = "personal";
    public static final String WORK_ID = "work";
    public static final String BANK_ID = "bank";
    public static final String SOCIAL_ID = "social";
    public static final String TEMPORARY_ID = "temporary";
    public static final String ANONYMOUS_ID = "anonymous";
    public static final String DEFAULT_ID = PERSONAL_ID;

    private static final String PREFS = "geckobrowser";
    private static final String PREF_DEFAULT = "defaultContainer";
    private static final String CONTEXT_PREFIX = "geckobrowser.identity.";

    public static final class Identity {
        public final String id;
        public final String name;
        public final String symbol;
        public final String description;
        public final int color;
        public final boolean privateMode;
        public final boolean ephemeral;
        public final boolean canBeDefault;

        Identity(String id, String name, String symbol, String description,
                 int color, boolean privateMode, boolean ephemeral,
                 boolean canBeDefault) {
            this.id = id;
            this.name = name;
            this.symbol = symbol;
            this.description = description;
            this.color = color;
            this.privateMode = privateMode;
            this.ephemeral = ephemeral;
            this.canBeDefault = canBeDefault;
        }
    }

    private static final Identity[] IDENTITIES = {
        new Identity(PERSONAL_ID, "Personnel", "●",
                "Navigation quotidienne et comptes personnels",
                0xFF6FAE5F, false, false, true),
        new Identity(WORK_ID, "Travail", "◆",
                "Comptes professionnels separes du reste",
                0xFF4F8FEF, false, false, true),
        new Identity(BANK_ID, "Banque", "▣",
                "Espace reserve aux services sensibles",
                0xFFD9A441, false, false, true),
        new Identity(SOCIAL_ID, "Reseaux sociaux", "✦",
                "Cookies sociaux confines dans leur propre espace",
                0xFFE56E9B, false, false, true),
        new Identity(TEMPORARY_ID, "Temporaire", "◐",
                "Session privee effacee apres le dernier onglet",
                0xFF9A8FEF, true, true, false),
        new Identity(ANONYMOUS_ID, "Anonyme", "◉",
                "Session privee isolee ; Tor reste une option distincte",
                0xFF7EC8C8, true, true, false)
    };

    public static Identity[] all() {
        return IDENTITIES.clone();
    }

    public static Identity find(String id) {
        String normalized = normalize(id);
        for (Identity identity : IDENTITIES) {
            if (identity.id.equals(normalized)) return identity;
        }
        return IDENTITIES[0];
    }

    public static String normalize(String id) {
        if (id != null) {
            for (Identity identity : IDENTITIES) {
                if (identity.id.equals(id)) return id;
            }
        }
        return DEFAULT_ID;
    }

    public static String name(String id) {
        return find(id).name;
    }

    public static String symbol(String id) {
        return find(id).symbol;
    }

    public static String description(String id) {
        return find(id).description;
    }

    public static int color(String id) {
        return find(id).color;
    }

    public static boolean isPrivate(String id) {
        return find(id).privateMode;
    }

    public static boolean isEphemeral(String id) {
        return find(id).ephemeral;
    }

    public static boolean canBeDefault(String id) {
        return find(id).canBeDefault;
    }

    /** Identifiant transmis a GeckoView pour partitionner les cookies. */
    public static String contextId(String id) {
        return CONTEXT_PREFIX + normalize(id);
    }

    public static String defaultId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = normalize(prefs.getString(PREF_DEFAULT, DEFAULT_ID));
        return canBeDefault(id) ? id : DEFAULT_ID;
    }

    public static void setDefault(Context context, String id) {
        String normalized = normalize(id);
        if (!canBeDefault(normalized)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_DEFAULT, normalized).apply();
    }
}
