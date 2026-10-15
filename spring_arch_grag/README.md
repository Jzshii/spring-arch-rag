# Artefakt zur Bachelorarbeit

Zwei Teile:

- `extraktor/` :  Java-Extraktor (JavaParser-basiert). Liest eine Spring-Boot-Codebasis
  und erzeugt Metadaten-JSON, Architekturgraph-JSON und einen lesbaren Beziehungstext.
- `rag-pipeline/` : Python-Implementierung der fünf RAG-Systeme (Plain Text, AST,
  AST + Rumpf, Basisgraph, Architekturgraph), der Evaluations-Harness und der
  Graph-Leiter-Varianten (G1–G4).

## Extraktor

```bash
cd extraktor
mvn clean package
java -jar target/RagParser-1.0-SNAPSHOT.jar <Pfad-zur-Codebasis> <Ausgabedatei.json>
```

Erzeugt neben der Ausgabedatei automatisch `rag-parser-graph.json` und
`rag-parser-knowledge.txt` im selben Verzeichnis. Java 17+ erforderlich.

Die drei ausgewerteten Zielcodebasen sind nicht Teil dieses Artefakts (fremder
Quellcode, nur referenziert):

| Datensatz | Repository | Commit |
|---|---|---|
| Petclinic | spring-projects/spring-petclinic | `a2c2ef994340d3970eb6db51247456a51bb161f8` |
| Microservices | spring-petclinic/spring-petclinic-microservices | `305a1f13e4f961001d4e6cb50a9db51dc3fc5967` |
| Mall | macrozheng/mall | `0504e86b1f1b6f1b8aa6a734d37a90fb67346be7` |

Für Mall wurden nur die Module `mall-admin`, `mall-common`, `mall-security`
analysiert (`mall-mbg`, `mall-portal`, `mall-search` ausgeschlossen).

## RAG-Pipeline

```bash
cd rag-pipeline
pip install -r requirements.txt
```

Voraussetzung: lokal laufendes Ollama mit den Modellen `llama3.1:8b` (Generator
und Judge, `temperature=0`) und `mxbai-embed-large` (Embeddings).

Die bereits erzeugten Extraktor-Ausgaben liegen unter `data/` (Petclinic) bzw.
`data/microservices/` und `data/mall/`. Die Fragen- und Referenzantwort-Kataloge
liegen unter `evaluation/ground_truth*.json`.

Für die textbasierten Systeme (Plain Text, AST, AST + Rumpf) wird zusätzlich der
Java-Quellcode der jeweiligen Zielcodebasis am in `evaluation/harness.py`
(`DATASETS[...]["java_src"]`) angegebenen Pfad erwartet — siehe Tabelle oben für
Repository und Commit. Die graphbasierten Systeme (Basisgraph, Architekturgraph)
benötigen nur die mitgelieferten `rag-parser-graph.json`-Dateien.

Hauptlauf (fünf Systeme, ein Datensatz, alle vier RAGAS-Metriken):

```bash
python -m evaluation.harness --dataset petclinic
```

Graph-Leiter-Varianten (G1–G4) und erweiterte Fragmentierungs-Systeme:
`evaluation/variant_harness.py`, Durchlauf-B-Nachbewertung der Graph-Leiter:
`evaluation/rescore_step3_b.py`.


