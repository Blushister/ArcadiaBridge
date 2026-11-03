package com.arcadia.bridge.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Base64;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;

/**
 * Contenu d'un partage en base : NBT binaire compresse, en base 64. Le format texte (SNBT) ne se relit pas toujours :
 * une seule cle vide ou une valeur infinie dans les donnees d'un objet modde rendait tout l'inventaire illisible.
 */
final class ShareData {

    /** Plafond a la relecture, largement au-dessus de ce que {@link Shares} accepte d'enregistrer. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private ShareData() {
    }

    static String encode(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Taille reelle (non compressee) d'un NBT : c'est elle qui compte pour les paquets envoyes aux joueurs. */
    static int rawSize(CompoundTag tag) {
        int[] size = {0};
        try (DataOutputStream out = new DataOutputStream(new OutputStream() {
            @Override
            public void write(int b) {
                size[0]++;
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                size[0] += len;
            }
        })) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return size[0];
    }

    /** Relit un partage ; ceux ecrits par une ancienne version sont encore en SNBT (ils commencent par "{"). */
    static CompoundTag decode(String data) throws IOException, CommandSyntaxException {
        if (data.startsWith("{")) {
            return TagParser.parseTag(data);
        }
        return NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(data)),
                NbtAccounter.create(MAX_BYTES));
    }
}
