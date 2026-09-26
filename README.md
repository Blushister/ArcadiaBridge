# ArcadiaBridge

Liaison Discord ↔ Minecraft pour les serveurs Arcadia — NeoForge 1.21.1, côté serveur uniquement.
Réécriture complète du mod `arcadiabridge 0.5.13` (aucun code repris de l'ancienne version).

## Fonctionnalités

- **Chat dans les deux sens** entre un salon Discord et le chat Minecraft, avec le préfixe LuckPerms du joueur.
- **Annonces** : démarrage et arrêt du serveur, connexions, déconnexions, morts.
- **Chat inter-serveurs** : les joueurs des différents serveurs Arcadia se parlent, chacun pouvant
  rester en local avec `/chat`. Côté Discord, chaque serveur garde son propre salon.
- **Signalements** : `/report <joueur> <raison>` en jeu, transmis au staff sur Discord avec des boutons
  Bannir / Rendre muet / Ignorer.
- **Alerte de contournement de ban** : prévient le staff quand un compte banni partage l'adresse d'un
  joueur qui se connecte (alerte seulement, jamais de sanction automatique).
- **Deux styles d'affichage** : compact (tout par le bot, une ligne par événement) ou riche (messages
  des joueurs via webhook avec leur tête, annonces en encarts colorés).
- **`/online`** sur Discord : qui est connecté, sur quel serveur ; et le statut du bot affiche le nombre
  total de joueurs.
- **Vérification des comptes** : à sa première connexion, le joueur reçoit un code privé et le saisit sur
  Discord avec `/verify`.
- **Sanctions** : ban, ban temporaire, mute, mute temporaire, avertissements, avec historique.
- **Synchronisation des sanctions** dans les deux sens : un ban en jeu bannit le compte Discord lié, un ban
  fait sur Discord bannit le compte Minecraft lié, et les durées expirent réellement.
- **Base MySQL partagée** entre tous les serveurs : une liaison ou une sanction vaut partout.
- **API** pour les autres mods Arcadia (`com.arcadia.bridge.api.ArcadiaBridgeAPI`).

## Installation

1. Déposer `arcadiabridge-<version>.jar` dans le dossier `mods/` du serveur.
2. Démarrer une fois le serveur : `config/arcadiabridge.toml` est créé.
3. Remplir la configuration (voir ci-dessous) puis redémarrer.

Le mod n'est pas nécessaire côté client.

### Bot Discord

Le bot a besoin de :

- l'intent privilégié **Message Content** (portail développeur Discord, onglet Bot) ;
- les permissions **Voir les salons**, **Envoyer des messages**, **Gérer les messages** (pour effacer un
  `!verify` contenant un code), **Bannir des membres**, **Exclure temporairement des membres** et
  **Gérer les webhooks** (pour créer lui-même le webhook du chat) ;
- **ne pas** avoir la permission « Mentionner @everyone » : le mod bloque déjà toutes les mentions,
  mais autant ne pas la donner.

### Configuration

Sections principales de `config/arcadiabridge.toml` :

| Section | À renseigner |
|---|---|
| `general` | `serverId` (unique par serveur), `serverName`, `language`, `presentation` |
| `database` | hôte, base, utilisateur, mot de passe de la base partagée |
| `discord` | `token`, `guildId`, `chatChannelId`, `logsChannelId`, `primaryMode` |
| `verification` | `enabled`, `mode` (`grace` ou `kick`), durées |
| `sanctions` | synchronisation Discord, intervalle de synchro, nombre max d'avertissements |
| `chat` | relais, format, webhook, chat inter-serveurs |
| `reports` | salon des signalements, délai anti-abus, durées des boutons |
| `security` | alerte de contournement de ban, conservation des empreintes d'adresse |

**`presentation`** vaut `compact` (défaut) ou `riche`. En `compact`, tout est posté par le bot en une
ligne. En `riche`, les messages des joueurs passent par un webhook (pseudo et tête de skin) et les
annonces deviennent des encarts colorés. Le bot ne crée de webhook que dans ce second cas.

**Serveur primaire.** Un seul serveur à la fois enregistre les commandes slash, traite `/verify`, réagit
aux bans faits sur Discord et applique les expirations côté Discord. Il est **élu automatiquement**
(`[discord] primaryMode = "auto"`, par défaut) grâce à un verrou MariaDB : si le primaire s'arrête, il
rend la main et un autre la reprend au cycle suivant ; s'il plante, MariaDB libère le verrou à la coupure
de la connexion. Reprise mesurée : 5 s après un arrêt, 12 s après un plantage. `always` force le rôle
(installation à un seul serveur), `never` l'interdit (serveur de test).

Les textes affichés sont modifiables dans `config/arcadiabridge/lang/<langue>.json`, rechargeables avec
`/arcadia_bridge reloadlang`.

## Commandes en jeu

| Commande | Permission |
|---|---|
| `/arcadia_ban <joueur> [raison]` | `arcadiabridge.command.ban` |
| `/arcadia_tempban <joueur> <durée> [raison]` | `arcadiabridge.command.ban` |
| `/arcadia_unban <joueur>` | `arcadiabridge.command.ban` |
| `/arcadia_mute <joueur> [raison]` | `arcadiabridge.command.mute` |
| `/arcadia_tempmute <joueur> <durée> [raison]` | `arcadiabridge.command.mute` |
| `/arcadia_unmute <joueur>` | `arcadiabridge.command.mute` |
| `/arcadia_warn <joueur> <raison>` | `arcadiabridge.command.warn` |
| `/arcadia_history <joueur>` | `arcadiabridge.command.history` |
| `/arcadia_unlink <joueur>` | `arcadiabridge.command.unlink` |
| `/arcadia_bridge status\|reloadlang` | `arcadiabridge.command.admin` |
| `/arcadia_verify` | tout le monde |
| `/report <joueur> <raison>` | tout le monde (délai anti-abus) |
| `/chat [global\|local]` | tout le monde |

Durées : `30m`, `2h`, `7d`, `1w`, ou combinées (`1d12h`).

Les permissions passent par l'API de permissions NeoForge, donc par LuckPerms s'il est installé. Sans
LuckPerms, elles reviennent aux opérateurs (niveau 2). `arcadiabridge.verification.bypass` dispense un
grade de la vérification.

> **Version de LuckPerms.** Sur NeoForge 21.1.221, utiliser **LuckPerms NeoForge 5.4.150** (celle des
> serveurs Arcadia). La 5.4.140 ne charge plus les données des joueurs à la connexion sur cette version
> de NeoForge et rend le serveur injouable, et les versions 5.5.x sont compilées pour Java 25 alors que
> Minecraft 1.21.1 tourne en Java 21.

## Commandes Discord

- `/verify <code>` — pour les joueurs, réponse visible uniquement par eux.
- `/mc ban|tempban|unban|mute|unmute|warn|info|unlink <joueur> …` — pour le staff (permission Discord
  « Bannir des membres », ou un rôle listé dans `staffRoleIds`).
- `/online` — pour tout le monde : qui est connecté et sur quel serveur.
- Les **boutons des signalements** revalident la permission au clic : un message reste cliquable
  longtemps après, y compris par quelqu'un qui a perdu son rôle entre-temps.

## Sécurité

Les failles de l'ancien mod sont corrigées :

- le code de vérification n'est **jamais publié sur Discord** ; il est affiché au seul joueur concerné ;
- code tiré avec `SecureRandom`, stocké **haché** (SHA-256), à **usage unique**, avec **expiration
  vérifiée** et limitation du nombre d'essais par compte Discord ;
- un compte déjà lié ne peut pas être re-lié par quelqu'un d'autre ;
- toutes les mentions sont désactivées sur les messages du bot **et** le texte des mentions est
  désamorcé : un joueur ne peut ni déclencher ni afficher un `@everyone` depuis le chat Minecraft ;
- le clic sur le code en jeu copie **le code seul** : coller « /verify CODE » dans Discord enverrait un
  message visible de tous au lieu d'ouvrir la commande slash ;
- un `/verify CODE` ou `!verify CODE` écrit en message texte est traité **puis effacé**, pour que le code
  ne reste pas affiché dans le salon ;
- codes couleur et markdown neutralisés dans les deux sens ;
- requêtes SQL exclusivement préparées ;
- les adresses IP ne sont **jamais stockées** : seule une empreinte salée l'est, oubliée après le délai
  configuré (90 jours par défaut), et elle ne sert qu'à alerter le staff.

## Déploiement sur l'infrastructure Arcadia

**Bot Discord partagé avec Arcadius.** Le mod utilise l'application Discord d'Arcadius, qui remplace à
chaque démarrage la liste complète de ses commandes au niveau du serveur Discord. Pour que ni l'un ni
l'autre ne puisse effacer les commandes de l'autre, le mod enregistre les siennes **au niveau global, une
par une**, et le serveur primaire vérifie toutes les ~5 minutes qu'elles existent toujours. Au
démarrage, le log liste les commandes du serveur Discord « laissées intactes » : on doit y voir celles
d'Arcadius. Le statut du bot est laissé à Arcadius (`botPresence = false`).

Pendant une phase de test sur le serveur Discord public, `staffOnlyCommands = true` réserve `/verify` et
`/online` au staff.

**Mise en forme du chat par LPC.** LPC écoute le chat en priorité maximale, diffuse lui-même le message
formaté puis annule l'événement d'origine. Le mod bloque donc les joueurs muets **avant la création de
l'événement de chat** (mixin sur `CommonHooks.onServerChatSubmittedEvent`) : aucun mod de chat ne peut
passer devant, quel que soit l'ordre de chargement. Et en présence de LPC, un message annulé est relayé,
puisqu'il a déjà été diffusé en jeu.

> Ne pas déclarer d'ordre de chargement par rapport à LPC : LPC se déclare lui-même avant LuckPerms, ce
> qui crée un cycle que NeoForge refuse (le serveur ne démarre plus).

**Reprise de l'ancien mod.** Au démarrage, le registre des joueurs de `arcadiabridge 0.5.x` (table
`players` de la même base) est recopié dans `ab_players` : UUID, pseudo et serveur les plus récents.
L'ancienne table est seulement lue ; l'import est rejouable (`[database] importLegacyPlayers`). L'ancien
mod n'avait produit ni liaison Discord ni sanction : il n'y a rien d'autre à reprendre.

L'ancien et le nouveau mod portent le même identifiant (`arcadiabridge`) : ils ne peuvent pas être
installés ensemble sur un même serveur.

## Notes techniques

- Aucune requête SQL sur le thread principal : les vérifications en jeu (ban, mute, liaison) lisent un
  cache mémoire rechargé toutes les `syncIntervalSeconds`.
- Les joueurs bannis sont refusés à la connexion (mixin sur `PlayerList#canPlayerLogin`), comme un ban
  vanilla.
- Si MySQL est injoignable, le serveur reste jouable : la vérification et l'application des sanctions
  sont suspendues et la connexion est retentée à chaque cycle de synchronisation.
- Les bibliothèques embarquées (JDA, MySQL, HikariCP, Jackson, OkHttp…) sont relocalisées sous
  `com.arcadia.bridge.shadow` pour éviter tout conflit avec les autres mods du modpack.

### Tables créées (préfixe configurable, `ab_` par défaut)

| Table | Contenu |
|---|---|
| `ab_players` | UUID, pseudo, dernier serveur et dernière connexion |
| `ab_links` | liaisons Minecraft ↔ Discord |
| `ab_codes` | codes de vérification en attente (hachés) |
| `ab_sanctions` | bans, mutes, avertissements, avec expiration et historique |
| `ab_servers` | état de chaque serveur (joueurs connectés, dernier signe de vie) |
| `ab_reports` | signalements, leur regroupement et leur traitement |
| `ab_chat` | relais du chat entre serveurs (purgé toutes les 5 minutes) |

Les tables de l'ancien mod ne sont pas réutilisées. Les joueurs devront refaire `/verify` une fois —
c'est volontaire, les anciennes liaisons n'étant pas fiables.

## Développement

```bash
./gradlew build        # jar dans build/libs/arcadiabridge-<version>.jar
./gradlew runServer    # serveur de test (dossier run/)
./gradlew runServer2   # second serveur, même base, pour tester le multi-serveur
./gradlew runClient    # client de test (pseudo Dev)
./gradlew runClient2   # second client (pseudo Dev2)
./gradlew test         # tests (voir TESTING.md)
```
