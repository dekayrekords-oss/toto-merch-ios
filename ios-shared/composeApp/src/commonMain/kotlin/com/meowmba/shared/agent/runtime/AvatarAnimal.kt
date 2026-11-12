package com.meowmba.shared.agent.runtime

/**
 * Supported avatar forms (ported from `ui/companion/LumenMotion.kt`'s
 * AvatarAnimal enum — the renderer-side animal mappings stay platform-side).
 */
enum class AvatarAnimal(val key: String, val label: String) {
    NONE("none", "Eyes only"), CAT("cat", "Cat"), DOG("dog", "Dog"),
    SQUIRREL("squirrel", "Squirrel"), RACCOON("raccoon", "Raccoon"),
    AUSTRALIAN_SHEPHERD("australian_shepherd", "Australian shepherd"),
    OWL("owl", "Owl"), DUCK("duck", "Duck"),
    RABBIT("rabbit", "Rabbit"), PIG("pig", "Pig"), ALIEN("alien", "Alien"),
    ROBOT("robot", "Robot"), PANDA("panda", "Panda");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE

        fun resolve(name: String): AvatarAnimal? = when (name.trim().lowercase()) {
            "none", "eyes", "eyes only", "just eyes", "lumen", "original", "normal" -> NONE
            "cat", "kitten", "kitty" -> CAT
            "dog", "puppy", "pup" -> DOG
            "squirrel" -> SQUIRREL
            "raccoon", "racoon" -> RACCOON
            "australian_shepherd", "australian shepherd", "australian shepherd dog", "aussie",
            "aussie dog", "shepherd", "shepherd dog" -> AUSTRALIAN_SHEPHERD
            "owl", "owlet" -> OWL
            "duck", "duckling", "mallard" -> DUCK
            "rabbit", "bunny", "bunny rabbit" -> RABBIT
            "pig", "piglet", "piggy" -> PIG
            "alien", "extraterrestrial" -> ALIEN
            "robot", "android", "robot face" -> ROBOT
            "panda", "panda bear", "giant panda" -> PANDA
            else -> null
        }
    }
}
