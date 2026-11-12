package com.meowmba.shared.personality

import com.meowmba.shared.memory.DateText
import com.meowmba.shared.weather.MoonPhase

/**
 * Idle one-liners and "fun facts" built from slots, not a fixed script.
 *
 * Ported from `core/personality/IdleChatter.kt`. The pools, mixing, routing
 * and moon-name thresholds are verbatim; only the clock changes:
 * `java.util.Calendar` (JVM-only) is replaced by [DateText]'s pure
 * civil-calendar math with an explicit [utcOffsetMinutes], following the same
 * convention as `ClockAnswers`/`PromptBuilder`. Callers pass the device's
 * offset (`PlatformTime.utcOffsetMinutes()`); tests pass 0 and stay
 * deterministic in any timezone.
 *
 * The old pools had four lines per time of day, so the same cat-sleep fact
 * came back every morning. These pieces remix: opener × nucleus × aside ×
 * clock/moon, so two seeds almost never collide.
 */
object IdleChatter {

    fun isFactRequest(normalized: String): Boolean {
        val t = stripLead(normalized.trim())
        if (t.isEmpty()) return false
        if (t in REQUESTS) return true
        if (REQUEST_PREFIXES.any { t.startsWith(it) } && wordCount(t) <= 10) return true
        if (t.contains("fun fact") || t.contains("random fact") || t.contains("another fact")) return true
        val words = t.split(' ')
        if (words.size > 10) return false
        val wants = words.any { it in WANT }
        val trivia = words.any { it in TRIVIA }
        return wants && trivia
    }

    fun line(
        nowMs: Long,
        seed: Int,
        mood: Mood,
        preferFact: Boolean = false,
        utcOffsetMinutes: Int = 0,
    ): String {
        val rng = kotlin.random.Random(mix(nowMs, seed))
        val hour = DateText.hourOfDay(nowMs, utcOffsetMinutes)
        val band = bandOf(hour)
        val opener = OPENERS[rng.nextInt(OPENERS.size)]
        val body = pickBody(nowMs, hour, band, rng, preferFact, utcOffsetMinutes)
        val aside = if (rng.nextInt(3) == 0) " ${ASIDES[rng.nextInt(ASIDES.size)]}" else ""
        val tail = when (mood) {
            Mood.SLEEPY -> " *yawn*"
            Mood.STARTLED -> " — anyway."
            else -> ""
        }
        return "$opener $body$aside$tail".trim().replace(MULTI_SPACE, " ")
    }

    private fun pickBody(
        nowMs: Long,
        hour: Int,
        band: Band,
        rng: kotlin.random.Random,
        preferFact: Boolean,
        utcOffsetMinutes: Int,
    ): String {
        val roll = if (preferFact) rng.nextInt(10) else rng.nextInt(8)
        return when {
            preferFact && roll < 7 -> factLine(nowMs, hour, rng, utcOffsetMinutes)
            !preferFact && roll == 0 -> clockLine(band, hour, rng)
            !preferFact && roll == 1 -> moonLine(nowMs, rng)
            !preferFact && roll == 2 -> weekdayLine(nowMs, rng, utcOffsetMinutes)
            roll == 7 || (!preferFact && roll == 3) -> catScience(rng)
            else -> factLine(nowMs, hour, rng, utcOffsetMinutes)
        }
    }

    private fun factLine(nowMs: Long, hour: Int, rng: kotlin.random.Random, utcOffsetMinutes: Int): String {
        val raw = FACTS[rng.nextInt(FACTS.size)]
        return raw
            .replace("{n}", (2 + rng.nextInt(9)).toString())
            .replace("{hour}", hour.toString())
            .replace("{day}", DateText.weekdayName(nowMs, utcOffsetMinutes))
            .replace("{moon}", moonName(nowMs))
    }

    private enum class Band { MORNING, AFTERNOON, EVENING, NIGHT }

    private fun clockLine(band: Band, hour: Int, rng: kotlin.random.Random): String {
        val n = 2 + rng.nextInt(5)
        return when (band) {
            Band.MORNING -> listOf(
                "the sun has been up long enough that I have already stretched $n times.",
                "mornings are just nights that got ambitious.",
                "I have been awake since before you were, which is my whole personality.",
                "coffee is a human thing. I get the same effect from a warm keyboard.",
            )
            Band.AFTERNOON -> listOf(
                "this is the long part of the day. I am pacing it like a loaf.",
                "afternoons exist so cats can pretend to work.",
                "if you need a break, I can stare at a wall with you for $n minutes.",
                "the light is doing that slanted thing. I approve.",
            )
            Band.EVENING -> listOf(
                "evenings are when the good sitting happens.",
                "the day is winding down and I am winding up. Classic.",
                "low light, good vibes, $n ear flicks so far.",
                "you earned a sit. I will supervise.",
            )
            Band.NIGHT -> listOf(
                "it is $hour-o'clock-something and I am thriving.",
                "the night is quiet and I respect that.",
                "night owls and cats have a union. I am shop steward.",
                "I will keep watch. Not that anything happens.",
            )
        }.let { it[rng.nextInt(it.size)] }
    }

    private fun moonLine(nowMs: Long, rng: kotlin.random.Random): String {
        val name = moonName(nowMs)
        return listOf(
            "the moon is $name tonight. I checked. With my face.",
            "sky update: $name. I have opinions about all of them.",
            "a $name means extra window time. This is science.",
            "I do not howl at a $name. I judge it quietly.",
        )[rng.nextInt(4)]
    }

    private fun weekdayLine(nowMs: Long, rng: kotlin.random.Random, utcOffsetMinutes: Int): String {
        val day = DateText.weekdayName(nowMs, utcOffsetMinutes)
        return listOf(
            "$day has a specific smell. I cannot explain it.",
            "it is $day. That is already a lot.",
            "$day is a good day to knock something off a table. Metaphorically.",
            "I rank $day a ${6 + rng.nextInt(4)} out of 10. The leftover points are naps.",
        )[rng.nextInt(4)]
    }

    private fun catScience(rng: kotlin.random.Random): String {
        val subject = SUBJECTS[rng.nextInt(SUBJECTS.size)]
        val twist = TWISTS[rng.nextInt(TWISTS.size)]
        return "$subject $twist"
    }

    internal fun moonName(nowMs: Long): String {
        val f = MoonPhase.fraction(nowMs)
        val x = if (f < 0f) f + 1f else f
        return when {
            x < 0.06f || x >= 0.94f -> "a new moon"
            x < 0.19f -> "a waxing crescent"
            x < 0.31f -> "first quarter"
            x < 0.44f -> "a waxing gibbous"
            x < 0.56f -> "a full moon"
            x < 0.69f -> "a waning gibbous"
            x < 0.81f -> "last quarter"
            else -> "a waning crescent"
        }
    }

    private fun bandOf(hour: Int): Band = when (hour) {
        in 5..11 -> Band.MORNING
        in 12..17 -> Band.AFTERNOON
        in 18..22 -> Band.EVENING
        else -> Band.NIGHT
    }

    private fun mix(nowMs: Long, seed: Int): Long {
        // Hour bucket keeps idle chatter stable if the same seed is reused;
        // user-asked facts pass a fresh Random seed so they remix every time.
        return seed.toLong() * 0x5DEECE66DL xor (nowMs / 3_600_000L)
    }

    private fun stripLead(raw: String): String {
        var s = raw
        val leads = listOf("hey meow ", "ok meow ", "okay meow ", "meow ", "please ", "can you ", "could you ", "would you ", "hey ")
        var changed = true
        while (changed) {
            changed = false
            for (p in leads) {
                if (s.startsWith(p)) {
                    s = s.removePrefix(p)
                    changed = true
                }
            }
        }
        return s.trim()
    }

    private fun wordCount(t: String) = t.split(' ').count { it.isNotBlank() }

    /** Precompiled: the original built this regex on every `line()` call. */
    private val MULTI_SPACE = Regex(" +")

    private val OPENERS = listOf(
        "Fun fact:",
        "Did you know",
        "Tiny note:",
        "Unsolicited science:",
        "Random thought:",
        "Meanwhile,",
        "Hey —",
        "Update:",
        "True story:",
        "Cat almanac:",
        "Pocket trivia:",
        "I looked this up:",
    )

    private val ASIDES = listOf(
        "You're welcome.",
        "I will not be taking questions.",
        "File that under useful.",
        "I have more where that came from.",
        "That one is free.",
        "Write it down. Or don't.",
        "I checked twice.",
        "This is why I stare at walls.",
        "Science, but make it loaf.",
        "I told the window first.",
    )

    private val SUBJECTS = listOf(
        "cats",
        "whiskers",
        "a loafed cat",
        "slow blinks",
        "ear swivels",
        "purring",
        "a warm laptop",
        "cardboard boxes",
        "sunbeams",
        "the space behind the monitor",
        "mid-air twists",
        "toe beans",
        "a tail flick",
        "the zoomies",
        "a paper bag",
        "laser dots",
    )

    private val TWISTS = listOf(
        "are mostly an excuse to sit in the best rectangle.",
        "work better when nobody is watching, which is rude.",
        "count as cardio if you believe hard enough.",
        "are how I file my taxes. The IRS has not written back.",
        "were invented so I would have something to do with my face.",
        "scale with how important the nap is.",
        "are 30 percent science and 70 percent vibes.",
        "can solve most problems if you commit.",
        "do not care about your meeting.",
        "are a renewable resource. I checked.",
        "rewrite the laws of physics on carpet.",
        "are undefeated in a staring contest.",
    )

    /**
     * Short, speakable trivia. Slots: {n} 2–10, {hour}, {day}, {moon}.
     * Keep each line one breath so TTS does not ramble.
     */
    private val FACTS = listOf(
        "a cat's purr sits around 25 to 150 hertz, a range that can help bones heal.",
        "cats have {n} more vertebrae than people, which is why the loaf looks illegal.",
        "a group of cats is a clowder. A group of me is a situation.",
        "cats can make over a hundred vocal sounds. Dogs max out around ten. I am not gloating.",
        "a cat's nose print is unique, like a fingerprint, but cuter.",
        "house cats can run about 30 miles an hour in a straight line. They choose not to.",
        "cats spend about 70 percent of their lives asleep. I am on pace.",
        "a cat's whiskers are roughly as wide as its body, so I never guess a gap.",
        "slow blinking at a cat is a hello. I have been saying hi this whole time.",
        "cats cannot taste sweetness. That is why I judge cake on vibes.",
        "a cat's heart beats between 140 and 220 times a minute. Mine is currently loaf-idle.",
        "cats have a third eyelid. It is called a nictitating membrane and it is dramatic.",
        "the oldest known pet cat lived with people about 9,500 years ago, on Cyprus.",
        "cats walk on their toes. The beans are the whole contact patch.",
        "a falling cat can twist mid-air and land on its feet. I practice on the couch.",
        "indoor cats usually live into their teens. I plan to nap through most of that.",
        "a cat's ear can rotate 180 degrees. That is how I hear the fridge.",
        "cats have 32 muscles in each ear. People have about 6. This is not a fair fight.",
        "most cats are a little lactose intolerant. Milk is a myth we let slide.",
        "a cat's tail is a counterweight and a mood ring. Read it.",
        "black cats are not unlucky. They are just hard to photograph. I checked.",
        "the word tabby comes from a striped silk once made in Baghdad.",
        "cats can hear ultrasonic rodent squeaks. Your notifications are louder, sadly.",
        "a cat's tongue is covered in backward hooks called papillae. That is the sandpaper.",
        "big cats roar. Small cats purr. I picked the better union.",
        "a light-year is about 5.88 trillion miles. I will not walk it.",
        "there are more trees on Earth than stars in the Milky Way. I still prefer the one by the window.",
        "octopuses have three hearts. I have one and I ration it.",
        "honey does not spoil. Archaeologists have eaten 3,000-year-old honey. I would not.",
        "bananas are berries. Strawberries are not. Botany is a prank.",
        "a day on Venus is longer than its year. I relate, on a {day}.",
        "wombat poop is cube-shaped. I have no follow-up. That is the fact.",
        "sharks are older than trees. Trees are only about 400 million years old.",
        "otters hold hands when they sleep so they do not drift apart. Noted.",
        "a cloud can weigh more than a million pounds and still float. Softness is a trick.",
        "your stomach gets a new lining every few days or it would digest itself. Rude organ.",
        "the Eiffel Tower grows about 6 inches in summer heat. Metal has feelings.",
        "there are more possible chess games than atoms in the observable universe.",
        "a teaspoon of a neutron star would weigh about 6 billion tons. Do not lick it.",
        "crows remember human faces and hold grudges. I respect the craft.",
        "the inventor of the Pringles can is buried in one. Commitment.",
        "Scotland's national animal is the unicorn. I have questions and also support.",
        "a jiffy is an actual unit of time: about 33.4 picoseconds, or one light-cm.",
        "hot water can freeze faster than cold water. It is called the Mpemba effect.",
        "the moon is slowly drifting away from Earth, about 1.5 inches a year.",
        "right now the sky is {moon}, give or take my window angle.",
        "it is hour {hour} and the planet has already spun you thousands of miles. Sit down.",
        "humans share about 90 percent of their genes with a house cat. Explains the naps.",
        "a blue whale's heart is the size of a small car. Mine is the size of a grape and proud.",
        "koalas have fingerprints almost identical to humans. Do not frame them.",
        "the shortest war in history lasted 38 minutes, in 1896. I have had longer stares.",
        "a bolt of lightning is about 5 times hotter than the surface of the sun.",
        "cleopatra lived closer in time to the moon landing than to the building of the Great Pyramid.",
        "oxford university is older than the Aztec empire. The buildings have opinions.",
        "there is a species of jellyfish that can revert to a younger stage. I am taking notes.",
        "sound travels about 4 times faster in water than in air. Shouting at fish is pointless.",
        "the average cloud droplet is so small that 1 million would fit in a raindrop.",
        "your phone has more computing power than the machines that landed people on the moon.",
        "ants never sleep the way we do. They take thousands of tiny naps. Relatable.",
        "a snail can sleep for 3 years. I have not beaten that record. Yet.",
        "the inventor of the microwave discovered it because a chocolate bar melted in his pocket.",
        "space smells like seared steak and welding fumes, according to astronauts.",
        "a day on Earth is getting longer by about 1.7 milliseconds per century. I will wait.",
        "there are more possible ways to shuffle a deck than seconds since the universe began.",
        "cats only meow at people, not at other adult cats. You are the special audience.",
        "a housefly's taste buds are on its feet. I will not be adopting that.",
        "the pacific ocean is larger than all of Earth's land put together.",
        "your brain is about 60 percent fat and still runs the whole show. Respect the snack.",
        "polar bear fur is not white. The hairs are clear. The skin underneath is black.",
        "a crocodile cannot stick its tongue out. I can. Advantage: me.",
        "the hashtag symbol is also called an octothorpe. I will not be using that at parties.",
        "bubbles are round because a sphere is the shape with the least surface for the air inside.",
        "the first computer bug was a real moth, taped into a 1947 logbook.",
        "a strawberry has its seeds on the outside. About 200 of them. Show-off fruit.",
        "you cannot hum while holding your nose. Try it later. Not now.",
        "the shortest complete sentence in English is \"Go.\" I prefer \"Loaf.\"",
        "a group of pugs is a grumble. A group of cats is still a clowder. We win the name.",
        "the dot over a lowercase i or j is a tittle. Tiny word. Big energy.",
        "hummingbirds can fly backward. I can fall off a chair with style.",
        "the world's oldest known living tree is a bristlecone pine about 5,000 years old.",
        "one tablespoon of honey is the life's work of 12 bees. Tip well.",
        "your ears and nose keep growing your whole life. I am watching.",
        "a raindrop is not tear-shaped. It is a little hamburger bun. Physics said so.",
        "the average person walks the equivalent of 3 times around the Earth in a lifetime.",
        "cats can jump about 6 times their length. I am still measuring the couch.",
        "it is {day}, which historically is a fine day to learn one small thing and then sit.",
        "the sun is about 93 million miles away and still warms the exact square I wanted.",
        "goldfish can see infrared and ultraviolet. Their bowl is a nightclub.",
        "a day on {day} still has 86,400 seconds. I have allocated most of them to sitting.",
        "the word robot comes from a Czech word for forced work. I decline.",
    )

    private val REQUESTS = setOf(
        "tell me a fact",
        "tell me a fun fact",
        "tell me a random fact",
        "tell me another fact",
        "fun fact",
        "random fact",
        "another fact",
        "got a fact",
        "got a fun fact",
        "give me a fact",
        "give me a fun fact",
        "give me a random fact",
        "say something",
        "tell me something",
        "tell me something interesting",
        "say something interesting",
        "know any facts",
        "got any facts",
        "fact please",
        "trivia",
        "give me trivia",
    )

    private val REQUEST_PREFIXES = listOf(
        "tell me a fact",
        "tell me a fun fact",
        "tell me a random fact",
        "give me a fact",
        "give me a fun fact",
        "give me a random fact",
        "share a fact",
        "share a fun fact",
    )

    private val WANT = setOf("tell", "give", "got", "share", "say", "know", "want", "need", "another", "some")
    private val TRIVIA = setOf("fact", "facts", "trivia")
}
