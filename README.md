# VibeCraft

**Vibe-coder un serveur Minecraft en direct.** Le public demande une fonctionnalité, une ou plusieurs IA (Claude Code) l'écrivent, et elle apparaît dans le jeu **sans redémarrer le serveur, sans déconnecter personne, et sans crash** même quand le code est à moitié écrit ou bugué. Pensé pour des démos live en conférence.

- Serveur **Paper 26.3**, clients Minecraft **vanilla** (aucun mod à installer chez les joueurs).
- Chaque fonctionnalité = un **module** Java dans `workspace/modules/<id>/` = une conversation d'IA. Plusieurs IA travaillent **en parallèle**.
- Les modules sont **compilés en mémoire et rechargés à chaud** (~150 ms). Si ça ne compile pas ou plante, l'ancienne version continue de tourner.
- **Garde-fous** : boucles infinies coupées (250 ms), exceptions isolées, appels dangereux refusés (`System.exit`, kick/ban/op, sockets…), disjoncteur par module, retour automatique à la version précédente.
- **Contenu « comme un mod »** : resource pack généré à chaud (modèles 3D, textures, GUI texturés) et chargé à la demande (les joueurs sont prévenus et font **clic droit avec une boussole**), textures générées par IA via OpenRouter ou Meshy AI, objets 3D réalistes décrits en texte (`*.shape.json`, `.obj`) ou générés par Meshy (text-to-3D), compilés en modèles vanilla.
- **Pilotage** : `/vibe` (fenêtre « Claude » native du jeu : conversations par module, envoyer / arrêter, réflexions et étapes de l'IA ; le menu coffre reste dans « Modules »), `/vibe ai <id> <demande>` (lance Claude Code en tâche de fond), `/idea` pour le public, dashboard web projetable sur `http://127.0.0.1:8765/` (API `/api/conversations`).
- **Filet de sécurité optionnel** : proxy Velocity + serveur d'attente ; si le serveur principal redémarre, les joueurs patientent et reviennent tout seuls.

## Démarrage rapide (Windows)

Prérequis : un JDK 25 dans `runtime/jdk-25/` et Paper 26.3 dans `server/paper.jar` (non versionnés, voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

1. Compiler le plugin : `vibecraft-plugin/build.sh` (Git Bash).
2. Lancer : `scripts\start-server.bat` (installe automatiquement le dernier plugin compilé).
3. Dans la console : `op <ton pseudo>`. Se connecter sur `localhost` en 26.3.
4. Une fois : connecter Claude Code (`claude auth login`) pour `/vibe ai`.
5. Optionnel : clés dans `server/plugins/VibeCraft/secrets.yml` : `openrouter: "sk-or-..."` (textures) et/ou `meshy: "msy_..."` (textures et objets 3D Meshy AI).

Pour coder avec Claude Desktop : ouvrir une session sur le dossier `workspace/` (une session par module). Les IA y lisent [`workspace/CLAUDE.md`](workspace/CLAUDE.md) et [`workspace/ASSETS.md`](workspace/ASSETS.md).

## Héberger sur un VPS Linux

Pour un serveur joignable de partout (Ubuntu, systemd), en root sur le VPS :

```bash
git clone https://github.com/Volko61/vibecraft.git /opt/vibecraft && /opt/vibecraft/deploy/linux/install.sh
```

Détails, administration (`vibecraft logs`, `vibecraft cmd op <pseudo>`, `vibecraft update`) et sécurité : [docs/DEPLOY-VPS.md](docs/DEPLOY-VPS.md).

## Contenu du dépôt

| Dossier | Rôle |
|---|---|
| `vibecraft-plugin/` | le plugin (moteur de hot-reload, garde-fous, API HTTP, commandes, jobs IA, resource pack, compilateur 3D) |
| `workspace/` | dossier de travail des IA : guide, exemples de modules |
| `tests/` | tests de bout en bout (bot mineflayer), corpus de red-team |
| `proxy/` | filet de sécurité Velocity + limbo |
| `scripts/` | lancement du serveur |
| `deploy/linux/` | installation sur un VPS Linux (systemd, pare-feu) |
| `docs/` | architecture détaillée |

## Sécurité pour une conférence

Voir la section « Before the talk » de [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) : mode proxy en `online-mode`, RCON non exposé, `ops.json` limité au présentateur. Les modules s'exécutent dans la JVM du serveur : les garde-fous protègent contre les erreurs de l'IA, pas contre un attaquant déterminé.
