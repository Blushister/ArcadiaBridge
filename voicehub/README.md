# Relais vocal entre les serveurs

Permet à des joueurs de serveurs Minecraft différents de se parler dans les salons vocaux d'ArcadiaChat. Le micro et le son restent gérés par Simple Voice Chat : les joueurs n'ont rien de plus à installer.

Python 3 seul, sans dépendance :

```bash
python3 server.py --port 24480 --secret "un-long-secret"
```

Le port UDP doit être joignable depuis tous les serveurs Minecraft, y compris ceux hébergés ailleurs. Les joueurs ne s'y connectent jamais.

Sur chaque serveur Minecraft, dans `config/arcadiabridge.toml` :

```toml
[voice]
relayHost = "adresse-du-relais"
relayPort = 24480
relaySecret = "un-long-secret"
```

Le secret authentifie les paquets et chiffre l'audio entre les serveurs : il ne doit pas circuler. L'audio n'est ni enregistré ni écrit dans les journaux. Compter environ 30 kbit/s par joueur qui parle et par serveur qui écoute.
