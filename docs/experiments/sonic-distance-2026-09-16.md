# Plex sonic distance experiment — 2026-09-16

Read-only queries against the Music library on Michael’s configured Plex server. No library metadata, playback, queue, or app settings changed. Six seeds were selected from the screenshot and observed frequently played tracks; this is a small exploratory sample, not a representative library benchmark.

## Method

Each seed was queried at `0.03`, `0.05`, `0.075`, `0.10`, `0.125`, `0.15`, `0.20`, and `0.25` using `/library/metadata/{id}/nearest?limit=100&maxDistance=...`. Counts exclude normalized artist/title copies of the seed and other returned tracks, matching the new Track Radio guard. Existing queue contents were not available and therefore are not excluded. Counts are bounded by the 100-result request; high counts may understate available matches. At experiment time, the app requested 20 raw candidates with a fixed 0.25 cutoff. The subsequent implementation fetches 100 candidates and adds configurable starting distance with bounded widening; see [validation](../ANDROID_VALIDATION.md).

Plex documents this endpoint as sonic track similarity: [API](https://developer.plex.tv/pms/) and [sonic analysis explanation](https://support.plex.tv/articles/sonic-analysis-music/). Distance is not a similarity percentage.

## Distinct suggestions from up to 100 raw results

| Seed | 0.075 | 0.10 | 0.125 | 0.15 | 0.20 | 0.25 |
|---|---:|---:|---:|---:|---:|---:|
| Bob Sinclar — Vision of Paradise | 2 | 10 | 25 | 58 | 98 | 98 |
| Hey Violet — Hoodie | 0 | 1 | 6 | 8 | 41 | 87 |
| New Order — Love Less | 1 | 1 | 2 | 2 | 8 | 27 |
| Prince — Little Red Corvette | 0 | 0 | 0 | 1 | 7 | 31 |
| Concrete Blonde — Still in Hollywood | 0 | 0 | 2 | 11 | 45 | 98 |
| Goldfrapp — Rocket | 0 | 3 | 7 | 17 | 61 | 93 |

At 0.03 all six seeds had zero distinct suggestions. At 0.05 only Love Less had a suggestion (one).

## Practical interpretation

- A global 0.10 cutoff is too sparse for most of these seeds, although it yields ten suggestions for Vision of Paradise.
- 0.15 is a useful close-match listening experiment for some songs, but produces only one match for Little Red Corvette and two for Love Less.
- 0.20 is still sparse for those two seeds (seven and eight).
- 0.25 yields at least 20 distinct suggestions for all six when requesting 100 candidates, but the app’s 20-candidate request can shrink to fewer after deduplication.
- Because Plex orders by distance, lowering a cutoff above the 20th result’s distance does not change the first 20 suggestions. For Vision of Paradise, the 20th raw suggestion is approximately 0.11675.
- Candidate count and maximum distance should be separate controls: fetching more candidates can replace duplicate slots without widening the allowed sonic range. This overfetch behavior was subsequently implemented alongside continuous radio.
- Match quality still requires Michael’s listening judgment. No audio auditions were performed.

## Duplicate evidence

Hoodie’s first 20 raw suggestions at 0.25 include seed copies 123366 and 123370, Runnin’ IDs 120857 and 123730, Who Am I Living For? IDs 121125, 121580, and 103746, and greedy IDs 120630 and 119916. This reproduces multiple entries for the same artist/title in a sonic response, but does not prove it was the exact queue originally reported.

## Listening candidates

These are the first ten distinct suggestions for each seed at 0.25, in returned order. Use distance to see which would survive a tighter cutoff.

### Bob Sinclar — Vision of Paradise

| Distance | Artist | Track |
|---:|---|---|
| 0.05739 | Sono | Open the Door (Alexander Kowalski remix) |
| 0.06453 | Bob Sinclar | Gym Tonic |
| 0.07842 | Lily Allen | Fuck You (Annie Nightingale and Far Too Loud remix) |
| 0.07992 | Nitzer Ebb | Once You Say (People Theatre remix) |
| 0.08142 | Bob Sinclar | World Hold On (Paolo Ortelli Remix & Luke Degree) |
| 0.08657 | Jean‐Claude Ades | My First Kiss (Chuckie remix) (radio cut) |
| 0.09473 | Bob Sinclar | Magic Fly |
| 0.09669 | Nitzer Ebb | Down on Your Knees |
| 0.09875 | Kylie Minogue | I Guess I Like It Like That |
| 0.09930 | Bob Sinclar | Summer Moonlight |

### Hey Violet — Hoodie

| Distance | Artist | Track |
|---:|---|---|
| 0.09630 | Nelly Furtado | Showstopper |
| 0.10112 | GALXARA | Waste My Youth |
| 0.10351 | L Devine | Runnin’ |
| 0.10553 | Katy Perry | Who Am I Living For? |
| 0.11615 | Tate McRae | greedy |
| 0.12464 | Hey Violet | Problems |
| 0.14560 | Nelly Furtado | End Game |
| 0.14610 | Charli xcx | Gone |
| 0.15070 | KATSEYE | Tonight I Might |
| 0.15150 | Tegan and Sara | Dying to Know |

### New Order — Love Less

| Distance | Artist | Track |
|---:|---|---|
| 0.03233 | New Order | Fine Time |
| 0.11889 | New Order | Guilty Partner |
| 0.15822 | The Church | Disappear |
| 0.15875 | New Order | Leave Me Alone |
| 0.16888 | The Church | Youth Worshipper |
| 0.18281 | The Smiths | Some Girls Are Bigger Than Others |
| 0.18561 | Talking Heads | Wild Wild Life (extended mix) |
| 0.19002 | Talking Heads | Radio Head |
| 0.20564 | A Certain Ratio | And Then She Smiles |
| 0.21011 | The Church | Don't Look Back |

### Prince — Little Red Corvette

| Distance | Artist | Track |
|---:|---|---|
| 0.14686 | Prince | Paisley Park |
| 0.15736 | Tom Petty and the Heartbreakers | Mary’s New Car |
| 0.17569 | The Fixx | Liner |
| 0.17693 | Prince | Around the World in a Day |
| 0.18817 | The Police | Don’t Stand So Close to Me |
| 0.19060 | Duran Duran | Notorious (live) |
| 0.19259 | The Fixx | Cameras in Paris |
| 0.21025 | The Fixx | Wish |
| 0.21643 | Big Audio Dynamite | E=MC² |
| 0.21671 | The Fixx | Walkabout |

### Concrete Blonde — Still in Hollywood

| Distance | Artist | Track |
|---:|---|---|
| 0.11979 | Concrete Blonde | Your Haunted Head |
| 0.12083 | Def Leppard | On Through the Night |
| 0.12825 | Black Flag | You Let Me Down |
| 0.12991 | Black Flag | Sinking |
| 0.13254 | Black Flag | Paralyzed |
| 0.13264 | Def Leppard | You Got Me Runnin’ |
| 0.13624 | Def Leppard | Switch 625 |
| 0.13708 | Guns N’ Roses | Think About You (5.1 mix) |
| 0.14650 | AC/DC | Touch Too Much |
| 0.14797 | AC/DC | If You Want Blood (You’ve Got It) |

### Goldfrapp — Rocket

| Distance | Artist | Track |
|---:|---|---|
| 0.09185 | Kylie Minogue | Stars |
| 0.09904 | Madonna | Let It Will Be |
| 0.09972 | Hayley Kiyoko | Girls Like Girls |
| 0.11530 | Tegan and Sara | Goodbye, Goodbye |
| 0.11662 | Avril Lavigne | 17 |
| 0.11837 | Tegan and Sara | I Couldn’t Be Your Friend |
| 0.12123 | Avril Lavigne | What the Hell |
| 0.12934 | Kim Petras | Shame on Me |
| 0.13188 | P!nk | We Could Have It All |
| 0.13519 | The Corrs | I Do What I Like |

[Sanitized raw results](sonic-distance-2026-09-16.json) contain track metadata, IDs, and distances only; no tokens, media paths, or server address.
