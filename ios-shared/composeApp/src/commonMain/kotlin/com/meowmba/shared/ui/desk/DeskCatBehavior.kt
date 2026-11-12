package com.meowmba.shared.ui.desk

import kotlin.random.Random

/** Acting only: these poses never execute a tool, send mail or change a goal. */
enum class DeskCatPose { REST, WALK, GROOM, STRETCH, SNIFF, TYPE, LETTER, WRITE, LISTEN, TALK,
    /** Curled up asleep (bed, couch). */ SLEEP,
    /** Head down at the food tray. */ EAT,
    /** Scratching in the litter box. */ DIG,
    /** Sitting up on a cat-tower perch, looking out. */ PERCH,
    /** Sitting, looking up at the TV. */ WATCH,
    /** Controller in paws at the old console. */ GAME,
    /** Head bobbing to the stereo. */ BOB,
    /** A book open in its paws. */ READ,
    /** Up on its hind legs, clawing a scratching post. */ SCRATCH }

/** The cat's face. Independent of the pose, so any pose can carry any mood. */
enum class DeskCatFace { NEUTRAL, HAPPY, PURR, LOVE, CURIOUS, SURPRISED, FOCUSED, SLEEPY, ASLEEP, YAWN, ANNOYED, PLAYFUL, CHEW }

enum class DeskCatActivity { IDLE, LISTENING, THINKING, SPEAKING }

/** A real reply event, not a canned description of what the cat appears to do. */
data class DeskCatReply(val key: String, val text: String)

internal data class DeskCatSpot(val x: Float, val y: Float)
internal data class DeskCatAct(val spot: DeskCatSpot, val pose: DeskCatPose, val durationMs: Long,
    val face: DeskCatFace? = null, val facingLeft: Boolean? = null)

internal object DeskCatBehavior {
    /** Floor level for the cat's top edge (paws on the floor at y 740). */
    const val FLOOR = 656f
    val home = DeskCatSpot(180f, 516f)
    val computer = DeskCatSpot(150f, 516f)
    val letters = DeskCatSpot(250f, 516f)
    val board = DeskCatSpot(126f, 300f)

    // Living room (left)
    val couch = DeskCatSpot(-242f, 598f)
    val livingTower get() = DeskCatSpot(if (DeskLayout.roomy) -320f else -352f, 382f)
    val stereo = DeskCatSpot(-192f, FLOOR)
    val bookshelf = DeskCatSpot(-382f, FLOOR)
    // Bedroom (right)
    val bed get() = DeskCatSpot(if (DeskLayout.roomy) 690f else 702f, 578f)
    val windowPerch = DeskCatSpot(538f, 294f)
    val food get() = DeskCatSpot(if (DeskLayout.roomy) 512f else 564f, FLOOR)
    val litter = DeskCatSpot(484f, 626f)
    val arcade = DeskCatSpot(548f, FLOOR)

    fun atObject(obj: DeskObject): DeskCatAct = when (obj) {
        DeskObject.BUILD -> DeskCatAct(computer, DeskCatPose.TYPE, 3_600, DeskCatFace.FOCUSED)
        DeskObject.INBOX -> DeskCatAct(letters, DeskCatPose.LETTER, 3_600, DeskCatFace.CURIOUS)
        DeskObject.PROJECT, DeskObject.TODAY -> DeskCatAct(board, DeskCatPose.WRITE, 3_600, DeskCatFace.FOCUSED)
        DeskObject.DRAWER_TOP, DeskObject.DRAWER_BOTTOM -> DeskCatAct(DeskCatSpot(64f, FLOOR), DeskCatPose.SNIFF, 2_400, DeskCatFace.CURIOUS)
        DeskObject.IDEA -> DeskCatAct(home, DeskCatPose.SNIFF, 2_400, DeskCatFace.LOVE)
        DeskObject.MONEY -> DeskCatAct(DeskCatSpot(obj.catX, 516f), DeskCatPose.SNIFF, 2_400, DeskCatFace.CURIOUS)
        DeskObject.BIG_WINDOW, DeskObject.TOWER_BED -> DeskCatAct(windowPerch, DeskCatPose.PERCH, 5_000, DeskCatFace.CURIOUS)
        DeskObject.ARCADE -> DeskCatAct(arcade, DeskCatPose.GAME, 5_000, DeskCatFace.PLAYFUL, facingLeft = true)
        DeskObject.BED -> DeskCatAct(bed, DeskCatPose.SLEEP, 9_000, DeskCatFace.ASLEEP)
        DeskObject.FOOD -> DeskCatAct(food, DeskCatPose.EAT, 4_500, DeskCatFace.CHEW, facingLeft = false)
        DeskObject.LITTER -> DeskCatAct(litter, DeskCatPose.DIG, 3_600, DeskCatFace.FOCUSED)
        DeskObject.BOOKSHELF -> DeskCatAct(bookshelf, DeskCatPose.READ, 4_500, DeskCatFace.FOCUSED)
        DeskObject.BIG_TV -> DeskCatAct(couch, DeskCatPose.WATCH, 5_000, DeskCatFace.CURIOUS)
        DeskObject.STEREO -> DeskCatAct(stereo, DeskCatPose.BOB, 5_000, DeskCatFace.PURR, facingLeft = false)
        DeskObject.COUCH -> DeskCatAct(couch, DeskCatPose.SLEEP, 8_000, DeskCatFace.ASLEEP)
        DeskObject.TOWER_LIVING -> DeskCatAct(livingTower, DeskCatPose.PERCH, 4_500, DeskCatFace.HAPPY)
        else -> if (obj.isPicture) DeskCatAct(DeskCatSpot(obj.catX, FLOOR), DeskCatPose.REST, 2_400, DeskCatFace.CURIOUS)
            else DeskCatAct(DeskCatSpot(obj.catX, 516f), DeskCatPose.SNIFF, 2_400)
    }

    /** Long, irregular rests; no interval at which the room must perform. */
    fun restMs(random: Random, baseMs: Long): Long {
        val base = baseMs.coerceIn(4_000, 60_000)
        return random.nextLong(base / 2, base + base / 2 + 1)
    }

    /** Office-only idle acting (the original Desk). */
    fun idle(random: Random, previous: DeskCatPose, hasMail: Boolean, hasGoal: Boolean): DeskCatAct {
        val choices = mutableListOf(DeskCatPose.GROOM, DeskCatPose.STRETCH, DeskCatPose.SNIFF, DeskCatPose.WALK)
        if (hasMail) choices += DeskCatPose.LETTER
        if (hasGoal) choices += DeskCatPose.WRITE
        choices.remove(previous)
        val pose = choices.random(random)
        val spot = when (pose) {
            DeskCatPose.LETTER -> letters
            DeskCatPose.WRITE -> board
            else -> if (random.nextBoolean()) DeskCatSpot(random.nextInt(32, 275).toFloat(), random.nextInt(654, 659).toFloat())
                    else DeskCatSpot(random.nextInt(126, 251).toFloat(), 516f)
        }
        val face = when (pose) {
            DeskCatPose.GROOM -> DeskCatFace.PURR
            DeskCatPose.STRETCH -> if (random.nextBoolean()) DeskCatFace.YAWN else DeskCatFace.SLEEPY
            DeskCatPose.LETTER -> DeskCatFace.CURIOUS
            DeskCatPose.WRITE -> DeskCatFace.FOCUSED
            else -> listOf(DeskCatFace.NEUTRAL, DeskCatFace.CURIOUS, DeskCatFace.PLAYFUL).random(random)
        }
        return DeskCatAct(spot, if (pose == DeskCatPose.WALK) DeskCatPose.REST else pose, random.nextLong(2_400, 5_201), face)
    }

    /**
     * Whole-apartment idle acting. [roomChance] is how often the cat heads to a different room
     * (higher on the folded cover screen, where it wanders back and forth between rooms).
     */
    fun apartmentIdle(random: Random, previous: DeskCatPose, current: DeskRoom, hasMail: Boolean, hasGoal: Boolean,
                      musicPlaying: Boolean, videoPlaying: Boolean, roomChance: Float): DeskCatAct {
        val room = if (random.nextFloat() < roomChance) DeskRoom.entries.filter { it != current }.random(random) else current
        val acts: List<DeskCatAct> = when (room) {
            DeskRoom.OFFICE -> return idle(random, previous, hasMail, hasGoal)
            DeskRoom.LIVING -> buildList {
                add(DeskCatAct(couch, DeskCatPose.SLEEP, random.nextLong(6_000, 10_001), DeskCatFace.ASLEEP))
                add(DeskCatAct(livingTower, DeskCatPose.PERCH, random.nextLong(3_600, 6_001), DeskCatFace.HAPPY))
                add(DeskCatAct(DeskCatSpot(if (DeskLayout.roomy) -340f else -372f, FLOOR), DeskCatPose.SCRATCH, 3_000, DeskCatFace.PLAYFUL, facingLeft = false))
                add(DeskCatAct(bookshelf, DeskCatPose.READ, random.nextLong(4_000, 6_001), DeskCatFace.FOCUSED))
                if (videoPlaying) add(DeskCatAct(couch, DeskCatPose.WATCH, 6_000, DeskCatFace.CURIOUS))
                if (musicPlaying) add(DeskCatAct(stereo, DeskCatPose.BOB, 6_000, DeskCatFace.PURR, facingLeft = false))
            }
            DeskRoom.BEDROOM -> listOf(
                DeskCatAct(bed, DeskCatPose.SLEEP, random.nextLong(7_000, 12_001), DeskCatFace.ASLEEP),
                DeskCatAct(windowPerch, DeskCatPose.PERCH, random.nextLong(4_000, 7_001), DeskCatFace.CURIOUS),
                DeskCatAct(food, DeskCatPose.EAT, random.nextLong(3_600, 5_201), DeskCatFace.CHEW, facingLeft = false),
                DeskCatAct(litter, DeskCatPose.DIG, 3_000, DeskCatFace.FOCUSED),
                DeskCatAct(arcade, DeskCatPose.GAME, random.nextLong(4_000, 6_001), DeskCatFace.PLAYFUL, facingLeft = true),
                DeskCatAct(DeskCatSpot(640f, FLOOR), DeskCatPose.STRETCH, 2_600, DeskCatFace.YAWN),
            )
        }
        return acts.filter { it.pose != previous }.ifEmpty { acts }.random(random)
    }

    /** The face a pose settles into when the act gives none. */
    fun defaultFace(pose: DeskCatPose): DeskCatFace = when (pose) {
        DeskCatPose.SLEEP -> DeskCatFace.ASLEEP
        DeskCatPose.EAT -> DeskCatFace.CHEW
        DeskCatPose.TYPE, DeskCatPose.WRITE, DeskCatPose.READ, DeskCatPose.DIG -> DeskCatFace.FOCUSED
        DeskCatPose.LETTER, DeskCatPose.LISTEN, DeskCatPose.WATCH, DeskCatPose.PERCH -> DeskCatFace.CURIOUS
        DeskCatPose.TALK -> DeskCatFace.HAPPY
        DeskCatPose.BOB, DeskCatPose.GROOM -> DeskCatFace.PURR
        DeskCatPose.GAME, DeskCatPose.SCRATCH -> DeskCatFace.PLAYFUL
        else -> DeskCatFace.NEUTRAL
    }

    /** What the cat says when it is petted (tapped). */
    val petLines = listOf("Mrrp?", "Hi!", "Purrr…", "Head scratches accepted.", "Mew.", "You found me.")

    fun description(pose: DeskCatPose, x: Float): String = when (pose) {
        DeskCatPose.REST -> "resting at ${x.toInt()}"
        DeskCatPose.WALK -> "walking around the room"
        DeskCatPose.GROOM -> "washing its face"
        DeskCatPose.STRETCH -> "stretching"
        DeskCatPose.SNIFF -> "exploring the room"
        DeskCatPose.TYPE -> "using the computer"
        DeskCatPose.LETTER -> "reading a letter"
        DeskCatPose.WRITE -> "writing on the board"
        DeskCatPose.LISTEN -> "listening to you"
        DeskCatPose.TALK -> "speaking to you"
        DeskCatPose.SLEEP -> "napping"
        DeskCatPose.EAT -> "eating"
        DeskCatPose.DIG -> "using the litter box"
        DeskCatPose.PERCH -> "sitting on the cat tower"
        DeskCatPose.WATCH -> "watching TV"
        DeskCatPose.GAME -> "playing a video game"
        DeskCatPose.BOB -> "listening to music"
        DeskCatPose.READ -> "reading a book"
        DeskCatPose.SCRATCH -> "scratching the post"
    }
}
