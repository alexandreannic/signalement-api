# SignalementApi

API de l'outil signalement.

L’outil signalement permet à chaque consommateur de signaler directement les anomalies constatées dans sa vie de tous les jours (chez son épicier, dans un bar..), de manière très rapide et très simple auprès du professionnel.

Plus d'information ici : https://beta.gouv.fr/startup/signalement.html

L'API nécessite une base PostgreSQL pour la persistence des données. 

## Développement

Créer un fichier de configuration local, par exemple local.conf, et configurer la connexion à la base de données via la propriété `slick.dbs.default.db.properties.url`
```
include "play.conf"

slick.dbs.default.db.properties.url=postgres://user:pass@host/dbname
play.mailer.mock = yes
```

Lancer l'application en local :
```bash
sbt run -Dconfig.file=[chemin vers le fichier de configuration local]
```

L'API est accessible à l'adresse `http://localhost:9000` avec rechargement à chaud des modifications.

## Tests

Pour exécuter les tests :

```bash
sbt test
```

## Démo

La version de démo de l'API est accessible à l'adresse https://signalement-api.herokuapp.com/

## Production

L'API de production de l'application  est accessible à l'adresse https://signalement-api.beta.gouv.fr


## Variables d'environnement

|Nom|Description|Valeur par défaut|
|:---|:---|:---|
|<a name="APPLICATION_HOST">APPLICATION_HOST</a>|Hôte du serveur hébergeant l'application||
|<a name="APPLICATION_HOST">APPLICATION_SECRET</a>|Clé secrète de l'application||
|<a name="APPLICATION_HOST">EVOLUTIONS_AUTO_APPLY</a>|Exécution automatique des scripts `upgrade` de la base de données|false|
|<a name="APPLICATION_HOST">EVOLUTIONS_AUTO_APPLY_DOWNS</a>|Exécution automatique des scripts `downgrade` de la base de données|false|
|<a name="APPLICATION_HOST">MAX_CONNECTIONS</a>|Nombre maximum de connexions ouvertes vers la base de données||
|<a name="APPLICATION_HOST">MAIL_FROM</a>|Expéditeur des mails||
|<a name="APPLICATION_HOST">MAIL_CONTACT_RECIPIENT</a>|Boite mail destinataire des mails génériques||
|<a name="APPLICATION_HOST">MAILER_HOST</a>|Hôte du serveur de mails||
|<a name="APPLICATION_HOST">MAILER_PORT</a>|Port du serveur de mails||
|<a name="APPLICATION_HOST">MAILER_USER</a>|Nom d'utilisateur du serveur de mails||
|<a name="APPLICATION_HOST">MAILER_PASSWORD</a>|Mot de passe du serveur de mails||
|<a name="APPLICATION_HOST">SENTRY_DSN</a>|Identifiant pour intégration avec [Sentry](https://sentry.io)||
