# Changelog - Pronostici Calcio

Storico versioni dell'app, consolidato dai vari `README_v*.txt` in un unico
file. Le versioni più recenti sono in cima.

# Versione 3.3

- Le importazioni storiche vengono conteggiate come riuscite soltanto quando l’API restituisce realmente delle partite.
- Le importazioni fallite non bloccano il popolamento: si passa alla successiva e verranno riprovate nei cicli seguenti.
- Il pannello distingue importazioni riuscite, tentativi e fallimenti e mostra l’ultimo risultato con la causa dell’eventuale errore.
- Mostra quante nuove partite sono state aggiunte da ogni importazione.
- Il pannello database è scorrevole sugli schermi più piccoli.

# Versione 3.2

- Nuovo comando “Aggiorna partite ora” che forza una richiesta aggiornata senza eliminare la copia locale di sicurezza.
- Il menu indica data, ora e origine degli ultimi dati: API, cache oppure cache offline.
- Nuovo pannello “Stato database” con partite concluse, campionati, avanzamento importazioni, periodo coperto, ultimo salvataggio e spazio occupato.

# Versione 3.1

- Rimossa la grande scritta bianca superiore per lasciare più spazio alle partite.
- Oggi e Domani evidenziano sempre la data realmente selezionata.
- I due pulsanti data vengono temporaneamente bloccati durante il caricamento delle partite, evitando chiamate duplicate con tocchi rapidi.
- Il riepilogo in alto segnala chiaramente quando sono attivi dei filtri.
- Ogni campionato mostra partite visibili e totali quando un filtro nasconde alcuni incontri.

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
# Versione 3.0

- Tutti i campionati partono chiusi e mostrano soltanto nome e numero di partite.
- Tocco sull'intestazione per aprire o richiudere le partite del campionato.
- Stato della tendina mantenuto durante filtri e aggiornamento dei pronostici della stessa schermata.
- Incluso il nuovo database storico permanente e il rating Elo proprietario.

# Versione 2.9

- Nuovo database SQLite permanente, separato dalla cache temporanea dell'API.
- Salvataggio automatico senza duplicati di partite, risultati e probabilità del modello.
- Importazione progressiva di cinque stagioni, una coppia campionato/stagione al giorno.
- Scontri diretti degli ultimi cinque anni calcolati dal database dell'app.
- Gli scontri diretti entrano nel pronostico con almeno tre gare e un peso massimo prudente del 15%.
- Rating Elo proprietario, aggiornato partita dopo partita e combinato prudentemente con il modello Poisson.
- Il menu mostra quante partite concluse sono già presenti nell'archivio.

# Versione 2.8

- Tutti i pannelli e i dialoghi usano ora lo sfondo blu scuro, i testi chiari e gli accenti verdi del tema dell'app.
- Aggiunta la probabilità Over 1,5 accanto a Goal e Over 2,5.
- Nuovo filtro Over 1,5 ≥60%.
- Salvataggio, verifica nello storico e statistiche separate per Over/Under 1,5.

# Versione 2.7

- Menu principale completamente personalizzato con i colori dell'app.
- Pannello blu scuro, bordo verde, righe arrotondate e pulsante Chiudi coerente.
- Rimossa la selezione Calendario: restano solo Oggi e Domani, compatibili con l'API.
- Una vecchia data salvata viene riportata automaticamente a Oggi.

# Versione 2.6

- Nuovo menu compatto: in alto restano Oggi, Domani e Menu.
- Storico dettagliato con pronostico originale e risultato reale.
- Verifica automatica separata per 1X2, Goal/No Goal e Over/Under 2,5.
- Statistiche generali e suddivise per campionato.
- Pronostici congelati anche da Domani e Calendario prima del calcio d'inizio.
- Intestazioni dei campionati più evidenti e coerenti con il tema.

# Versione 2.5

- Partite della giornata raggruppate per campionato e ordinate per orario.
- Corretto l'ordine della forma recente: vengono usate le ultime 8 partite reali.
- Le richieste vecchie non possono più sovrascrivere la giornata selezionata.
- Caricamento iniziale alleggerito: prior solo per i campionati presenti.
- Cache scaduta ripulita automaticamente e fallback offline sui dati salvati.
- Data, campionato e filtri vengono conservati alla riapertura.
- Build release firmata in modo permanente tramite GitHub Secrets.
- Backup Android disabilitato e nuovi test automatici sullo storico recente.
