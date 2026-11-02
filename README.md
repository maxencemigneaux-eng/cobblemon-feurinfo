# Feurinfo — fiche Pokémon en jeu pour Cobblemon

Mod **100 % côté client** pour **Cobblemon 1.8.x** (Fabric, Minecraft 1.21.1) : il marche sur
n'importe quel serveur, même si personne d'autre ne l'a.

## Utilisation

- `/feurinfo dracaufeu` → ouvre la fiche (nom français ou anglais, ou numéro : `/feurinfo 6`).
  Appuie sur **Tab** pendant que tu tapes pour l'autocomplétion.
- `/feurinfo` → ouvre l'interface vide avec la barre de recherche.

## Dans l'interface

**À gauche — le modèle 3D**
- Le Pokémon tourne tout seul ; **glisse** avec la souris pour le tourner, **clique** pour entendre son cri.
- Boutons **< >** pour changer de forme (Alola, Galar, Méga, Gigamax...).
- Bouton **★ Shiny** pour voir sa version chromatique.

**À droite — les attaques**, par onglet : **Niveau**, **CT**, **Œufs**, **Tuteur**, **Évolution**, **Autres**.
Pour chaque attaque : icône du type (la même que dans Cobblemon), catégorie, puissance, précision, PP.
**Survole** une attaque pour lire sa description. Molette pour défiler.

**Chercher une attaque** : tape dans la barre « Chercher une attaque... » au-dessus de la liste
(ex. `séisme`, `lance`, ou un type comme `feu`). La recherche se fait dans **toutes** les catégories
à la fois, et la colonne **Méthode** indique comment le Pokémon l'apprend (Niv. 32, CT, Œuf, Tuteur...).
La recherche reste active quand tu changes de Pokémon. Clique sur un onglet pour revenir à la liste normale.

**En haut à droite — recherche** : tape un autre Pokémon puis **Entrée**.

## D'où viennent les données ?

Des fichiers de ton propre Cobblemon (les mêmes que ceux affichés par
[cobblemon-pokedata.timinc.us](https://cobblemon-pokedata.timinc.us)) : pas besoin d'internet,
et c'est toujours la bonne version. Sur un serveur, ce sont les données envoyées par le serveur
(donc ses éventuelles modifications sont prises en compte).

## Télécharger le .jar

Dossier **`telechargement/`** du dépôt → clique sur le .jar → bouton **Download raw file**.
