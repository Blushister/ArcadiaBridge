package com.arcadia.bridge.util;

import java.util.regex.Pattern;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/** Nettoyage des textes qui circulent entre Minecraft et Discord. */
public final class Text {

    private static final Pattern SECTION_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]?");
    private static final Pattern AMPERSAND_CODES = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\n]]");
    /** @everyone, @here, &lt;@id&gt;, &lt;@!id&gt;, &lt;@&amp;id&gt; : une espace de largeur nulle est glissee apres le @. */
    private static final Pattern MENTIONS = Pattern.compile("@(?=everyone|here|[0-9&!])");
    private static final String ZERO_WIDTH_SPACE = "​";

    private Text() {
    }

    /** Convertit les codes &x d'un texte de configuration en codes §x. */
    public static String colorize(String configText) {
        return AMPERSAND_CODES.matcher(configText).replaceAll("§$1");
    }

    /** Retire toute mise en forme Minecraft (§ et &) et les caracteres de controle. */
    public static String stripFormatting(String text) {
        if (text == null) {
            return "";
        }
        String out = SECTION_CODES.matcher(text).replaceAll("");
        out = AMPERSAND_CODES.matcher(out).replaceAll("");
        return CONTROL_CHARS.matcher(out).replaceAll("");
    }

    /** Texte venant de Discord, rendu sur pour le chat Minecraft : pas de codes couleur, une ligne, longueur limitee. */
    public static String forMinecraft(String text, int maxLength) {
        String out = stripFormatting(text).replace('\n', ' ').trim();
        if (out.length() > maxLength) {
            out = out.substring(0, maxLength - 1) + "…";
        }
        return out;
    }

    /**
     * Texte venant de Minecraft, rendu sur pour Discord : sans markdown, sans mise en forme Minecraft
     * et sans mention active.
     */
    public static String forDiscord(String text) {
        return neutralizeMentions(MarkdownSanitizer.escape(stripFormatting(text)));
    }

    /**
     * Desamorce les mentions. Le bot envoie deja ses messages avec allowed_mentions vide (rien ne peut
     * notifier), mais on casse aussi le texte : un joueur ne doit pas pouvoir afficher un faux
     * "@everyone" credible dans le salon.
     */
    public static String neutralizeMentions(String text) {
        return MENTIONS.matcher(text).replaceAll("@" + ZERO_WIDTH_SPACE);
    }

    /** Remplace les variables {cle} ; args = cle1, valeur1, cle2, valeur2... */
    public static String fill(String template, Object... args) {
        String out = template;
        for (int i = 0; i + 1 < args.length; i += 2) {
            out = out.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return out;
    }
}
