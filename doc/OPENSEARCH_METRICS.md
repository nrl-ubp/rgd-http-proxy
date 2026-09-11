# Metriques `ubp_*` dans OpenSearch

## 1. Ce que l'application expose

Micrometer/Prometheus est actif (`config/application.properties`) :

```properties
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true
```

L'exposition se fait sur `http://<host>:8080/q/metrics` (format texte Prometheus).

### `ubp_proxy_forward`

Produite par `ProxyService.recordForwardDuration(method, path, durationMs)`
(`src/main/java/com/ubp/rgd/proxy/services/ProxyService.java`), appelee apres chaque appel
sortant vers l'API proxifiee. Trois **gauges** portent le meme nom, differenciees par le tag
`stat` :

```
ubp_proxy_forward{method="GET",uri="persons/123",stat="min"} 12.0
ubp_proxy_forward{method="GET",uri="persons/123",stat="max"} 480.0
ubp_proxy_forward{method="GET",uri="persons/123",stat="avg"} 65.3
```

| Tag | Valeur | Remarque |
|---|---|---|
| `method` | `GET`, `POST`, `PUT`, `DELETE`, `PATCH` | |
| `uri` | chemin proxifie sans query string | valeur brute : les identifiants sont dans le chemin |
| `stat` | `min` / `max` / `avg` | agregats **cumulatifs depuis le demarrage du process** |

Points importants pour le dashboard :

* Unite = **millisecondes**.
* `max` est **monotone croissant** et remis a zero au redemarrage de l'application :
  le "temps de traitement le plus long" sur une periode = `max(metric_value)` des points
  `stat=max` de la periode.
* La cardinalite de `uri` peut exploser (chemins avec ids). Si besoin, normaliser le chemin
  (remplacer les segments numeriques/UUID par `{id}`) soit dans `recordForwardDuration`, soit
  dans le pipeline Logstash via un `mutate/gsub`.
* Autres metriques applicatives : `ubp_proxy_counter{name="get|post|put|delete|patch|get_me|transform|get_concat"}`.

## 2. Ingestion (Logstash -> OpenSearch)

Utiliser `config/metrics-logstash-pipeline.conf`. Le pipeline :

1. poll `/q/metrics` toutes les 30 s ;
2. decoupe en lignes, ignore les lignes `# HELP` / `# TYPE` ;
3. parse `nom{labels} valeur` ;
4. ne garde que les metriques `ubp_*` ;
5. **eclate les labels Prometheus en champs** `label.method`, `label.uri`, `label.stat` via un
   filtre `kv` (indispensable pour agreger par url et par methode) ;
6. ecrit dans l'index `quarkus-metrics-YYYY.MM.dd`.

Lancement :

```bash
logstash -f config/metrics-logstash-pipeline.conf
```

Document indexe resultant :

```json
{
  "@timestamp": "2026-09-10T08:30:00.000Z",
  "service_name": "rgd-http-proxy",
  "metric_name": "ubp_proxy_forward",
  "metric_value": 480.0,
  "label": { "method": "GET", "uri": "persons/123", "stat": "max" }
}
```

### Index template

A creer une fois (Dev Tools) pour garantir des `keyword` agregeables et un `double` :

```json
PUT _index_template/quarkus-metrics
{
  "index_patterns": ["quarkus-metrics-*"],
  "template": {
    "settings": { "number_of_shards": 1, "number_of_replicas": 1 },
    "mappings": {
      "properties": {
        "@timestamp":   { "type": "date" },
        "service_name": { "type": "keyword" },
        "metric_name":  { "type": "keyword" },
        "metric_value": { "type": "double" },
        "label": {
          "properties": {
            "method": { "type": "keyword" },
            "uri":    { "type": "keyword" },
            "stat":   { "type": "keyword" },
            "name":   { "type": "keyword" }
          }
        }
      }
    }
  }
}
```

Puis creer l'index pattern `quarkus-metrics-*` (champ temps `@timestamp`) dans
OpenSearch Dashboards.

## 3. Table "temps de traitement le plus long par URL et par methode"

### Option A - visualisation "Data Table" (recommandee)

1. **Visualize > Create visualization > Data Table**, source = index pattern `quarkus-metrics-*`.
2. **Filtre / requete** (barre DQL) :
   ```
   metric_name: "ubp_proxy_forward" and label.stat: "max"
   ```
3. **Metrics** :
   * Metric 1 : `Max` sur `metric_value` — label : `Temps max (ms)`.
   * (optionnel) ajouter une 2e visualisation ou un 2e tableau filtre sur `label.stat: "avg"`
     pour la moyenne, car `avg` et `max` sont des documents distincts.
4. **Buckets** :
   * *Split rows* : `Terms` sur `label.uri`, Order by `Metric: Temps max (ms)`, Descending,
     Size `50` — label : `URL`.
   * *Split rows* (sous-bucket) : `Terms` sur `label.method`, Order by `Metric: Temps max (ms)`,
     Descending, Size `10` — label : `Methode`.
5. **Options** : activer *Show total* si souhaite, trier la colonne `Temps max (ms)` en
   decroissant.
6. Choisir la plage temporelle voulue (ex. *Last 24 hours*) et sauvegarder sous
   `ubp_proxy_forward - Temps max par URL/methode`, puis l'ajouter au dashboard.

Resultat :

| URL | Methode | Temps max (ms) |
|---|---|---|
| persons/search | POST | 4 820 |
| persons/123 | GET | 480 |
| accounts | GET | 210 |

### Option B - requete SQL (Query Workbench)

```sql
SELECT label.uri   AS url,
       label.method AS method,
       MAX(metric_value) AS max_ms,
       AVG(metric_value) AS avg_of_max_ms
FROM   quarkus-metrics-*
WHERE  metric_name = 'ubp_proxy_forward'
  AND  label.stat = 'max'
GROUP BY label.uri, label.method
ORDER BY max_ms DESC
LIMIT 50
```

### Option C - agregation brute (Dev Tools), utile pour verifier

```json
GET quarkus-metrics-*/_search
{
  "size": 0,
  "query": {
    "bool": {
      "filter": [
        { "term": { "metric_name": "ubp_proxy_forward" } },
        { "term": { "label.stat": "max" } },
        { "range": { "@timestamp": { "gte": "now-24h" } } }
      ]
    }
  },
  "aggs": {
    "by_uri": {
      "terms": { "field": "label.uri", "size": 50, "order": { "max_ms": "desc" } },
      "aggs": {
        "max_ms": { "max": { "field": "metric_value" } },
        "by_method": {
          "terms": { "field": "label.method", "size": 10, "order": { "max_ms": "desc" } },
          "aggs": { "max_ms": { "max": { "field": "metric_value" } } }
        }
      }
    }
  }
}
```

## 4. Compléments de dashboard suggeres

* **Line chart** : `Max(metric_value)` sur `metric_name: ubp_proxy_forward and label.stat: "avg"`,
  axe X = `Date Histogram(@timestamp)`, split series = `label.uri` -> evolution du temps moyen.
* **Metric** : `Max(metric_value)` filtre `label.stat: "max"` -> pire temps observe.
* **Table trafic** : `Max(metric_value)` sur `metric_name: ubp_proxy_counter`, split rows
  `label.name` (compteur cumulatif : utiliser `Max` puis une derivee si besoin du debit).

## 5. Limites connues et ameliorations possibles

* `min`/`max`/`avg` etant cumulatifs, on ne peut pas obtenir un "max de la derniere heure" reel :
  seul le max depuis le demarrage est disponible. Pour des fenetres glissantes, remplacer les
  gauges par un `Timer` Micrometer (`metricsRegistry.timer("ubp_proxy_forward", tags)`), qui
  expose `_count`, `_sum` et des quantiles/histogrammes exploitables par intervalle.
* Le filtre `kv` coupe les labels sur `,` : une valeur de label contenant une virgule serait mal
  decoupee (non observe sur `method`/`uri`/`stat`).
* Le scrape toutes les 30 s peut manquer un pic si l'application redemarre entre deux collectes.
