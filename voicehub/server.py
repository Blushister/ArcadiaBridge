"""Relais vocal entre les serveurs Arcadia.

Chaque serveur Minecraft (ArcadiaBridge) lui envoie la voix de ses joueurs, et il la renvoie aux autres serveurs. Il ne connait
ni les joueurs ni les salons : l'audio est chiffre de bout en bout entre les serveurs, il ne verifie que l'authenticite des
paquets avec le secret partage. Rien n'est enregistre ni ecrit dans les journaux, a part le nombre de serveurs connectes.

Paquet : "AV" | version (1) | genre (1 : 0 presence, 1 audio) | contenu | signature HMAC-SHA256 tronquee a 16 octets.
Presence : horodatage en millisecondes (8 octets) puis identifiant du serveur.
"""
import argparse
import hashlib
import hmac
import socket
import struct
import time

MAGIC = b"AV\x01"
TAG_SIZE = 16
HEADER_SIZE = len(MAGIC) + 1
KIND_PRESENCE = 0
KIND_AUDIO = 1
KIND_ACK = 2
SERVER_TIMEOUT = 30
CLOCK_MARGIN = 60
MAX_PACKETS_PER_SECOND = 3000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=24480)
    parser.add_argument("--secret", required=True)
    args = parser.parse_args()
    key = hashlib.sha256(args.secret.encode("utf-8") + b"|hmac").digest()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.host, args.port))
    sock.settimeout(5)
    print(f"Relais vocal pret sur {args.host}:{args.port}", flush=True)

    servers = {}  # adresse -> derniere presence
    window_start = 0.0
    window_count = {}
    last_count = -1
    while True:
        try:
            packet, address = sock.recvfrom(2048)
        except OSError:
            # Delai depasse, ou (sous Windows) un serveur parti qui ne repond plus : ce n'est jamais une raison de s'arreter.
            packet = None
        now = time.time()
        for known in [a for a, seen in servers.items() if now - seen > SERVER_TIMEOUT]:
            del servers[known]
        if len(servers) != last_count:
            last_count = len(servers)
            print(f"Serveurs connectes : {last_count}", flush=True)
        if packet is None or len(packet) < HEADER_SIZE + TAG_SIZE or not packet.startswith(MAGIC):
            continue
        body, tag = packet[:-TAG_SIZE], packet[-TAG_SIZE:]
        if not hmac.compare_digest(hmac.new(key, body, hashlib.sha256).digest()[:TAG_SIZE], tag):
            continue
        kind = body[len(MAGIC)]
        if kind == KIND_PRESENCE:
            if len(body) >= HEADER_SIZE + 8:
                sent = struct.unpack(">q", body[HEADER_SIZE:HEADER_SIZE + 8])[0] / 1000
                if abs(now - sent) <= CLOCK_MARGIN:
                    servers[address] = now
                    # Accuse de reception : le serveur sait que le relais repond, meme quand personne ne parle.
                    ack = MAGIC + bytes([KIND_ACK]) + body[HEADER_SIZE:HEADER_SIZE + 8]
                    try:
                        sock.sendto(ack + hmac.new(key, ack, hashlib.sha256).digest()[:TAG_SIZE], address)
                    except OSError:
                        pass
            continue
        if kind != KIND_AUDIO or address not in servers:
            continue
        if now - window_start >= 1:
            window_start = now
            window_count = {}
        window_count[address] = window_count.get(address, 0) + 1
        if window_count[address] > MAX_PACKETS_PER_SECOND:
            continue
        for target in servers:
            if target != address:
                try:
                    sock.sendto(packet, target)
                except OSError:
                    pass


if __name__ == "__main__":
    main()
