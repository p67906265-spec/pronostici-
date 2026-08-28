# Changelog - Pronostici Calcio

Storico versioni dell'app, consolidato dai vari `README_v*.txt` in un unico
file. Le versioni più recenti sono in cima.

## v2.4
- I nomi delle due squadre nella scheda partita sono toccabili separatamente.
- Toccando una squadra apre le ultime 5 partite ricavate dall'archivio locale.
- V = Vittoria, P = Pareggio, S = Sconfitta.
- Per ogni partita mostra data, risultato dal punto di vista della squadra e avversario.
- Nessuna chiamata API `last=5`: usa solo i dati già presenti nell'archivio locale fino a 60 giorni.
- *(fix successivo)* le ultime 5 partite, quando l'archivio locale non basta, usano football-data.org invece di API-Football (piano gratuito non consente la stagione corrente).

## v2.3
- Nuovo pulsante "Posizione in classifica" in ogni partita, apre la classifica completa del campionato.
- Le due squadre della partita vengono evidenziate con bordo verde e nome verde.
- Matching tollerante ai diversi nomi tra API-FOOTBALL e football-data.org.
- Funziona per Serie A, Premier League, La Liga, Bundesliga, Ligue 1, Eredivisie, Primeira Liga e Champions League.

## v2.1
- Classifica ridisegnata: ogni squadra su una riga ben definita.
- Header con colonne: #, Squadra, Pt, G, V, N, P, DR.
- Righe separate in card con sfondo alternato per migliorare la lettura.

## v2.0 - Doppia API
- API-FOOTBALL per: partite, risultati, archivio storico locale, motore pronostici proprio.
- football-data.org per: classifiche (Serie A, Premier League, La Liga, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Champions League).
- La classifica non usa più l'endpoint `standings` di API-FOOTBALL.
- Nuovo GitHub Secret richiesto: `FOOTBALL_DATA_KEY`.

## v1.9 - Archivio storico progressivo
- Orizzonte del modello portato da 21 a 60 giorni.
- Dati storici conservati nella cache locale del telefono.
- Massimo 20 nuove giornate scaricate per sessione/caricamento; le giornate già presenti non vengono richieste di nuovo.
- Nei dettagli analisi mostra quanti giorni dell'archivio 60gg sono già disponibili.

## v1.8
- Le partite terminate mostrano direttamente il risultato finale (es. "Finale: 4 - 0").
- Se esiste un pronostico salvato, viene mostrato anche ✅ corretto / ❌ sbagliato.

## v1.7 - Motore proprio
- Non usa più `/predictions` di API-FOOTBALL.
- Calcola i pronostici dai risultati reali dei 21 giorni precedenti (gol fatti/subiti, casa/trasferta, rendimento recente).
- Calcola xG stimati, 1/X/2, Goal, Più di 2,5, doppia chance e affidabilità.
- Nessun parametro `last` e nessun `headtohead`.

## v1.6
- Storico 7gg: eliminate le richieste `from`/`to` non accettate; usa 7 richieste `date=` compatibili.
- Classifica: aggiunto selettore dedicato.

## v1.5
- Rimossi definitivamente "Scontri diretti" e tutte le chiamate con parametro `last`.
- Nuovo pulsante Filtri: 1 / X / 2, Gol ≥60%, Over 2,5 ≥60%, Top 5 del giorno, ordinamento per affidabilità, azzera filtri.
- Storico con verifica automatica ✅/❌ per i pronostici 1X2 salvati.

## v1.4
- Rimossa la voce "Forma ultime 5" e la chiamata con parametro `last` (non disponibile nel piano Free).
- Dettagli analisi: Analisi pronostico + Scontri diretti.

## v1.3
- Testi pronostici tradotti in italiano.
- Calendario con selezione di qualsiasi data.
- Filtro Campionati, pronostici forti ≥70%, Preferiti per partita.
- Classifica del campionato selezionato.
- Statistiche 1X2 corretto/sbagliato sui pronostici salvati.

## v1.2
- Pulsante Campionati funzionante (Tutti + 10 campionati/coppe).
- Il filtro si applica a Oggi, Domani e Storico.

## v1.1 - dati reali
- API-FOOTBALL tramite GitHub Secret `API_FOOTBALL_KEY`.
- Schermate Oggi / Domani per Serie A, Premier League, La Liga, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Champions League, Europa League, Conference League.
- Pronostici 1X2 reali da endpoint `predictions`, storico ultimi 7 giorni, cache locale.

## v1.0 - prima versione dimostrativa
- Schermata Oggi / Domani con partite demo.
- Percentuali 1/X/2, Goal/No Goal, Over 2.5, pronostico consigliato, livello di affidabilità.
- Workflow GitHub Actions per generare APK debug.
