# Héberger VibeCraft sur un VPS Linux (Ubuntu)

Objectif : un serveur VibeCraft qui tourne en permanence sur un VPS (testé pour Ubuntu, systemd) et auquel
on se connecte depuis n'importe où avec un Minecraft **vanilla 26.3**.

## Installation (une commande)

Sur le VPS, en root :

```bash
apt-get update && apt-get install -y git
git clone https://github.com/Volko61/vibecraft.git /opt/vibecraft
/opt/vibecraft/deploy/linux/install.sh
```

Le script ([deploy/linux/install.sh](../deploy/linux/install.sh)) :

1. crée le compte système `vibecraft` (le serveur ne tourne pas en root) ;
2. télécharge le JDK 25 Temurin dans `runtime/jdk-25/` et le dernier build de Paper 26.3 dans `server/paper.jar`
   (checksum vérifié), puis extrait ses bibliothèques ;
3. écrit `eula.txt` et, à la première installation, un `server.properties` sûr pour Internet
   (`online-mode=true`, `enable-rcon=false`) ;
4. compile le plugin (`vibecraft-plugin/build.sh`) ;
5. installe Claude Code pour `/vibe ai` (`SKIP_CLAUDE=1` pour s'en passer) ;
6. installe et démarre le service systemd `vibecraft` (redémarre tout seul, au boot et après un crash) ;
7. ouvre le pare-feu `ufw` : SSH, **25565** (Minecraft) et **8766** (resource pack). Le dashboard (8765)
   reste fermé au monde.

Il est idempotent : le relancer ne touche ni au monde, ni aux modules, ni à `server.properties`.
Variables utiles : `VIBECRAFT_MEMORY=6G` (mémoire max), `UPGRADE_PAPER=1`, `SKIP_FIREWALL=1`.

> Si l'hébergeur a son propre pare-feu (panneau « firewall » / « security group »), y ouvrir aussi
> **TCP 25565** et **TCP 8766**.

## Ensuite

| Besoin | Commande (sur le VPS) |
|---|---|
| Se donner les droits d'admin | `sudo vibecraft cmd op <ton pseudo>` |
| Console en direct | `sudo vibecraft logs` |
| N'importe quelle commande console | `sudo vibecraft cmd <commande>` |
| Arrêter / démarrer / état | `sudo vibecraft stop` / `start` / `status` |
| Mettre à jour le jeu (git pull + build + redémarrage) | `sudo vibecraft update` |
| Connecter l'IA à son compte (`/vibe ai`), une fois | `sudo vibecraft ai-login` |
| Mémoire, clés API | `/etc/default/vibecraft` puis `sudo vibecraft restart` |

**Se connecter** : Multijoueur → Ajouter un serveur → l'IP du VPS (ou un nom de domaine qui pointe dessus).
Les textures des modules sont servies sur le port 8766 à l'adresse que le joueur a tapée
(`resource-pack.public-host: auto`).

**Dashboard** depuis ton PC, par un tunnel SSH (il n'est jamais exposé sur Internet) :

```bash
ssh -L 8765:127.0.0.1:8765 root@<ip-du-vps>
# puis ouvrir http://127.0.0.1:8765/
```

**Clés** OpenRouter / Meshy : dans `/etc/default/vibecraft` (`OPENROUTER_API_KEY=...`, `MESHY_API_KEY=...`) ou
dans `server/plugins/VibeCraft/secrets.yml`.

## Connecter l'IA (`/vibe ai`)

Tant que Claude Code n'est pas connecté, le jeu affiche « IA indisponible : l'IA n'est pas encore connectée à son
compte ». Sur le VPS :

```bash
sudo vibecraft ai-login
```

La commande affiche un lien : ouvre-le dans le navigateur de ton PC, connecte-toi, puis colle le code
affiché dans le terminal. L'IA se réactive toute seule dans les 10 secondes, sans redémarrer le serveur
(la console, `sudo vibecraft logs`, affiche alors `[AI] Claude Code auth: ...`).

Autre possibilité : une clé API ou un jeton dans `/etc/default/vibecraft` (`ANTHROPIC_API_KEY=...`, ou
`CLAUDE_CODE_OAUTH_TOKEN=...` obtenu avec `sudo -u vibecraft -H ~vibecraft/.local/bin/claude setup-token`),
puis `sudo vibecraft restart`.

## Console du service

Le service lit sa console sur la FIFO `/run/vibecraft.stdin` (unité `vibecraft.socket`) : c'est ce
qu'utilisent `vibecraft cmd` et l'arrêt propre (`stop`, le monde est sauvegardé). La sortie va dans le journal
(`journalctl -u vibecraft`). Pour lancer à la main sans systemd : `scripts/start-server.sh`.

## Sécurité sur Internet

* **online-mode=true** : seuls des comptes Minecraft authentifiés entrent, personne ne peut prendre le pseudo
  d'un op. Pour un serveur privé : `sudo vibecraft cmd whitelist on` puis `whitelist add <pseudo>`.
* **RCON désactivé**, API et dashboard sur 127.0.0.1 : rien d'autre que 25565 et 8766 n'est ouvert.
* **SSH** : se connecter par clé (`ssh-copy-id root@<ip>`), puis mettre `PasswordAuthentication no` dans
  `/etc/ssh/sshd_config` et `systemctl restart ssh`. Changer le mot de passe root s'il a circulé.
* Les modules tournent dans la JVM du serveur : ne donner `op` (donc `/vibe ai`) qu'à des personnes de confiance.
* Le filet de sécurité Velocity (`proxy/`) n'a pour l'instant que des scripts Windows : il n'est pas installé ici.
