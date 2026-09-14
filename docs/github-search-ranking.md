# How GitHub Search appears to rank repositories

An empirical reconstruction from the search history stored by Repo Growth Monitor.
GitHub does not publish the ranking formula. This document describes the ranking
that the collected results actually produce.

The sample is the daily top-50 of the tracked queries, observed for 17 days.
The conclusions are about those queries, not about every search on GitHub.

## The ranking in one paragraph

GitHub Search ranks repositories almost entirely by popularity. Forks weigh more
than stars. A keyword in the repository name multiplies that popularity: with a
match, far fewer stars are enough to reach the same place. Query competition
sets the bar. Everything else that looks like quality — README, topics, license,
contributors, commit freshness, description length — does not move the position.

In practice three things decide the rank:

1. The repository name, chosen once.
2. Forks and stars gained from outside GitHub Search.
3. Which queries are worth competing for.

Improving the repository itself, without those three, does not change the
position.

## What the ranker appears to do

For a given query GitHub first collects repositories that match the text. The
match is cheap: a word in the name, in the description, or even inside an emoji
shortcode such as `:outbox_tray:` is enough to enter the result set.

Then it orders that set by popularity. The order is not “quality first,
popularity second”. Popularity is the order. Text match only changes how much
popularity is required.

A useful way to read a position:

> Required popularity ≈ competitors above you × keyword discount from the name.

If the name contains the query words, the required popularity drops several
times. If the query is narrow, there are almost no popular competitors, so even
a small repository can sit at #1. If the query is a single common word, the
same repository can sit at #22 with identical text, because twenty-one much
more popular repositories stand above it.

## Signals that move the rank

### 1. Forks — the strongest popularity signal

Inside a single query, take every pair of repositories where stars and forks
disagree: one has more stars, the other has more forks. The one with more forks
ranks higher in 1,203 of 1,910 such pairs (63%).

How large a star gap forks can still beat:

| Star gap of the rival | Forks still rank higher |
|---|---|
| up to 1.5× | 73% |
| up to 3× | 68% |
| up to 10× | 47% |
| more than 10× | 18% |

Forks outweigh stars until the star gap reaches roughly five to eight times.
After that, stars win.

A linear model on pooled queries once pointed the other way (partial
correlation −0.166 for stars vs −0.141 for forks). That model mixed queries of
different scales. The within-query pairwise test is the one that matches how
GitHub actually orders a result page.

Stars and forks still move together (correlation 0.879). They are not
independent levers. When they conflict, forks decide.

### 2. Stars — almost as strong, and easier to see

Within a query, position correlates with the log of stars from −0.50 to −0.87.
Median stars by rank band:

| Rank band | Median stars |
|---|---|
| 1–5 | 39 |
| 6–10 | 15 |
| 11–25 | 5 |
| 26–40 | 2 |
| 41–50 | 0 |

In day-to-day reading, stars and forks are hard to separate. On a frozen
leaderboard the tracked repository moved from #10 to #6 in lockstep with stars
going from 17 to 21. Nothing else on that page moved.

### 3. Keyword in the name — a multiplier on required popularity

The first test of this signal was blind. Average position is about 25 whether
the name matches or not, because every repository in the table was already
selected into the top-50. The right question is not “which position”, but “how
many stars that position costs”.

For a place in the top-10:

| | Median stars needed |
|---|---|
| Query words in the name | 21 |
| Query words not in the name | 128 |

The discount is largest on short queries, where text match is otherwise weak:

| Query length | Stars with a name match | Stars without | Discount |
|---|---|---|---|
| 1 word | 80.5 | 739.5 | 9.2× |
| 2 words | 20.5 | 43.5 | 2.1× |
| 3 or more | 9 | 21 | 2.3× |

Where the word sits inside the name (start vs middle) was not strong enough to
separate from this effect. The presence of the word is what matters.

### 4. Description — the same idea, about half as strong as the name

A keyword in the description also lowers the star bar, but by less. Description
length itself does not help (see below).

GitHub’s tokenizer is literal. Repositories whose description contains the
emoji shortcode `:outbox_tray:` appear in `outbox` results even when they have
nothing to do with the outbox pattern.

### 5. Competitor density — the largest lever that is still under your control

The same repository is #1 for `transactional outbox kafka language:Java` and
#22 for `outbox`. The text match is the same in both cases: `outbox` is in the
name. The difference is who else matched.

| Query | Median stars of the 21 repositories above | Minimum stars among them |
|---|---|---|
| `outbox` | 151 | 31 |
| `transactional outbox kafka language:Java` | 4 | 0 |

Twenty-one stars sit below the floor of the broad query and far above the
median of the narrow one. Choosing the query is choosing the competitors.

Niche growth confirms the same picture. The whole `outbox` niche gained 2.4
stars per day across about fifty repositories, of which the tracked share is
about 10%. `outbox example` and `transactional outbox example` gained no stars
in fifteen days. Those queries are easy to occupy and useless to occupy.

## Signals that do not move the rank

Partial correlation with position after removing the effect of stars:

| Factor | Partial correlation |
|---|---|
| Contributors | −0.009 |
| Topics | −0.035 |
| Repository age | −0.056 |
| Recency of the last commit | −0.062 |
| Description length | +0.083 |
| Number of keyword mentions in the README | −0.134 |

All of these are indistinguishable from noise. Commit freshness even points the
wrong way: the top-5 are older and more abandoned than the tail (average 716
days since activity vs 605). The pages are headed by popular projects, not by
maintained ones.

Watchers (GitHub’s `subscribers_count`) are collected correctly. They still do
not rank. Of 12,284 result rows, 7,810 have a watcher count above zero;
`dtm-labs/dtm` is stored with 102 subscribers. The tracked repositories
genuinely have zero.

## Open question: H1 in the README

GitHub Search indexes README text, so a keyword in the first heading could in
principle help. It could not be tested.

All fifty README files from one result page were downloaded. Of the 22
repositories with the keyword in an H1, 21 also have it in the name. The two
effects cannot be separated.

Where a split is possible — name match with H1 vs name match without, nine
repositories — the gap is 2.5 positions at similar star counts. That is noise.
The hypothesis stays open. It needs repositories that put the word in the H1
and not in the name.

## What this looks like on a real leaderboard

Across 15 tracked queries the same repository is:

- #1 on 4 queries
- top-3 on 7
- top-10 on 11

The rest of the page barely moves. Over 17 days, no competitor in the top-15 of
`outbox language:Java` changed position even once. On nine of those seventeen
days the whole result list was identical. The only repository that moved was
the tracked one, from #10 to #6, exactly as its stars went from 17 to 21.

That is the ranker in motion: a popularity sort on a nearly frozen set, with
text match already decided at indexing time.

## What this means for promotion

Do once:

- Put the query’s important words in the repository name. That decision is
  worth a several-times discount on the stars you will later need.

Do continuously:

- Attract forks and stars from outside GitHub (LinkedIn, articles, releases
  that people actually use). Search will not create that popularity; it only
  sorts by it.
- Track narrow queries where the competitors above you have fewer stars than
  you do, and drop queries whose floor is already above you.

Do not expect a ranking effect from:

- README polish, topics, license, extra contributors, or “looks actively
  maintained”.
- Winning a query that nobody stars.

## Related: traffic is not the same as rank

GitHub Search rank and GitHub referrer traffic are easy to confuse and are not
the same thing.

The `github.com` referrer is not search click-through. Three facts point at
internal browsing instead:

- 33 views per visitor, against 3.4 from LinkedIn and 1.8 from ChatGPT.
- `/graphs/traffic` recorded 127 views from one person, `/pulse` another 70 —
  the owner looking at their own stats.
- `github.com` is 44% of views and only 16% of distinct people.

A direct rank-versus-traffic correlation of 0.878 on levels is a trap of two
rising series. On first differences it falls to −0.053. Seventeen points cannot
support that claim, and a referrer snapshot is itself a rolling 14-day window,
which smears any daily effect.

What the traffic numbers do show clearly: LinkedIn brought 17 unique visitors
in two weeks against 15 from all of GitHub, with a much healthier visit depth.
External channels create the popularity that search later ranks.

## How the reconstruction was done

Daily top-50 snapshots of the tracked queries, stored in `search_result`, plus
repository stats (stars, forks, watchers, contributors, last commit). Pairwise
tests were always run inside one query on one day, so a 10,000-star repository
in a crowded query is never compared with a 4-star repository in an empty one.

Three corrections along the way, kept here because each of them changed the
conclusion:

1. Watchers are real. The collector reads `subscribers_count`. Zero on the
   tracked repositories is not a bug.
2. Forks beat stars in a conflict. The first linear model said the opposite
   because it pooled queries.
3. Keywords in the name work. Average position hides it; required stars show
   it.

Limits: 17 days, top-50 only, queries around one niche, no access to GitHub’s
actual scoring code. The description above is the ranking the data produce, not
a claim about GitHub’s internals.
