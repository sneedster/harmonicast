# HarmoniCast market-share strategy

**Date:** September 13, 2026  
**Status:** Proposed strategy, saved from the project discussion for continued planning in Codex.  
**Objective:** Grow active, retained usage, rather than subscription revenue or download counts.

This document preserves the strategy discussed in chat, with ordinary Markdown source links and the truncated closing sentence completed. Pilot targets are proposals, not forecasts, industry benchmarks, or implementation commitments. Product and competitor details are a dated snapshot; recheck them before publishing comparisons or promising features. Saving this document does not authorize changes to the app, analytics, distribution, or public marketing.

## Core strategy: steal listening occasions before trying to steal entire users

Getting someone to abandon Plexamp is a much bigger ask than getting them to say, “For a family drive, HarmoniCast is the one we use.” Win that situation, earn repeat use, and then compete for more of their listening.

Position HarmoniCast around **shared control of a personal music library**, with the weighted automatic mix as the reason people keep using it even when they are alone.

“Market share” here means **active, retained usage**, not subscription revenue.

## 1. Pick a fight we can win

The obvious alternatives already cover a lot of ground. The following is a working competitive snapshot, not a claim that competing products lack every HarmoniCast feature.

| Alternative | What we should respect | Where we should compete |
|---|---|---|
| **Plexamp** | Its core experience is free, and its premium offering includes discovery and playback features such as Guest DJ, downloads, and Sonic Adventure. “Another Plex player” is not enough. | Make HarmoniCast the preferred player for **real people requesting music together**, and for listeners who prefer its particular mix controls. |
| **Symfonium** | Broad music-source support, offline caching, casting, smart playlists, personal mixes, and extensive customization. It is a one-time purchase, not a subscription. | Compete on a **focused, easy-to-understand shared-listening experience**, not the longest feature list. |
| **Spotify Jam** | It already provides collaborative queues, including through Android Auto. Its documentation says hosting requires Premium, while free users can join in person. | Offer a compelling version of that social experience **for an existing Plex music collection**, with browser participation. Do not claim passenger requests themselves are a new invention. |

Competitor references: [Plexamp](https://www.plex.tv/plexamp/), [Symfonium](https://symfonium.app/), and [Spotify Jam support](https://support.spotify.com/us/article/jam/). Verify current feature availability and subscription requirements before publishing a comparison.

**Our first audience should be Android-using Plex music-library owners who regularly listen with family or friends.**

They already have the difficult prerequisite: a music library they care about. We are asking them to try a different player, not acquire a collection, install a new media-server platform, or leave their existing ecosystem.

That fits the current project: HarmoniCast connects directly to Plex, with nearby rooms hosted by the Android device. MusicGrabber is optional—not another required server for ordinary playback. See the [README](../README.md) and [standalone architecture](STANDALONE_ARCHITECTURE.md).

We should **not** initially target general Spotify users, people whose main requirement is an iPhone player, or users looking for a universal frontend for every music server. The [roadmap](../ROADMAP.md) keeps playback Plex-only; growth does not require throwing that scope away.

## 2. Sell a situation, not a collection of features

Proposed lead message:

> **Your Plex library. Everyone gets a turn.**

Supporting copy:

> Play your music on Android while nearby friends and family request tracks from their browsers. Requests take priority, and a configurable automatic mix fills the gaps.

That is grounded in the documented request-first queue, participant fairness, browser guest controls, and automatic mix. Retain the nearby-network qualification rather than imply that anyone can join from anywhere. See [architecture](STANDALONE_ARCHITECTURE.md).

The first demonstration should tell this story:

**Music is playing → a passenger or guest opens the invitation → they request a song → the request appears → their song plays → the mix continues.**

Show the actual join steps and any required network setup. A polished video that cuts around the inconvenient part would hide precisely the friction we need to solve.

For the car demonstration, set everything up while parked and show the passenger handling requests—not the driver managing a web interface.

### Give the automatic mix its own message

“Weighted smart playlist” makes sense to us, but test this phrasing with prospective users:

> **More of your favorites. Some music you haven’t rated yet. Fewer recent repeats.**

The controls support rated/unrated balance, preference for higher ratings, and repeat-avoidance windows. Those are concrete behaviors we can demonstrate, rather than claiming the app somehow has a uniquely intelligent recommendation engine. See [automatic mix documentation](../README.md#automatic-mix).

Working hypothesis:

**Shared requests earn the first trial. The mix earns additional listening time.**

That needs testing. Some users may turn out to care much more about the mix than rooms; record those motivations separately rather than assume everyone values both.

## 3. Make trying it feel reversible

The initial call to action should not be “switch from Plexamp.”

It should be:

> **Keep Plexamp. Try HarmoniCast the next time everyone wants a say in the music.**

This preserves the honesty of the earlier introduction without effectively telling interested people to go away.

There is an important distinction between:

> “Plexamp is good, so you probably shouldn’t bother.”

and:

> “Plexamp is good. Here’s the situation I built HarmoniCast to handle differently.”

Be confident about the second without pretending HarmoniCast is already the best player for everyone.

### Preserve the user’s investment in their library

A low-risk trial also means being careful with ratings. HarmoniCast’s automatic ratings can overwrite Plex ratings, and turning the feature off does not undo earlier changes. The explicit opt-in is therefore important to the growth strategy, not merely a settings detail. See [automatic ratings documentation](../README.md#automatic-ratings).

Keep the first-run path centered on listening and requesting. Explain rating changes separately, with examples, before anyone enables them.

The promise should be **“try a different listening experience,”** not “let an unfamiliar app immediately rearrange years of preferences.”

## 4. Treat reliability and onboarding as growth work

Before increasing promotion, establish one acceptance test:

> **Can an unfamiliar Plex owner install HarmoniCast, play music, invite a guest, and get that guest’s request played without us walking them through it?**

That is more useful than adding five more features to the announcement.

There are a few concrete reasons to prioritize this.

**The September 13 release still identifies real-device checks to finish.** The [v1.1.12 release notes](https://github.com/sneedster/harmonicast/releases/tag/v1.1.12) say real Wi-Fi-to-cellular playback and Android installer acceptance still need device verification. Those are especially relevant when the proposed selling point involves using the app in a car. Treat this as a dated finding and consult the latest [validation notes](ANDROID_VALIDATION.md) before assigning work.

**The networking promise needs to match the demonstration.** Nearby rooms are not internet-wide guest sessions. Test home Wi-Fi and the intended in-car setup separately, including wireless Android Auto where applicable, with both Android and iPhone guest browsers. Do not assume that success on the living-room network proves the car experience. The documented local-network scope makes that distinction important. See [architecture](STANDALONE_ARCHITECTURE.md).

**The documentation needs one immediate trust fix.** At the time of this discussion, the [privacy policy](../PRIVACY.md) says automatic update checks are off by default and run at most daily; the [v1.1.12 release](https://github.com/sneedster/harmonicast/releases/tag/v1.1.12) and [README](../README.md#app-updates) say they run on fresh launches by default, while preserving existing opt-outs. Reconcile that before actively sending more users to the project. Recheck whether it has already been fixed before editing.

**Development features should not become acquisition promises prematurely.** Shared-library room hosting and delegated acquisition still have acceptance work called out in the project documents. Start the main campaign with the verified owner workflow, then broaden it when the shared-user path is ready. See the [README](../README.md#shared-plex-access-development), [roadmap](../ROADMAP.md), and [shared Plex access plan](SHARED_PLEX_ACCESS_PLAN.md).

Proposed feature-priority rule:

> **For the initial growth push, favor changes that help a new household reach—and repeat—a successful listening session.**

That includes understandable connection errors, reliable playback recovery, clear request status, and an obvious explanation when the automatic mix has exhausted eligible tracks. It does not require a new visual theme or another playback backend.

## 5. Use three acquisition channels, in this order

### A. Recruit people with the exact problem

Start with a small group of Plex owners who say something like:

> “My kids keep asking me to change songs.”

or:

> “I want people at a gathering to request music without passing around my phone.”

The project owner reported already posting to r/plexamp. Use that as a starting point for interested volunteers rather than immediately publishing another general announcement.

For additional outreach, test relevant Plex, self-hosting, and Unraid communities where their posting rules permit it. Use the specific scenario, disclose that it is your project, and ask for feedback from people who actually have the problem. Do not scatter the same promotional text across every forum.

Draft invitation:

> I started HarmoniCast as a Codex experiment because I wanted my kids to be able to request songs from our Plex library in the car. It also has a configurable automatic mix. I’m looking for a few Plex-owning households to try the guest-request workflow and tell me where it becomes annoying. You don’t need to stop using Plexamp.

Keep the voice practical, personal, and transparent about AI-assisted development. Avoid generic product-announcement language such as “Introducing the future of collaborative audio experiences.”

### B. Publish answers to specific searches

Create a small number of genuinely useful pages around problems such as **letting passengers request Plex music**, **running a Plex music request queue at home**, and **configuring a favorites/unrated mix**.

Each page should show the workflow, requirements, limitations, and an actual demonstration. Treat these as search topics to test—not claims that we have established large search volumes.

A fair **HarmoniCast versus Plexamp** page could be useful too, but it should explain when Plexamp is the better fit. Compare complete tasks, not a table where every HarmoniCast cell gets a green checkmark.

The purpose is to reach someone already looking for the experience, not convince an indifferent listener that they need another app.

### C. Let successful sessions introduce the app

The project includes a browser “Get the Android app” link and an in-app sharing flow. Guests can continue participating without installing anything. See [sharing documentation](../README.md#share-the-app).

That gives us a plausible referral path:

**A host runs a good session → a guest enjoys it → a guest who also has a Plex library tries hosting later.**

But this is a hypothesis, not an automatic viral-growth machine. Many guests will never own a Plex library, and that is fine.

Keep the installation invitation unobtrusive. Forcing guests to install the app would undermine the very experience we are trying to promote.

Hold off on paid advertising until we know that qualified users can activate and return. More downloads are not helpful when we have not yet learned why people stay.

## 6. Measure usage we actually won

We do not have a credible denominator for “the Plex music-player market,” so do not set a goal like “capture 2%.” Nor should we mistake GitHub stars or APK downloads for retained users.

For the first pilot, use **20 qualified households** and a simple scorecard:

| Question | What to record |
|---|---|
| Can people get started? | How many complete a first guest-request session without live help? |
| Does the experience repeat? | How many run another session within 14 days? |
| Are we displacing an alternative? | What would they have used for that same occasion before HarmoniCast? |
| Is the mix independently valuable? | Do they return for solo listening, and specifically because of the mix? |
| What is the support burden? | Which failures require our intervention, and how often? |

As **proposed pilot targets, not forecasts or industry benchmarks**, aim for 15 of the 20 households to complete the first session, eight of those activated households to repeat within two weeks, and five to describe a specific situation where HarmoniCast has become their preferred choice.

The small sample will not establish market share. It will tell us whether there is a repeatable reason to choose the product.

The [privacy policy](../PRIVACY.md) explicitly says the app contains no analytics or telemetry. Do not silently undermine that to measure growth. Start with consenting testers, short follow-ups, and user-submitted diagnostics; do not collect listening histories or guest identities by default.

The feedback determines the next move:

**People cannot get started:** fix onboarding.

**People succeed once but do not return:** investigate whether the benefit is too weak, the occasion too infrequent, or the experience too troublesome.

**People return but cannot recommend it easily:** improve the explanation, installation path, and sharing materials.

**People repeatedly choose it over their old workflow:** expand outreach to more people with that same problem.

## 7. A practical first-month plan

This is a relative four-week pilot sequence, not a committed calendar or authorization to start outreach.

**Week 1: Make the promise dependable.** Reconcile the documentation, complete the critical device checks, observe a few fresh installations, and record one honest end-to-end demonstration.

**Week 2: Recruit the pilot cohort.** Lead with the family/passenger scenario. Record what each household currently uses and what specifically motivated the trial.

**Week 3: Fix the biggest observed obstacles.** Prioritize problems that prevent installation, joining, requests, playback, or repeat use. Avoid turning every suggestion into a roadmap commitment.

**Week 4: Follow up and choose the next push.** Check repeat use and actual substitution. Publish the practical setup guide and demonstration, incorporating what the pilot taught us. Seek permission before using any tester quotes.

For distribution, GitHub signed APKs are the documented supported route. Continue the previously discussed F-Droid effort, but F-Droid availability was not verified in the strategy discussion; the plan should not depend on an existing listing. See [installation](../README.md#install) and [architecture](STANDALONE_ARCHITECTURE.md).

## Recommendation

**Lead with shared listening. Differentiate with the controllable mix. Remove the friction of trying it. Expand only after people come back.**

Our advantage does not need to be a feature that nobody can copy. It can be sustained attention to a particular experience: making a personal music collection enjoyable for everyone in the car or room, without making its owner the full-time request operator.

The first meaningful victory is not someone uninstalling Plexamp.

It is someone saying:

> **“We still have Plexamp. But when we’re all listening together, we use HarmoniCast.”**
